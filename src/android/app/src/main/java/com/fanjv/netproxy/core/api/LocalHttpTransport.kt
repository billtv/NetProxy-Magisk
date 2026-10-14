package com.fanjv.netproxy.core.api

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class LocalHttpTransport(context: Context) : ModuleApiTransport {
    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val connections = ConnectionStore(context.applicationContext)
    private val pairing = Mutex()
    private var pinnedFactory: Pair<String, SSLSocketFactory>? = null

    suspend fun connect(): ModuleConnection = withContext(Dispatchers.IO) {
        pairing.withLock {
            val existing = connections.read()
            if (existing != null) return@withLock existing
            provisionConnection("https://127.0.0.1:9091")
        }
    }

    override suspend fun reconnect(address: String): Unit = withContext(Dispatchers.IO) {
        validateModuleAddress(address)
        pairing.withLock { provisionConnection(address) }
    }

    private suspend fun provisionConnection(address: String): ModuleConnection {
        provision(address)
        repeat(20) {
            connections.read()?.takeIf { it.address == address }?.let { return it }
            delay(100)
        }
        throw ModuleApiException("api.connection_unavailable", "无法自动连接模块后台")
    }

    override suspend fun request(method: String, path: String, body: JsonObject?,
        query: Map<String, String>, mutation: Boolean): ModuleHttpResponse =
        withContext(Dispatchers.IO) {
            val credentials = connect()
            val connection = open(credentials, path, query)
            connection.requestMethod = method
            connection.readTimeout = if (!mutation) 30_000 else if (path.startsWith("/subscriptions")) 615_000 else 135_000
            try {
                execute(connection) {
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        val bytes = body.toString().toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.use { input ->
                        val bytes = input.readBytesLimited(8 * 1024 * 1024)
                        String(bytes, Charsets.UTF_8)
                    }.orEmpty()
                    if (status == 401) renew(credentials.address)
                    ModuleHttpResponse(status, text)
                }
            } catch (error: ModuleApiException) { throw error }
            catch (error: CancellationException) { throw error }
            catch (_: SSLHandshakeException) {
                currentCoroutineContext().ensureActive()
                renew(credentials.address)
                throw ModuleApiException("api.unauthorized", "模块身份验证失败，请重新连接")
            }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw ModuleApiException("api.connection_failed", "无法连接模块后台，操作结果需要重新确认")
            }
        }

    override suspend fun download(path: String, query: Map<String, String>, destination: File) = withContext(Dispatchers.IO) {
        val connection = open(connect(), path, query)
        connection.readTimeout = 150_000
        try {
            execute(connection) {
                val status = connection.responseCode
                if (status !in 200..299) {
                    ModuleApiCodec(json).decode(status, connection.errorStream?.use { String(it.readBytesLimited(64 * 1024), Charsets.UTF_8) }.orEmpty())
                    throw ModuleApiException("api.download_failed", "诊断包下载失败")
                }
                require(connection.contentType == "application/gzip")
                val count = destination.outputStream().use { output -> connection.inputStream.use { it.copyTo(output) } }
                require(count > 0 && (connection.contentLengthLong < 0 || count == connection.contentLengthLong))
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    override suspend fun watch(topics: Set<String>, consume: suspend (ModuleEvent) -> Unit): Unit = withContext(Dispatchers.IO) {
        val connection = open(connect(), "/events", mapOf("topics" to topics.sorted().joinToString(",")))
        connection.readTimeout = 30_000
        execute(connection) {
            if (connection.responseCode != 200) {
                ModuleApiCodec(json).decode(connection.responseCode, connection.errorStream?.use { String(it.readBytesLimited(64 * 1024), Charsets.UTF_8) }.orEmpty())
                throw ModuleApiException("api.events_failed", "无法订阅模块事件")
            }
            require(connection.contentType.startsWith("text/event-stream"))
            connection.inputStream.bufferedReader().use { reader ->
                var name = ""
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    require(line.length + data.length <= 8 * 1024 * 1024)
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                require(name in setOf("status", "traffic", "groups", "subscriptions"))
                                consume(ModuleEvent(name, data.toString()))
                            }
                            name = ""; data.setLength(0)
                        }
                        line.startsWith("event:") -> name = line.substringAfter(':').trim()
                        line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(':').removePrefix(" ")) }
                    }
                }
            }
        }
    }

    private fun open(credentials: ModuleConnection, path: String, query: Map<String, String>): HttpsURLConnection {
        require(path.startsWith('/') && !path.contains('?') && !path.contains('#'))
        val suffix = query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return (URL(credentials.address + "/api/v1" + path + if (suffix.isEmpty()) "" else "?$suffix")
            .openConnection(Proxy.NO_PROXY) as HttpsURLConnection).apply {
            connectTimeout = 5_000
            instanceFollowRedirects = false
            useCaches = false
            sslSocketFactory = pinnedTLS(credentials.certificate_sha256)
            setRequestProperty("Authorization", "Bearer ${credentials.secret}")
        }
    }

    private suspend fun provision(address: String) {
        // 未认证连接只触发向私有 Provider 交付，不发送或接收秘密，也不复用到业务请求。
        val connection = URL("$address/api/v1/auth/provision").openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        connection.sslSocketFactory = tls(null).socketFactory
        connection.connectTimeout = 3_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setFixedLengthStreamingMode(2)
        execute(connection) {
            connection.outputStream.use { it.write("{}".toByteArray()) }
            connection.responseCode
        }
    }

    private suspend fun renew(address: String) = pairing.withLock {
        // 更新交付副本后仍返回原请求结果，不能重发可能已被接受的写入。
        try { provision(address) } catch (_: Exception) { currentCoroutineContext().ensureActive() }
    }

    @Synchronized
    private fun pinnedTLS(fingerprint: String): SSLSocketFactory {
        pinnedFactory?.takeIf { it.first == fingerprint }?.let { return it.second }
        return tls(fingerprint).socketFactory.also { pinnedFactory = fingerprint to it }
    }

    private suspend fun <T> execute(connection: HttpsURLConnection, operation: suspend () -> T): T = coroutineScope {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        try { operation() } finally { cancellation.cancel(); connection.disconnect() }
    }

    private fun tls(fingerprint: String?): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (fingerprint == null) return
                val certificate = chain?.singleOrNull() ?: throw java.security.cert.CertificateException()
                certificate.checkValidity()
                val hash = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (hash != fingerprint) throw java.security.cert.CertificateException()
            }
        }), SecureRandom())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size() + size <= limit)
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
