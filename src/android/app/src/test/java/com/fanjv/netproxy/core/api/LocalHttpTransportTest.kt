package com.fanjv.netproxy.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

class LocalHttpTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var store: ConnectionStore
    private lateinit var transport: LocalHttpTransport
    private lateinit var credentials: ModuleConnection
    private val diskReads = AtomicInteger()

    @Before fun setUp() {
        server = MockWebServer().apply {
            protocols = listOf(Protocol.HTTP_1_1)
            useHttps(identity.socketFactory)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        credentials = ModuleConnection("https://127.0.0.1:${server.port}", "a".repeat(64), identity.fingerprint)
        store = ConnectionStore({ diskReads.incrementAndGet(); Json.encodeToString(credentials) }, {})
        transport = LocalHttpTransport(store, credentials.address)
    }

    @After fun tearDown() { server.close() }

    @Test fun reusesPinnedConnectionAndCachedCredentials() = runBlocking {
        server.enqueue(MockResponse(body = "first"))
        server.enqueue(MockResponse(body = "second"))
        assertEquals("first", read())
        assertEquals("second", read())
        val first = recorded()
        val second = recorded()
        assertEquals(first.connectionIndex, second.connectionIndex)
        assertEquals(1, second.exchangeIndex)
        assertEquals("Bearer ${credentials.secret}", first.headers["Authorization"])
        assertEquals(1, diskReads.get())
    }

    @Test fun cancellationAfterCompletionDoesNotDisconnectReusedConnection() = runBlocking {
        server.enqueue(MockResponse(body = "first"))
        server.enqueue(MockResponse.Builder().body("second").bodyDelay(1, TimeUnit.SECONDS).build())
        val first = async(Dispatchers.IO) { read() }
        assertEquals("first", first.await())
        val firstRequest = recorded()
        val decoding = CountDownLatch(1)
        val second = async(Dispatchers.IO) {
            transport.request("GET", "/info", null, emptyMap(), false) { _, input ->
                decoding.countDown()
                input.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(decoding.await(3, TimeUnit.SECONDS))
        first.cancel()
        assertEquals("second", withTimeout(3_000) { second.await() })
        assertEquals(firstRequest.connectionIndex, recorded().connectionIndex)
    }

    @Test fun cancellationWhileDecodingBodyCancelsOnlyItsCall() = runBlocking {
        server.enqueue(MockResponse.Builder().body("delayed").bodyDelay(1, TimeUnit.SECONDS).build())
        server.enqueue(MockResponse(body = "healthy"))
        val decoding = CountDownLatch(1)
        val released = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            transport.request("GET", "/info", null, emptyMap(), false) { _, input ->
                decoding.countDown()
                try { input.readBytes() } finally { released.countDown() }
            }
        }
        assertTrue(decoding.await(3, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(released.await(1, TimeUnit.SECONDS))
        assertEquals("healthy", read())
        assertEquals(2, server.requestCount)
        assertNotNull(store.read())
    }

    @Test fun decoderFailureClosesResponseAndRetainsCredentials() = runBlocking {
        server.enqueue(MockResponse(body = "first"))
        server.enqueue(MockResponse(body = "second"))
        val failure = IllegalArgumentException("test decoder")
        val result = runCatching {
            transport.request("GET", "/info", null, emptyMap(), false) { _, _ -> throw failure }
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals("second", read())
        assertNotNull(store.read())
    }

    @Test fun staleUnauthorizedResponseDoesNotClearProviderReplacement() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("denied").headersDelay(300, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse(body = "new credentials"))
        val old = async(Dispatchers.IO) { readStatus() }
        recorded()
        val replacement = credentials.copy(secret = "c".repeat(64))
        store.write(Json.encodeToString(replacement))
        assertEquals(401, old.await())
        assertEquals(replacement, store.read())
        assertEquals("new credentials", read())
        assertEquals("Bearer ${replacement.secret}", recorded().headers["Authorization"])
    }

    @Test fun unauthorizedMutationInvalidatesWithoutResending() = runBlocking {
        server.enqueue(MockResponse(code = 401, body = "denied"))
        val status = transport.request("POST", "/service/start", JsonObject(emptyMap()), emptyMap(), true) { code, input ->
            input.readBytes()
            code
        }
        assertEquals(401, status)
        assertNull(store.read())
        assertEquals(1, server.requestCount)
        recorded()
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun reconnectInvalidatesOldCredentialsAndProvisionNeverSendsBearer() = runBlocking {
        val old = requireNotNull(store.read())
        val delivered = credentials.copy(secret = "c".repeat(64))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("/api/v1/auth/provision", request.target)
                assertEquals("POST", request.method)
                assertNull(request.headers["Authorization"])
                assertNull(store.read())
                store.write(Json.encodeToString(delivered))
                return MockResponse(body = "{}")
            }
        }
        transport.reconnect(credentials.address)
        assertEquals(delivered, transport.connect())
        assertFalse(store.invalidate(old))
        assertEquals(1, server.requestCount)
        assertEquals(1, diskReads.get())
    }

    @Test fun cachedRequestsDoNotWaitForPairingLock() = runBlocking {
        val delivered = CountDownLatch(1)
        val finishProvision = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target == "/api/v1/auth/provision") {
                    store.write(Json.encodeToString(credentials))
                    delivered.countDown()
                    check(finishProvision.await(3, TimeUnit.SECONDS))
                    return MockResponse(body = "{}")
                }
                return MockResponse(body = "cached")
            }
        }
        val reconnect = async(Dispatchers.IO) { transport.reconnect(credentials.address) }
        try {
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            assertEquals("cached", withTimeout(1_000) { read() })
        } finally {
            finishProvision.countDown()
            reconnect.await()
        }
    }

    @Test fun acceptsSelfSignedPinnedCertificateButRejectsDifferentIdentity() = runBlocking {
        store.write(Json.encodeToString(credentials.copy(certificate_sha256 = "0".repeat(64))))
        server.enqueue(MockResponse(body = "must not arrive"))
        val error = runCatching { read() }.exceptionOrNull() as ModuleApiException
        assertEquals("api.unauthorized", error.resultCode)
        assertNull(store.read())
        assertEquals(0, server.requestCount)
    }

    @Test fun matchingFingerprintDoesNotBypassHostnameValidation() = runBlocking {
        server.useHttps(wrongHostIdentity.socketFactory)
        store.write(Json.encodeToString(credentials.copy(certificate_sha256 = wrongHostIdentity.fingerprint)))
        server.enqueue(MockResponse(body = "must not arrive"))
        val error = runCatching { read() }.exceptionOrNull() as ModuleApiException
        assertEquals("api.unauthorized", error.resultCode)
        assertNull(store.read())
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectsAreReturnedWithoutFollowingOrLeakingBearer() = runBlocking {
        MockWebServer().use { target ->
            target.start()
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.url("/leak")).build())
            assertEquals(302, readStatus())
            assertEquals(1, server.requestCount)
            assertEquals(0, target.requestCount)
        }
    }

    @Test fun ignoresSystemProxySelector() = runBlocking {
        val original = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = error("must not select system proxy")
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
        })
        try {
            server.enqueue(MockResponse(body = "direct"))
            assertEquals("direct", read())
        } finally { ProxySelector.setDefault(original) }
    }

    @Test fun serviceUnavailableWithImmediateRetryDoesNotResendMutation() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("unavailable").build())
        server.enqueue(MockResponse(body = "next request"))
        val status = transport.request("POST", "/service/start", JsonObject(emptyMap()), emptyMap(), true) { code, input ->
            input.readBytes()
            code
        }
        assertEquals(503, status)
        assertEquals(1, server.requestCount)
        assertEquals("next request", read())
    }

    @Test fun queryUsesUrlEncodingWithoutChangingPath() = runBlocking {
        server.enqueue(MockResponse(body = "ok"))
        transport.request("GET", "/info", null, mapOf("value" to "a b,&?#"), false) { _, input -> input.readBytes() }
        val request = recorded()
        assertEquals("/api/v1/info", request.url.encodedPath)
        assertEquals("a b,&?#", request.url.queryParameter("value"))
    }

    @Test fun responseAndImageLimitsAreEnforced() = runBlocking {
        server.enqueue(MockResponse(body = "x".repeat(8 * 1024 * 1024 + 1)))
        assertEquals("api.connection_failed", (runCatching { read() }.exceptionOrNull() as ModuleApiException).resultCode)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("x".repeat(256 * 1024)).build())
        assertEquals(256 * 1024, transport.image("/android/icons/test").size)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body("x".repeat(256 * 1024 + 1)).build())
        assertEquals("api.connection_failed", (runCatching { transport.image("/android/icons/test") }.exceptionOrNull() as ModuleApiException).resultCode)
    }

    @Test fun downloadWritesBinaryBodyAndCancellationRemovesPartialFile() = runBlocking {
        val parent = File("build/tmp").apply { mkdirs() }.toPath()
        val destination = Files.createTempFile(parent, "transport-download-", ".gz").toFile()
        try {
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/gzip").body("archive").build())
            transport.download("/logs/export", emptyMap(), destination)
            assertEquals("archive", destination.readText())
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/gzip").body("x".repeat(100))
                .throttleBody(1, 100, TimeUnit.MILLISECONDS).build())
            val downloading = launch(Dispatchers.IO) { transport.download("/logs/export", emptyMap(), destination) }
            withTimeout(3_000) {
                while (destination.length() != 1L) delay(10)
            }
            downloading.cancelAndJoin()
            withTimeout(2_000) {
                while (destination.exists()) delay(10)
            }
            assertFalse(destination.exists())
            assertNotNull(store.read())
        } finally { destination.delete() }
    }

    @Test fun sseCancellationDoesNotInterruptPooledShortRequests() = runBlocking {
        server.enqueue(MockResponse(body = "before"))
        assertEquals("before", read())
        val before = recorded()
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
            .body("event: status\ndata: {}\n\n" + ": heartbeat\n\n".repeat(10))
            .throttleBody(24, 100, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse(body = "after"))
        val consumed = CountDownLatch(1)
        val watching = launch(Dispatchers.IO) {
            transport.watch(setOf("status")) {
                assertEquals(ModuleEvent("status", "{}"), it)
                consumed.countDown()
                awaitCancellation()
            }
        }
        assertTrue(consumed.await(3, TimeUnit.SECONDS))
        val events = recorded()
        watching.cancelAndJoin()
        assertEquals("after", read())
        val after = recorded()
        assertEquals("close", events.headers["Connection"])
        assertNotEquals(before.connectionIndex, events.connectionIndex)
        assertEquals(before.connectionIndex, after.connectionIndex)
    }

    @Test fun sseEndOfStreamIsAnExplicitFailure() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(": heartbeat\n\n").build())
        val error = runCatching { transport.watch(setOf("status")) {} }.exceptionOrNull() as ModuleApiException
        assertEquals("api.disconnected", error.resultCode)
    }

    @Test fun limitedStreamBoundsSingleBulkAndSkipReads() {
        val exact = LimitedInputStream(ByteArrayInputStream(ByteArray(4)), 4)
        assertEquals(4, exact.readBytes().size)
        val bulk = LimitedInputStream(ByteArrayInputStream(ByteArray(5)), 4)
        assertThrows(IOException::class.java) { bulk.readBytes() }
        val single = LimitedInputStream(ByteArrayInputStream(ByteArray(5)), 4)
        repeat(4) { assertEquals(0, single.read()) }
        assertThrows(IOException::class.java) { single.read() }
        val skip = LimitedInputStream(ByteArrayInputStream(ByteArray(5)), 4)
        assertThrows(IOException::class.java) { skip.skip(5) }
        assertFalse(exact.markSupported())
        assertThrows(IOException::class.java) { exact.reset() }
    }

    private suspend fun read(): String = transport.request("GET", "/info", null, emptyMap(), false) { _, input ->
        input.readBytes().toString(Charsets.UTF_8)
    }

    private suspend fun readStatus(): Int = transport.request("GET", "/info", null, emptyMap(), false) { status, input ->
        input.readBytes()
        status
    }

    private fun recorded(): RecordedRequest = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))

    private data class Identity(val socketFactory: SSLSocketFactory, val fingerprint: String)

    private companion object {
        val identity by lazy { identity("ip:127.0.0.1,dns:localhost") }
        val wrongHostIdentity by lazy { identity("ip:127.0.0.2") }

        fun identity(san: String): Identity {
            // 仅在测试时生成匿名临时证书，不提交任何私钥，也不增加 TLS 测试依赖。
            val parent = File("build/tmp").apply { mkdirs() }.toPath()
            val directory = Files.createTempDirectory(parent, "transport-tls-").toFile()
            val file = File(directory, "identity.p12")
            val password = UUID.randomUUID().toString().toCharArray()
            val keytool = File(System.getProperty("java.home"), "bin/keytool${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""}")
            try {
                val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "test", "-keyalg", "EC",
                    "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=$san", "-validity", "2",
                    "-storetype", "PKCS12", "-keystore", file.absolutePath, "-storepass", String(password), "-noprompt")
                    .redirectErrorStream(true).start()
                try {
                    check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Unable to generate test TLS identity" }
                } finally { if (process.isAlive) process.destroyForcibly() }
                val keys = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
                val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
                val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
                val certificate = keys.getCertificate("test") as X509Certificate
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
                return Identity(tls.socketFactory, fingerprint)
            } finally {
                password.fill('\u0000')
                file.delete()
                directory.delete()
            }
        }
    }
}
