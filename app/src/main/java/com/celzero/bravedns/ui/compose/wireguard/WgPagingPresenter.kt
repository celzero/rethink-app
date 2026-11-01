/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback

interface WgListContent {
    @Composable fun Content(modifier: Modifier = Modifier)
}

/** Keeps the baseline differ, ordering, placeholders and getItem load hints without RecyclerView. */
abstract class WgPagingPresenter<T : Any>(diff: DiffUtil.ItemCallback<T>) : WgListContent {
    var revision by mutableIntStateOf(0)
        private set
    private val differ = AsyncPagingDataDiffer(diff, object : ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) { revision++ }
        override fun onRemoved(position: Int, count: Int) { revision++ }
        override fun onMoved(fromPosition: Int, toPosition: Int) { revision++ }
        override fun onChanged(position: Int, count: Int, payload: Any?) { revision++ }
    })
    val itemCount: Int get() { revision; return differ.itemCount }
    fun getItem(position: Int): T? { revision; return differ.getItem(position) }
    fun peek(position: Int): T? { revision; return differ.peek(position) }
    suspend fun submitData(data: PagingData<T>) = differ.submitData(data)
    fun submitData(lifecycle: Lifecycle, data: PagingData<T>) = differ.submitData(lifecycle, data)
    fun addLoadStateListener(listener: (androidx.paging.CombinedLoadStates) -> Unit) = differ.addLoadStateListener(listener)
    fun refresh() = differ.refresh()
    fun notifyDataSetChanged() { revision++ }
}
