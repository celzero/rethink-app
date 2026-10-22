/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.dns

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.BlockFreeDnsItem
import com.celzero.bravedns.data.BlockFreeDnsType
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import androidx.compose.ui.text.style.TextAlign
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

/** Mirrors the XML singleSelection + selectionRequired ChipGroup, in XML child order. */
internal class BlockFreeChipGroup {
    val ids = listOf(R.id.bfd_chip_all, R.id.bfd_chip_rethink, R.id.bfd_chip_doh, R.id.bfd_chip_dot,
        R.id.bfd_chip_dnscrypt, R.id.bfd_chip_odoh, R.id.bfd_chip_dns_proxy)
    var checkedId by mutableIntStateOf(R.id.bfd_chip_all)
        private set
    private var listener: ((BlockFreeChipGroup, List<Int>) -> Unit)? = null
    fun setOnCheckedStateChangeListener(value: (BlockFreeChipGroup, List<Int>) -> Unit) { listener = value }
    fun check(id: Int) {
        if (id !in ids || id == checkedId) return
        checkedId = id
        listener?.invoke(this, listOf(id))
    }
    fun restore(id: Int) { check(id) }
}
internal class BlockFreeDnsPresentation(context: Context) {
    val bfdSelectedName = WgControlState(context)
    val bfdSelectedCard = WgControlState(context).apply { visibility = View.GONE }
    val bfdEmptyTxt = WgControlState(context).apply { visibility = View.GONE }
    val bfdFilterChipGroup = BlockFreeChipGroup()
}
internal class BlockFreeDnsListState(selectedKey: String, val onItemSelected: (BlockFreeDnsItem) -> Unit) {
    var items by mutableStateOf<List<BlockFreeDnsItem>>(emptyList())
        private set
    var selectedKey by mutableStateOf(selectedKey)
        private set
    fun submitList(value: List<BlockFreeDnsItem>) { items = value }
    fun updateSelectedKey(value: String) { selectedKey = value }
}

@Composable internal fun BlockFreeDnsContent(b: BlockFreeDnsPresentation, list: BlockFreeDnsListState) {
    val context = LocalContext.current
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Text("Block Free DNS", Modifier.padding(start = 17.dp, top = 15.dp, end = 20.dp), color = MaterialTheme.colorScheme.primary, style = utilityTextStyle(appearance = com.google.android.material.R.style.TextAppearance_Material3_TitleSmall_Emphasized))
            Text("Select a DNS that passes all queries through without blocking. This is used as a fallback when your active DNS is unavailable.", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp)
            if (b.bfdSelectedCard.visibility == View.VISIBLE) OriginalCardSurface(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), cornerRadius = 12.dp, elevation = 0.dp, containerColor = themeColor(R.attr.chipBgColorPositive)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Selected:", color = themeColor(R.attr.primaryTextColor), fontSize = 12.sp)
                    Text(b.bfdSelectedName.text.toString(), Modifier.padding(start = 6.dp), color = themeColor(R.attr.primaryTextColor), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val labels = listOf("All", "Rethink", "DoH", "DoT", "DNSCrypt", "ODoH", "DNS Proxy")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                b.bfdFilterChipGroup.ids.forEachIndexed { index, id ->
                    OriginalChip("activity_block_free_dns:" + context.resources.getResourceEntryName(id), b.bfdFilterChipGroup.checkedId == id, { b.bfdFilterChipGroup.check(id) }, { Text(labels[index]) }, Modifier.testTag("blockfree-filter-$id"))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp), thickness = .5.dp, color = themeColor(R.attr.divider))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list.items, key = { it.key }) { item ->
                    val checked = item.key == list.selectedKey
                    OriginalCardSurface(Modifier.fillMaxWidth(), cornerRadius = 12.dp, elevation = 0.dp, border = BorderStroke(.5.dp, themeColor(R.attr.border))) {
                        Row(Modifier.fillMaxWidth().testTag("blockfree-item-${item.key}").semantics { selected = checked }.clickable(role = Role.RadioButton) { list.onItemSelected(item) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.name, Modifier.weight(1f, false), color = themeColor(R.attr.primaryTextColor), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    OriginalLabel("list_item_block_free_dns:bfd_item_type_chip", Modifier.padding(start = 10.dp)) { Text(item.type.label) }
                                }
                                Text(if (item.type == BlockFreeDnsType.SYSTEM) "Underlying network's DNS" else item.url, Modifier.padding(top = 10.dp), color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (checked) Icon(painterResource(R.drawable.ic_tick), null, Modifier.size(32.dp).padding(4.dp)) else Spacer(Modifier.size(32.dp))
                        }
                    }
                }
            }
            if (b.bfdEmptyTxt.visibility == View.VISIBLE) Text("No DNS servers found", Modifier.fillMaxWidth().padding(32.dp), color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}
