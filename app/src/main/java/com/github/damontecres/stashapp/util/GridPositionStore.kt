package com.github.damontecres.stashapp.util

import android.util.Log
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.data.room.GridPosition
import com.github.damontecres.stashapp.suppliers.FilterArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "GridPositionStore"

/**
 * A stable key identifying this filter's identity for the purposes of remembering a grid scroll
 * position, or null if this filter's results have no stable order (e.g. random sorts) and so no
 * position should be remembered.
 *
 * The display [FilterArgs.name] is excluded since it doesn't affect which items are shown or
 * their order.
 */
fun FilterArgs.positionKey(): String? {
    if (sortAndDirection.isRandom) {
        return null
    }
    return try {
        val json = StashJson.encodeToString(FilterArgs.serializer(), this.copy(name = null))
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        "${dataType.name}:$hex"
    } catch (ex: Exception) {
        Log.w(TAG, "Failed to compute position key", ex)
        null
    }
}

/**
 * Caches and persists the last-scrolled-to position for a grid, keyed by server URL and a
 * [FilterArgs.positionKey]. Reads are served from an in-memory cache backed by [Room]; writes are
 * debounced so rapid scrolling doesn't hammer the database.
 *
 * Never throws: any persistence failure is logged and swallowed so a bad read/write never crashes
 * the app or blocks scrolling.
 */
object GridPositionStore {
    private const val PRUNE_INTERVAL = 200
    private const val PRUNE_KEEP = 2000
    private const val SAVE_DEBOUNCE_MS = 750L

    private val cache = ConcurrentHashMap<String, Int>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveCounter = AtomicInteger(0)

    private fun dao() = StashApplication.getDatabase().gridPositionsDao()

    private fun cacheKey(
        serverUrl: String,
        filterKey: String,
    ) = "$serverUrl|$filterKey"

    /**
     * The cached position for the given server & filter without touching the database.
     * Returns 0 if nothing has been loaded or saved yet; call [load] first to warm the cache.
     */
    fun peek(
        serverUrl: String,
        filterKey: String,
    ): Int = cache[cacheKey(serverUrl, filterKey)] ?: 0

    /**
     * Load the remembered position for the given server & filter, checking the in-memory cache
     * first before falling back to the database. Returns 0 if nothing is remembered or on any
     * failure.
     */
    suspend fun load(
        serverUrl: String,
        filterKey: String,
    ): Int {
        val key = cacheKey(serverUrl, filterKey)
        cache[key]?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                val position = dao().get(serverUrl, filterKey)?.position ?: 0
                cache[key] = position
                position
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to load grid position for $key", ex)
            0
        }
    }

    /**
     * Record the current position for the given server & filter. Updates the in-memory cache
     * immediately and persists asynchronously after a short debounce so rapid scrolling doesn't
     * trigger a database write per frame.
     */
    fun save(
        serverUrl: String,
        filterKey: String,
        position: Int,
    ) {
        if (position < 0) {
            return
        }
        val key = cacheKey(serverUrl, filterKey)
        val previous = cache.put(key, position)
        if (previous == position) {
            return
        }
        jobs[key]?.cancel()
        val job =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                try {
                    dao().insert(GridPosition(serverUrl, filterKey, position, System.currentTimeMillis()))
                    if (saveCounter.incrementAndGet() % PRUNE_INTERVAL == 0) {
                        dao().prune(PRUNE_KEEP)
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to save grid position for $key", ex)
                }
            }
        jobs[key] = job
    }

    /** Clears all remembered positions, both persisted and cached. */
    suspend fun clear() {
        try {
            withContext(Dispatchers.IO) {
                dao().deleteAll()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to clear grid positions", ex)
        }
        cache.clear()
    }
}
