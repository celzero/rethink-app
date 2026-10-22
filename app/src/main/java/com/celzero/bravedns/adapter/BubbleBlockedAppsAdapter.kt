/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.recyclerview.widget.DiffUtil
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.ui.compose.utilities.BubbleBlockedRow
import com.celzero.bravedns.ui.compose.wireguard.WgPagingPresenter

class BubbleBlockedAppsAdapter(private val onAllowClick: (BlockedAppInfo) -> Unit) : WgPagingPresenter<BlockedAppInfo>(object : DiffUtil.ItemCallback<BlockedAppInfo>() {
    override fun areItemsTheSame(oldItem: BlockedAppInfo, newItem: BlockedAppInfo) = oldItem.uid == newItem.uid
    override fun areContentsTheSame(oldItem: BlockedAppInfo, newItem: BlockedAppInfo) = oldItem == newItem
}) {
    @Composable fun Row(item: BlockedAppInfo) { BubbleBlockedRow(item, onAllowClick) }
    @Composable override fun Content(modifier: Modifier) { LazyColumn(modifier) { items(itemCount) { getItem(it)?.let { item -> Row(item) } } } }
}
