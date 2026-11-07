package com.celzero.bravedns.ui.compose.blocklists

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

class BlocklistFilterChip(val label: String, initial: Boolean, val change: (Boolean) -> Unit) {
    var checked by mutableStateOf(initial)
}
class BlocklistFilterState {
    val chips = mutableStateListOf<BlocklistFilterChip>()
    var apply by mutableStateOf<() -> Unit>({})
    var clear by mutableStateOf<() -> Unit>({})
}

/** Subgroup mutation stays immediate, including the baseline Back/dismiss draft quirk. */
@Composable fun BlocklistFilterScreen(state: BlocklistFilterState) {
    Column(Modifier.fillMaxWidth().padding(10.dp).verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 20.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
        Text(stringResource(R.string.bsrf_sub_group_heading), Modifier.padding(horizontal = 15.dp, vertical = 10.dp).semantics { heading() }, color = themeColor(R.attr.secondaryTextColor), fontSize = 14.sp)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            state.chips.forEach { chip ->
                OriginalChip("item_chip_filter:(anonymous)", chip.checked, onClick = { chip.checked = !chip.checked; chip.change(chip.checked) }, label = { Text(chip.label) }, modifier = Modifier.padding(3.dp).testTag("blocklist-subgroup-${chip.label}"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OriginalButton("bottom_sheet_rethink_plus_filter:rpf_clear", state.clear, Modifier.weight(.3f).padding(horizontal = 10.dp).testTag("blocklist-filter-clear")) { Text(stringResource(R.string.bsrf_clear_filter), color = themeColor(R.attr.primaryTextColor)) }
            OriginalButton("bottom_sheet_rethink_plus_filter:rpf_apply", state.apply, Modifier.weight(.7f).padding(horizontal = 10.dp).testTag("blocklist-filter-apply")) { Text(stringResource(R.string.lbl_apply), color = themeColor(R.attr.primaryTextColor)) }
        }
    }
}
