package com.celzero.bravedns.ui.compose.rpn

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.bottomsheet.AutoExcludeCountriesBottomSheet.CountryItem
import com.celzero.bravedns.ui.compose.themeColor

@Composable
fun RpnExcludeContent(ui: RpnExcludeState, filtered: List<CountryItem>, all: List<CountryItem>, minAvailable: Int, onToggle: (CountryItem) -> Unit) {
    ui.revision // Lists intentionally retain the baseline in-place edits and full invalidation.
    var restoredQuery by rememberSaveable { mutableStateOf(ui.query) }
    LaunchedEffect(ui) { if (restoredQuery != ui.query) ui.setQuery(restoredQuery) }
    LaunchedEffect(ui.query) { restoredQuery = ui.query }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 10.dp).size(40.dp, 4.dp).alpha(.5f).background(themeColor(R.attr.border)))
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_filter), null, Modifier.size(48.dp).padding(12.dp))
            Column(Modifier.padding(start = 14.dp)) { Text(stringResource(R.string.auto_exclude_cc_title), fontSize = 20.sp); Text(stringResource(R.string.auto_exclude_cc_subtitle), Modifier.alpha(.7f), fontSize = 13.sp) }
        }
        RpnSettingsCard { RpnText(ui.tvMinInfo, Modifier.padding(12.dp), MaterialTheme.typography.bodySmall) }
        Surface(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = themeColor(androidx.cardview.R.attr.cardBackgroundColor)) {
        OriginalTextField(ui.query, { restoredQuery = it; ui.setQuery(it) }, Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 12.dp, end = 4.dp), style = OriginalFieldStyle.TransparentSearch, singleLine = true,
            placeholder = { Text(stringResource(R.string.auto_exclude_cc_search_hint)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp)) },
            trailingIcon = { if (ui.btnClearSearch.visibility != android.view.View.GONE) IconButton({ ui.btnClearSearch.performClick() }, Modifier.rpnVisibility(ui.btnClearSearch).rpnMotion(ui.btnClearSearch)) { Icon(painterResource(R.drawable.ic_cross), stringResource(R.string.lbl_cancel), Modifier.size(20.dp), tint = themeColor(R.attr.primaryTextColor)) } })
        }
        if (ui.actionRow.isVisible) RpnText(ui.tvSelectedCount, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            RpnSettingsCard {
                if (ui.loadingState.isVisible) Column(Modifier.fillMaxWidth().height(120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(36.dp)); Text(stringResource(R.string.auto_exclude_cc_loading), Modifier.padding(top = 12.dp).alpha(.55f), fontSize = 13.sp)
                }
                if (ui.tvEmpty.isVisible) RpnText(ui.tvEmpty, Modifier.fillMaxWidth().padding(24.dp))
                if (ui.rvCountries.isVisible) Column(Modifier.rpnMotion(ui.rvCountries)) {
                    val count = all.count { it.isExcluded }
                    val maxAllowed = all.size - minAvailable
                    val limitReached = maxAllowed in 1..count
                    filtered.forEach { item -> key(item.cc) {
                        Row(Modifier.fillMaxWidth().alpha(if (item.isExcluded || !limitReached) 1f else .45f).clickable { onToggle(item) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.flagEmoji, fontSize = 24.sp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(item.countryName); Text(item.cc, style = MaterialTheme.typography.bodySmall) }
                            OriginalCheckbox("list_item_exclude_country:cb_exclude", item.isExcluded, null)
                        }
                    } }
                }
            }
        }
        OriginalButton("bottomsheet_auto_exclude_countries:btn_done", { ui.btnDone.performClick() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp)) { OriginalRpnText(ui.btnDone) }
    }
}
