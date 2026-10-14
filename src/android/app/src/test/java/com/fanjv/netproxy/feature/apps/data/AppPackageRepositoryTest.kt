package com.fanjv.netproxy.feature.apps.data

import com.fanjv.netproxy.core.api.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AppPackageRepositoryTest {
    private fun listing(name: String, user: Int = 0, label: String = name) = apiSuccess(
        """{"user_id":$user,"revision":"apps","apps":[{"package_name":"$name","label":"$label","uid":${user * 100000 + 10001},"system":false,"version_code":1,"last_update_time":1}]}"""
    )

    @Test fun invalidationReloadsUsersAndPackageListings() = runBlocking {
        val calls = AtomicInteger()
        val repository = AppPackageRepository(ModuleApiClient(TestApiTransport { request ->
            calls.incrementAndGet()
            if (request.path == "/android/users") apiSuccess("""[{"id":0,"name":"Owner"}]""") else listing("example.app")
        }))
        repeat(2) { repository.getUsers(); repository.getApplications().map { it.packageName } }
        assertEquals(2, calls.get())
        repository.invalidatePackageListingCaches()
        repository.getUsers(); repository.getApplications().map { it.packageName }
        assertEquals(4, calls.get())
    }

    @Test fun oldListingCannotRefillCacheAfterInvalidation() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = AppPackageRepository(ModuleApiClient(TestApiTransport {
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit); release.await(); listing("old.example")
            } else listing("new.example")
        }))
        val old = async { repository.getApplications().map { it.packageName } }
        withTimeout(5000) { entered.await() }
        repository.invalidatePackageListingCaches()
        assertEquals(listOf("new.example"), repository.getApplications().map { it.packageName })
        release.complete(Unit)
        assertEquals(listOf("old.example"), old.await())
        assertEquals(listOf("new.example"), repository.getApplications().map { it.packageName })
        assertEquals(2, calls.get())
    }

    @Test fun samePackageUsesEachUsersLabel() = runBlocking {
        val repository = AppPackageRepository(ModuleApiClient(TestApiTransport { request ->
            val user = request.query.getValue("user_id").toInt()
            listing("example.app", user, if (user == 0) "Owner app" else "Work app")
        }))
        assertEquals("Owner app", repository.getApplications("0").single().label)
        assertEquals("Work app", repository.getApplications("10").single().label)
    }

    @Test fun failureDoesNotInventOwnerOrEmptyApplications() = runBlocking {
        val repository = AppPackageRepository(ModuleApiClient(TestApiTransport { throw ModuleApiException("android.unavailable", "fixture") }))
        for (operation in listOf<suspend () -> Unit>({ repository.getUsers() }, { repository.getApplications().map { it.packageName } })) {
            try { operation(); fail("expected query failure") } catch (error: ModuleApiException) {
                assertEquals("android.unavailable", error.resultCode)
            }
        }
    }
}
