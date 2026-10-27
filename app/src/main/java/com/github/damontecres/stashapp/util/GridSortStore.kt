package com.github.damontecres.stashapp.util

import android.util.Log
import com.github.damontecres.stashapp.StashApplication
import com.github.damontecres.stashapp.data.SortAndDirection
import com.github.damontecres.stashapp.data.room.GridSort
import com.github.damontecres.stashapp.suppliers.FilterArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "GridSortStore"

/**
 * A stable key identifying this filter's identity for the purposes of remembering the chosen sort,
 * or null if the key could not be computed.
 *
 * Unlike [positionKey], the sort is excluded (as is the display [FilterArgs.name]) so that every
 * sort of the same list shares one key.
 */
fun FilterArgs.sortKey(): String? =
    try {
        val strippedFind =
            findFilter
                ?.copy(sortAndDirection = null)
                // A find filter with nothing left in it is equivalent to none at all
                ?.takeIf { it.q != null }
        val json =
            StashJson.encodeToString(
                FilterArgs.serializer(),
                this.copy(name = null, findFilter = strippedFind),
            )
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        "${dataType.name}:$hex"
    } catch (ex: Exception) {
        Log.w(TAG, "Failed to compute sort key", ex)
        null
    }

/**
 * Caches and persists the last-chosen sort for a list, keyed by server URL and a
 * [FilterArgs.sortKey]. Reads are served from an in-memory cache backed by [Room]; writes are
 * persisted immediately since sort changes are infrequent.
 *
 * Random sorts are stored with an unresolved seed so a fresh seed can be chosen when re-applied.
 *
 * Never throws: any persistence failure is logged and swallowed.
 */
object GridSortStore {
    private const val PRUNE_INTERVAL = 50
    private const val PRUNE_KEEP = 1000

    private val cache = ConcurrentHashMap<String, SortAndDirection>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveCounter = AtomicInteger(0)

    private fun dao() = StashApplication.getDatabase().gridSortsDao()

    private fun cacheKey(
        serverUrl: String,
        filterKey: String,
    ) = "$serverUrl|$filterKey"

    /**
     * Load the remembered sort for the given server & filter, checking the in-memory cache first
     * before falling back to the database. Returns null if nothing is remembered or on any failure.
     * A returned random sort has an unresolved seed; see [SortAndDirection.withResolvedRandom].
     */
    suspend fun load(
        serverUrl: String,
        filterKey: String,
    ): SortAndDirection? {
        val key = cacheKey(serverUrl, filterKey)
        cache[key]?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                val sort =
                    dao().get(serverUrl, filterKey)?.let {
                        StashJson.decodeFromString(SortAndDirection.serializer(), it.sort)
                    }
                if (sort != null) {
                    cache[key] = sort
                }
                sort
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to load grid sort for $key", ex)
            null
        }
    }

    /**
     * Record the sort for the given server & filter. Updates the in-memory cache immediately and
     * persists asynchronously.
     */
    fun save(
        serverUrl: String,
        filterKey: String,
        sort: SortAndDirection,
    ) {
        val toSave = if (sort.isRandom) sort.copy(randomSeed = -1) else sort
        val key = cacheKey(serverUrl, filterKey)
        val previous = cache.put(key, toSave)
        if (previous == toSave) {
            return
        }
        scope.launch {
            try {
                val json = StashJson.encodeToString(SortAndDirection.serializer(), toSave)
                dao().insert(GridSort(serverUrl, filterKey, json, System.currentTimeMillis()))
                if (saveCounter.incrementAndGet() % PRUNE_INTERVAL == 0) {
                    dao().prune(PRUNE_KEEP)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to save grid sort for $key", ex)
            }
        }
    }

    /** Clears all remembered sorts, both persisted and cached. */
    suspend fun clear() {
        try {
            withContext(Dispatchers.IO) {
                dao().deleteAll()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to clear grid sorts", ex)
        }
        cache.clear()
    }
}
