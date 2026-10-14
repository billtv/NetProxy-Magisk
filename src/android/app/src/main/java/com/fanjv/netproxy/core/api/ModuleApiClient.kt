package com.fanjv.netproxy.core.api

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File

internal data class ModuleApiResponse(val code: String, val message: String, val data: JsonElement)
internal data class ModuleHttpResponse(val status: Int, val content: String)
internal data class ModuleEvent(val name: String, val content: String)

internal class ModuleApiException(
    val resultCode: String,
    override val message: String,
    val data: JsonElement = JsonObject(emptyMap()),
) : IllegalStateException(message) {
    val persisted: Boolean get() = ((data as? JsonObject)?.get("persisted") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
}

internal interface ModuleApiTransport {
    suspend fun reconnect(address: String)
    suspend fun request(method: String, path: String, body: JsonObject?, query: Map<String, String>, mutation: Boolean): ModuleHttpResponse
    suspend fun download(path: String, query: Map<String, String>, destination: File)
    suspend fun watch(topics: Set<String>, consume: suspend (ModuleEvent) -> Unit)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ModuleApiClient(private val transport: ModuleApiTransport) {
    constructor(context: Context) : this(LocalHttpTransport(context.applicationContext))
    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val codec = ModuleApiCodec(json)
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val interests = HashMap<String, Int>()
    private val topics = MutableStateFlow<Set<String>>(emptySet())
    private val snapshots = topics.flatMapLatest { active ->
        if (active.isEmpty()) emptyFlow() else channelFlow {
            var wait = 1000L
            while (isActive) {
                send(ModuleEvent("reset", ""))
                try {
                    transport.watch(active) { send(it); wait = 1000L }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { }
                send(ModuleEvent("", ""))
                delay(wait)
                wait = (wait * 2).coerceAtMost(15_000L)
            }
        }
    }.scan(emptyMap<String, Result<ModuleApiResponse>>()) { previous, event ->
        if (event.name == "reset") emptyMap()
        else if (event.name.isEmpty()) {
            previous.mapValues { Result.failure<ModuleApiResponse>(ModuleApiException("api.disconnected", "模块事件连接已断开")) }
        } else previous + (event.name to runCatching { codec.decode(200, event.content) })
    }.stateIn(eventScope, SharingStarted.WhileSubscribed(0, 0), emptyMap())

    fun observe(names: Set<String>): Flow<Result<Map<String, ModuleApiResponse>>> = flow {
        require(names.isNotEmpty() && names.all { it in setOf("status", "traffic", "groups", "subscriptions") })
        updateInterests(names, 1)
        try {
            snapshots.collect { snapshot ->
                val failure = names.mapNotNull { snapshot[it]?.exceptionOrNull() }.firstOrNull()
                if (failure != null) emit(Result.failure(failure))
                else if (names.all { snapshot.containsKey(it) }) emit(Result.success(names.associateWith { snapshot.getValue(it).getOrThrow() }))
            }
        } finally { updateInterests(names, -1) }
    }.distinctUntilChanged()

    private fun updateInterests(names: Set<String>, change: Int) = synchronized(interests) {
        names.map { if (it == "traffic") "status" else it }.forEach { topic ->
            require(topic in setOf("status", "groups", "subscriptions"))
            val count = (interests[topic] ?: 0) + change
            if (count == 0) interests.remove(topic) else interests[topic] = count
        }
        topics.value = interests.keys.toSet()
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()) = request("GET", path, query = query)
    suspend fun reconnect(address: String) {
        transport.reconnect(address)
        get("/info")
    }
    suspend fun post(path: String, body: JsonObject = JsonObject(emptyMap()), mutation: Boolean = true,
        query: Map<String, String> = emptyMap()) = request("POST", path, body, query, mutation)
    suspend fun put(path: String, body: JsonObject, query: Map<String, String> = emptyMap()) = request("PUT", path, body, query, true)
    suspend fun delete(path: String, query: Map<String, String> = emptyMap()) = request("DELETE", path, query = query, mutation = true)
    suspend inline fun <reified T> read(path: String, query: Map<String, String> = emptyMap()): T = json.decodeFromJsonElement(get(path, query).data)
    suspend fun download(path: String, query: Map<String, String>, destination: File) = transport.download(path, query, destination)

    private suspend fun request(method: String, path: String, body: JsonObject? = null,
        query: Map<String, String> = emptyMap(), mutation: Boolean = false): ModuleApiResponse {
        currentCoroutineContext().ensureActive()
        return withContext(if (mutation) NonCancellable + Dispatchers.IO else Dispatchers.IO) {
            val result = transport.request(method, path, body, query, mutation)
            codec.decode(result.status, result.content)
        }
    }
}

internal class ModuleApiCodec(private val json: Json) {
    fun decode(status: Int, content: String): ModuleApiResponse {
        val root = runCatching { json.parseToJsonElement(content).jsonObject }.getOrElse {
            throw ModuleApiException("api.invalid_response", "模块返回的数据格式无效")
        }
        val schema = root["schema"] as? JsonPrimitive
        if (schema == null || schema.isString || schema.intOrNull != 1) throw ModuleApiException("api.unsupported_schema", "模块管理接口版本不受支持")
        val ok = root["ok"] as? JsonPrimitive
        val code = (root["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val message = (root["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (ok == null || ok.isString || ok.booleanOrNull == null || code.isNullOrEmpty() || message == null) {
            throw ModuleApiException("api.invalid_response", "模块返回的数据格式无效")
        }
        val data = root["data"] ?: JsonObject(emptyMap())
        if (status !in 200..299 || ok.booleanOrNull != true) throw ModuleApiException(code, message.ifEmpty { "模块操作失败" }, data)
        return ModuleApiResponse(code, message, data)
    }

}
