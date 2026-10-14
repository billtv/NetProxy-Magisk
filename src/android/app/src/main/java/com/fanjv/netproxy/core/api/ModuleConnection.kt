package com.fanjv.netproxy.core.api

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.URI

@Serializable
internal data class ModuleConnection(
    val address: String,
    val secret: String,
    val certificate_sha256: String,
) {
    fun validate(): ModuleConnection {
        validateModuleAddress(address)
        require(secret.matches(Regex("[0-9a-fA-F]{64}")) && certificate_sha256.matches(Regex("[0-9a-f]{64}")))
        return this
    }
}

internal fun validateModuleAddress(address: String) {
    val uri = URI(address)
    require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    require(uri.path.isNullOrEmpty() && uri.port in 1..65535)
    val host = requireNotNull(uri.host).removeSurrounding("[", "]")
    require((host.contains(':') && host.all { it.isDigit() || it in ":.abcdefABCDEF" }
        || host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) && InetAddress.getByName(host).isLoopbackAddress)
}

internal class ConnectionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "module-connection.json"))
    private companion object { val lock = Any() }

    fun read(): ModuleConnection? = synchronized(lock) {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return@synchronized null }
        input.use { Json.decodeFromString<ModuleConnection>(it.bufferedReader().readText()).validate() }
    }

    fun write(content: String) = synchronized(lock) {
        require(content.length <= 4096)
        Json.decodeFromString<ModuleConnection>(content).validate()
        val output = file.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}
