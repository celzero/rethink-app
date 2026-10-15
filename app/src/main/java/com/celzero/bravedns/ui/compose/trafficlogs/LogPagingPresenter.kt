/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.trafficlogs

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.paging.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback

/** Paging generations, original diff callbacks, snapshots and access hints remain in Paging. */
abstract class LogPagingPresenter<T : Any>(diff: DiffUtil.ItemCallback<T>) {
    private var revision by mutableIntStateOf(0)
    private val differ = AsyncPagingDataDiffer(diff, object : ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) { revision++ }
        override fun onRemoved(position: Int, count: Int) { revision++ }
        override fun onMoved(fromPosition: Int, toPosition: Int) { revision++ }
        override fun onChanged(position: Int, count: Int, payload: Any?) { revision++ }
    })
    val itemCount: Int get() { revision; return differ.itemCount }
    fun snapshot(): ItemSnapshotList<T> = differ.snapshot()
    fun submitData(lifecycle: Lifecycle, data: PagingData<T>) = differ.submitData(lifecycle, data)
    fun addLoadStateListener(listener: (CombinedLoadStates) -> Unit) = differ.addLoadStateListener(listener)
    fun removeLoadStateListener(listener: (CombinedLoadStates) -> Unit) = differ.removeLoadStateListener(listener)
    fun refresh() = differ.refresh()
    fun peek(index: Int): T? { revision; return if (index in 0 until itemCount) differ.peek(index) else null }
    open fun itemKey(value: T): Any = value.hashCode()
    fun keyAt(index: Int): Any = peek(index)?.let(::itemKey) ?: "log-placeholder-$index"
    @Composable fun RowAt(index: Int) {
        val generation = revision
        if (index in 0 until itemCount) {
            val value = differ.getItem(index)
            if (value == null) Spacer(Modifier.fillMaxWidth().height(72.dp)) else Row(value, generation)
        }
    }
    @Composable protected abstract fun Row(value: T, generation: Int)
}
