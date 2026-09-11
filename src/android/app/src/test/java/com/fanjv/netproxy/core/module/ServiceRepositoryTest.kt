package com.fanjv.netproxy.core.module

import com.fanjv.netproxy.core.command.NetProxyCtlClient
import com.fanjv.netproxy.core.command.NetProxyCtlOutput
import com.fanjv.netproxy.core.command.NetProxyCtlTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServiceRepositoryTest {
    @Test fun cancelledRefreshDoesNotPublishLateSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val complete = CountDownLatch(1)
        val client = NetProxyCtlClient(transport = NetProxyCtlTransport { args, _ ->
            assertEquals(listOf("service", "status"), args)
            started.complete(Unit)
            check(complete.await(5, TimeUnit.SECONDS))
            NetProxyCtlOutput(true, listOf("""{"schema":1,"ok":true,"code":"service.status","message":"服务状态","data":{"state":"ready"}}"""), emptyList())
        })
        val repository = ServiceRepository(client)
        var published = false
        var cancelled = false
        val refresh = launch {
            try {
                repository.status()
                published = true
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        try {
            started.await()
            refresh.cancel()
        } finally {
            complete.countDown()
            refresh.cancelAndJoin()
        }
        assertTrue(cancelled)
        assertFalse(published)
    }
}
