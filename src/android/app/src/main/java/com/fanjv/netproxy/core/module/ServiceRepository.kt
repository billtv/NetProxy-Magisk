package com.fanjv.netproxy.core.module

import com.fanjv.netproxy.core.api.ModuleApiClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.decodeFromJsonElement

/** 代理服务生命周期、模式与运行状态的数据入口。 */
internal class ServiceRepository(
    private val client: ModuleApiClient
) {
    suspend fun status(): ServiceStatusSnapshot =
        client.read("/service")

    fun observe() = client.observe(setOf("status", "traffic")).map { result -> result.mapCatching { events ->
        val status = client.json.decodeFromJsonElement<ServiceStatusSnapshot>(events.getValue("status").data)
        val traffic = events.getValue("traffic").data.jsonObject
        if (status.state != "ready" || status.coreStartedAtMillis <= 0 ||
            traffic["available"]?.jsonPrimitive?.booleanOrNull != true ||
            traffic["started_at_millis"]?.jsonPrimitive?.longOrNull != status.coreStartedAtMillis) status
        else {
            val core = traffic.getValue("status").jsonObject
            status.copy(memoryBytes = core.getValue("memory").jsonPrimitive.longOrNull ?: 0,
                connectionsIn = core.getValue("connections_in").jsonPrimitive.intOrNull ?: 0,
                connectionsOut = core.getValue("connections_out").jsonPrimitive.intOrNull ?: 0,
                uploadTotal = core.getValue("uplink_total").jsonPrimitive.longOrNull ?: 0,
                downloadTotal = core.getValue("downlink_total").jsonPrimitive.longOrNull ?: 0,
                processCpuTicks = traffic.getValue("process_cpu_ticks").jsonPrimitive.longOrNull ?: 0,
                systemCpuTicks = traffic.getValue("system_cpu_ticks").jsonPrimitive.longOrNull ?: 0,
                cpuCount = traffic.getValue("cpu_count").jsonPrimitive.intOrNull ?: 1)
        }
    } }

    suspend fun action(action: String) {
        require(action in setOf("start", "stop", "restart", "reload"))
        client.post("/service/$action")
    }

    suspend fun setMode(mode: String) {
        require(mode.isNotBlank())
        client.put("/mode", buildJsonObject { put("mode", mode) })
    }
}
