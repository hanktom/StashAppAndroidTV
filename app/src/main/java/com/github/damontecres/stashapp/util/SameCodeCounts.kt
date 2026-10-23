package com.github.damontecres.stashapp.util

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.apollographql.apollo.api.Optional
import com.github.damontecres.stashapp.api.type.CriterionModifier
import com.github.damontecres.stashapp.api.type.FindFilterType
import com.github.damontecres.stashapp.api.type.SceneFilterType
import com.github.damontecres.stashapp.api.type.StringCriterionInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 全程式共用的「同番號影片數量」快取。卡片只呼叫 [request]，請求會被合併並延遲，
 * 之後一次查詢一批番號，避免每張卡片各打一次網路請求。
 */
object SameCodeCounts {
    private const val TAG = "SameCodeCounts"
    private const val DEBOUNCE_MS = 250L
    private const val CHUNK_SIZE = 50

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 正規化番號 -> 數量；Compose 可直接觀察 */
    private val counts = mutableStateMapOf<String, Int>()

    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private var job: Job? = null
    private var serverKey: String? = null

    fun normalize(code: String): String = code.trim().uppercase()

    /** 已快取的數量，未快取回傳 null（在 Composable 內呼叫會被追蹤） */
    fun get(code: String): Int? = counts[normalize(code)]

    fun request(code: String) {
        val key = normalize(code)
        if (key.isEmpty()) return
        resetIfServerChanged()
        if (!requested.add(key)) return
        pending.add(key)
        synchronized(this) {
            if (job?.isActive != true) {
                job =
                    scope.launch {
                        delay(DEBOUNCE_MS)
                        flush()
                    }
            }
        }
    }

    private fun resetIfServerChanged() {
        val key = StashServer.getCurrentStashServer()?.url
        synchronized(this) {
            if (serverKey != key) {
                serverKey = key
                requested.clear()
                pending.clear()
                counts.clear()
            }
        }
    }

    private suspend fun flush() {
        val batch = pending.toList()
        pending.removeAll(batch.toSet())
        val server = StashServer.getCurrentStashServer() ?: return
        batch.chunked(CHUNK_SIZE).forEach { chunk ->
            try {
                val regex = "(?i)^(" + chunk.joinToString("|") { escapeRegex(it) } + ")$"
                val filter =
                    SceneFilterType(
                        code =
                            Optional.present(
                                StringCriterionInput(
                                    value = regex,
                                    modifier = CriterionModifier.MATCHES_REGEX,
                                ),
                            ),
                    )
                val results =
                    QueryEngine(server).findSceneCodes(
                        FindFilterType(per_page = Optional.present(-1)),
                        filter,
                    )
                val grouped =
                    results
                        .filter { (id, code) -> code != null && !DeletedScenes.isDeleted(id) }
                        .groupingBy { normalize(it.second!!) }
                        .eachCount()
                chunk.forEach { counts[it] = grouped[it] ?: 0 }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to query same-code counts", ex)
                // 失敗時允許之後重試
                requested.removeAll(chunk.toSet())
            }
        }
        synchronized(this) {
            job = null
            if (pending.isNotEmpty()) {
                job =
                    scope.launch {
                        delay(DEBOUNCE_MS)
                        flush()
                    }
            }
        }
    }

    private fun escapeRegex(s: String): String {
        val meta = "\\.+*?()|[]{}^$-"
        val sb = StringBuilder()
        for (c in s) {
            if (c in meta) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }
}
