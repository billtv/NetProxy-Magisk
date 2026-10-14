package com.fanjv.netproxy.feature.apps.data

import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object AppIconCache {
    private const val MAX_CACHE_KB = 8 * 1024
    private val cacheLock = Any()
    var revision by mutableLongStateOf(0L)
        private set
    var generation by mutableLongStateOf(0L)
        private set
    private val lruCache = object : LruCache<String, ImageBitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            ((value.width.toLong() * value.height * 4 + 1023) / 1024).coerceAtLeast(1).toInt()
    }

    private fun cacheKey(userId: String, packageName: String, sizePx: Int) = "$userId:$packageName@$sizePx"
    fun cachedIcon(packageName: String, userId: String, sizePx: Int): ImageBitmap? =
        synchronized(cacheLock) { lruCache[cacheKey(userId, packageName, sizePx)] }

    suspend fun putIcons(userId: String, sizePx: Int, icons: Map<String, ByteArray>, expectedGeneration: Long) = withContext(Dispatchers.Default) {
        val decoded = icons.mapNotNull { (name, bytes) ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { name to it }
        }
        currentCoroutineContext().ensureActive()
        synchronized(cacheLock) {
            if (generation != expectedGeneration) return@synchronized
            decoded.forEach { (name, image) -> lruCache.put(cacheKey(userId, name, sizePx), image) }
            if (decoded.isNotEmpty()) revision++
        }
    }

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) clear()
    }

    fun clear() = synchronized(cacheLock) {
        generation++
        revision++
        lruCache.evictAll()
    }
}
