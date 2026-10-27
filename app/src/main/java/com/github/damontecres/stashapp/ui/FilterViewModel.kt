package com.github.damontecres.stashapp.ui

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apollographql.apollo.api.Query
import com.github.damontecres.stashapp.api.fragment.StashData
import com.github.damontecres.stashapp.api.type.SortDirectionEnum
import com.github.damontecres.stashapp.data.DataType
import com.github.damontecres.stashapp.suppliers.DataSupplierFactory
import com.github.damontecres.stashapp.suppliers.FilterArgs
import com.github.damontecres.stashapp.suppliers.StashPagingSource
import com.github.damontecres.stashapp.util.AlphabetSearchUtils
import com.github.damontecres.stashapp.util.ComposePager
import com.github.damontecres.stashapp.util.GridPositionStore
import com.github.damontecres.stashapp.util.GridSortStore
import com.github.damontecres.stashapp.util.LoggingCoroutineExceptionHandler
import com.github.damontecres.stashapp.util.QueryEngine
import com.github.damontecres.stashapp.util.StashServer
import com.github.damontecres.stashapp.util.positionKey
import com.github.damontecres.stashapp.util.sortKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How [FilterViewModel.setFilter] should treat the remembered per-list sort
 */
enum class SortMemory {
    /** Neither read nor write the remembered sort */
    NONE,

    /** The list is being opened: re-apply the remembered sort if there is one */
    RESTORE,

    /** The user changed the filter/sort: remember its sort */
    SAVE,
}

class FilterViewModel : ViewModel() {
    private var server: StashServer? = null
    val pager = MutableLiveData<ComposePager<StashData>>()

    val currentFilter: FilterArgs? get() = pager.value?.filter
    val dataType: DataType? get() = currentFilter?.dataType

    private var positionKey: String? = null

    /**
     * The last remembered grid position for the current filter (see [GridPositionStore]), or 0.
     * Always reflects the latest saved value, so re-showing a grid restores where the user left off.
     */
    val savedPosition: Int
        get() {
            val key = positionKey ?: return 0
            val serverUrl = server?.url ?: return 0
            val size = pager.value?.size ?: return 0
            return GridPositionStore.peek(serverUrl, key).coerceIn(0, (size - 1).coerceAtLeast(0))
        }

    private var job: Job? = null

    /** The filter the in-flight [job] is building a pager for, if any */
    private var pendingFilter: FilterArgs? = null

    /**
     * Set the filter (and create a new pager) for the given server.
     *
     * @param sortMemory how to use the remembered sort (see [GridSortStore]); [SortMemory.RESTORE]
     * re-applies the remembered sort for [filterArgs] if there is one, [SortMemory.SAVE] remembers
     * [filterArgs]'s sort, and [SortMemory.NONE] does neither
     */
    fun setFilter(
        server: StashServer,
        filterArgs: FilterArgs,
        columns: Int,
        sortMemory: SortMemory = SortMemory.NONE,
    ) {
        when (sortMemory) {
            SortMemory.NONE -> {}

            SortMemory.SAVE -> {
                filterArgs.sortKey()?.let {
                    GridSortStore.save(server.url, it, filterArgs.sortAndDirection)
                }
            }

            SortMemory.RESTORE -> {
                restoreFilter(server, filterArgs, columns)
                return
            }
        }
        if (pager.value?.filter != filterArgs || server != this.server) {
            job?.cancel()
            startPager(server, filterArgs, columns)
        }
    }

    /**
     * Applies the remembered sort (if any) to [filterArgs] and then starts the pager, unless the
     * resulting filter is already the one being shown (or loaded) in which case nothing happens.
     */
    private fun restoreFilter(
        server: StashServer,
        filterArgs: FilterArgs,
        columns: Int,
    ) {
        val previous = job
        job =
            viewModelScope.launch(LoggingCoroutineExceptionHandler(server, viewModelScope)) {
                val remembered = filterArgs.sortKey()?.let { GridSortStore.load(server.url, it) }
                val candidate = if (remembered != null) filterArgs.with(remembered) else filterArgs
                val current =
                    if (previous?.isActive == true && pendingFilter != null) {
                        pendingFilter
                    } else {
                        pager.value?.filter
                    }
                if (server == this@FilterViewModel.server && current != null &&
                    current.withoutRandomSeed() == candidate.withoutRandomSeed()
                ) {
                    // Already showing this (e.g. a tab re-entering composition with stale state)
                    return@launch
                }
                previous?.cancel()
                publishPager(server, candidate.withResolvedRandom(), columns)
            }
    }

    private fun FilterArgs.withoutRandomSeed(): FilterArgs =
        if (sortAndDirection.isRandom) with(sortAndDirection.copy(randomSeed = -1)) else this

    /**
     * Launches a coroutine, tracked as [job], that creates a pager for the filter and publishes it.
     */
    private fun startPager(
        server: StashServer,
        filterArgs: FilterArgs,
        columns: Int,
    ) {
        job =
            viewModelScope.launch(LoggingCoroutineExceptionHandler(server, viewModelScope)) {
                publishPager(server, filterArgs, columns)
            }
    }

    private suspend fun publishPager(
        server: StashServer,
        filterArgs: FilterArgs,
        columns: Int,
    ) {
        Log.d("FilterPageViewModel", "filterArgs=$filterArgs, columns=$columns")
        this.server = server
        pendingFilter = filterArgs
        val dataSupplierFactory = DataSupplierFactory(server.version)
        val dataSupplier =
            dataSupplierFactory.create<Query.Data, StashData, Query.Data>(filterArgs)
        val pagingSource =
            StashPagingSource(QueryEngine(server), dataSupplier) { _, _, item -> item }
        val pager =
            ComposePager(filterArgs, pagingSource, viewModelScope, pageSize = columns * 10)
        pager.init()
        // Computed from the effective filter (with any restored sort)
        val key = filterArgs.positionKey()
        if (key != null) {
            // Warm the cache so savedPosition can be read synchronously
            GridPositionStore.load(server.url, key)
        }
        positionKey = key
        this.pager.value = pager
    }

    suspend fun findLetterPosition(letter: Char): Int {
        val server = this.server!!
        val filter = this.pager.value!!.filter

        val dataSupplierFactory = DataSupplierFactory(server.version)
        val letterPosition =
            AlphabetSearchUtils.findPosition(
                letter,
                filter,
                QueryEngine(server),
                dataSupplierFactory,
            )
        val jumpPosition =
            if (filter.sortAndDirection.direction == SortDirectionEnum.DESC) {
                // Reverse if sorting descending
                pager.value!!.size - letterPosition - 1
            } else {
                letterPosition
            }
        return jumpPosition
    }
}
