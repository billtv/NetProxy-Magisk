package com.fanjv.netproxy.feature.apps.data

import com.fanjv.netproxy.core.api.ModuleApiClient
import com.fanjv.netproxy.feature.apps.model.UserInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable
internal data class AndroidApp(
    @SerialName("package_name") val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("last_update_time") val lastUpdateTime: Long,
)

@Serializable
private data class AndroidUser(val id: Int, val name: String)
@Serializable
private data class AndroidApps(val user_id: Int, val revision: String, val apps: List<AndroidApp>)

internal class AppPackageRepository(private val client: ModuleApiClient) {
    private val mutex = Mutex()
    private var users: List<UserInfo>? = null
    private val applications = HashMap<String, AndroidApps>()
    private var generation = 0L
    private val queries = HashMap<String, Mutex>()

    suspend fun getUsers(): List<UserInfo> {
        val (version, query) = mutex.withLock {
            users?.let { return it }
            generation to queries.getOrPut("users") { Mutex() }
        }
        return query.withLock {
            mutex.withLock { if (generation == version) users?.let { return@withLock it } else null }?.let { return@withLock it }
            val result = client.read<List<AndroidUser>>("/android/users").map { UserInfo(it.id.toString(), it.name) }
            mutex.withLock { if (generation == version) users = result }
            result
        }
    }

    suspend fun getApplications(user: String = "0"): List<AndroidApp> {
        val (version, query) = mutex.withLock {
            applications[user]?.let { return it.apps }
            generation to queries.getOrPut("apps:$user") { Mutex() }
        }
        return query.withLock {
            mutex.withLock { if (generation == version) applications[user] else null }?.let { return@withLock it.apps }
            val result = client.read<AndroidApps>("/android/apps", mapOf("user_id" to user))
            mutex.withLock { if (generation == version) applications[user] = result }
            result.apps
        }
    }

    suspend fun icons(userId: String, packages: List<String>, size: Int): Map<String, ByteArray> {
        val result = client.post("/android/apps/icons", buildJsonObject {
            put("user_id", userId.toInt()); put("size", size.coerceIn(16, 192))
            put("packages", JsonArray(packages.map(::JsonPrimitive)))
        }, mutation = false).data.jsonArray
        return result.mapNotNull { entry ->
            val icon = entry.jsonObject
            val png = icon["png"]?.jsonPrimitive?.content ?: return@mapNotNull null
            icon.getValue("package_name").jsonPrimitive.content to android.util.Base64.decode(png, android.util.Base64.DEFAULT)
        }.toMap()
    }

    suspend fun invalidatePackageListingCaches() = mutex.withLock {
        users = null
        applications.clear()
        queries.clear()
        generation++
    }
}
