/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.recyclerview.widget.DiffUtil
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.ui.compose.utilities.BubbleAllowedRow
import com.celzero.bravedns.ui.compose.wireguard.WgPagingPresenter

class BubbleAllowedAppsAdapter(private val onRemoveClick: (AllowedAppInfo) -> Unit) : WgPagingPresenter<AllowedAppInfo>(object : DiffUtil.ItemCallback<AllowedAppInfo>() {
    override fun areItemsTheSame(oldItem: AllowedAppInfo, newItem: AllowedAppInfo) = oldItem.uid == newItem.uid
    override fun areContentsTheSame(oldItem: AllowedAppInfo, newItem: AllowedAppInfo) = oldItem == newItem
}) {
    @Composable fun Row(item: AllowedAppInfo) { BubbleAllowedRow(item, onRemoveClick) }
    @Composable override fun Content(modifier: Modifier) { LazyColumn(modifier) { items(itemCount) { getItem(it)?.let { item -> Row(item) } } } }
}
