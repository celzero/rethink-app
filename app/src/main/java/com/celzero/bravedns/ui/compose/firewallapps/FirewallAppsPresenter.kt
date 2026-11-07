package com.celzero.bravedns.ui.compose.firewallapps

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.paging.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.ui.compose.wireguard.WgListContent

/** Non-View presenter: exact Paging diff callbacks and access hints, lifecycle-bound collection. */
abstract class FirewallAppsPresenter(diff: DiffUtil.ItemCallback<AppInfo> = ConnectionDiff) : WgListContent {
    private var revision by mutableIntStateOf(0)
    private val differ = AsyncPagingDataDiffer(diff, object : ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) { revision++ }
        override fun onRemoved(position: Int, count: Int) { revision++ }
        override fun onMoved(fromPosition: Int, toPosition: Int) { revision++ }
        override fun onChanged(position: Int, count: Int, payload: Any?) { revision++ }
    })
    val itemCount: Int get() { revision; return differ.itemCount }
    fun snapshot(): ItemSnapshotList<AppInfo> = differ.snapshot()
    fun submitData(lifecycle: Lifecycle, data: PagingData<AppInfo>) = differ.submitData(lifecycle, data)
    fun addLoadStateListener(listener: (CombinedLoadStates) -> Unit) = differ.addLoadStateListener(listener)
    fun removeLoadStateListener(listener: (CombinedLoadStates) -> Unit) = differ.removeLoadStateListener(listener)
    fun addSnapshotListener(listener: () -> Unit) = differ.addOnPagesUpdatedListener(listener)
    fun removeSnapshotListener(listener: () -> Unit) = differ.removeOnPagesUpdatedListener(listener)
    fun refresh() = differ.refresh()
    fun getItem(index: Int): AppInfo? = differ.getItem(index)
    fun peek(index: Int): AppInfo? { revision; return differ.peek(index) }
    @Composable fun RowAt(index: Int) {
        val version = revision
        if (index in 0 until itemCount) {
            val item = differ.getItem(index)
            if (item == null) Spacer(Modifier.height(50.dp)) else Row(index, item, version)
        }
    }
    @Composable override fun Content(modifier: Modifier) {
        Column(modifier) { repeat(itemCount) { RowAt(it) } }
    }
    @Composable protected abstract fun Row(index: Int, connection: AppInfo, revision: Int)
    companion object {
        private val ConnectionDiff = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(old: AppInfo, new: AppInfo) = old.uid == new.uid && old.packageName == new.packageName
            override fun areContentsTheSame(old: AppInfo, new: AppInfo) = old == new
        }
    }
}
