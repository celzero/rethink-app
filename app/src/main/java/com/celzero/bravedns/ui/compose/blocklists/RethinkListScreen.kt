package com.celzero.bravedns.ui.compose.blocklists

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.ui.compose.dnssettings.DnsControl
import com.celzero.bravedns.ui.compose.settings.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.themeColor
import kotlinx.coroutines.flow.Flow

@Composable fun RethinkListScreen(state: RethinkListState?, pages: Flow<PagingData<RethinkDnsEndpoint>>, owner: LifecycleOwner, context: Context, appConfig: AppConfig, configure: (() -> Unit)? = null) {
    val presenter = remember(context, owner, appConfig) { RethinkEndpointPresenter(context, appConfig, owner) }
    val flow = remember(pages, owner) { pages.flowWithLifecycle(owner.lifecycle) }
    val list = flow.collectAsLazyPagingItems()
    Surface(color = themeColor(R.attr.background)) {
        // The standalone activity delegates insets to this screen; the bottom sheet
        // already owns its window insets and must not receive another full-screen pad.
        LazyColumn(Modifier.fillMaxWidth().then(if (state != null) Modifier.safeDrawingPadding() else Modifier)) {
            if (configure != null) item {
                Text("RethinkDNS List", Modifier.padding(16.dp).semantics { heading() }, fontSize = 20.sp, color = themeColor(R.attr.primaryLightColorText))
                Text("Click here to configure blocklists.", Modifier.fillMaxWidth().clickable(onClick = configure).padding(16.dp), fontSize = 14.sp, color = themeColor(R.attr.primaryTextColor))
            }
            state?.let { b -> item {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (b.lbVersion.visible) OriginalChip("fragment_rethink_list:lb_version", selected = false, selectable = false, onClick = {}, enabled = false, label = { Text(b.lbVersion.text) })
                    UpdateChip(b.bslbCheckUpdateBtn, R.string.rt_chip_check_update, "check")
                    UpdateChip(b.bslbUpdateAvailableBtn, R.string.rt_chip_update_available, "update")
                    UpdateChip(b.bslbRedownloadBtn, R.string.rt_re_download, "redownload")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RethinkChoice(b.radioSky, R.string.radio_sky_btn, "sky")
                    RethinkChoice(b.radioMax, R.string.radio_max_btn, "max")
                }
                Text(b.frlDesc.text, Modifier.padding(16.dp), color = themeColor(R.attr.primaryLightColorText), fontSize = 14.sp)
            } }
            items(list.itemCount) { index -> list[index]?.let { endpoint -> RethinkEndpointRow(endpoint, presenter, if (configure == null) 4.dp else 0.dp) } }
            if (state != null && list.itemCount == 0) item {
                if (list.loadState.source.refresh is LoadState.Loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                if (list.loadState.source.refresh is LoadState.NotLoading) Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.rethink_empty_state_title), color = themeColor(R.attr.primaryTextColor))
                    Text(stringResource(R.string.rethink_empty_state_desc), color = themeColor(R.attr.primaryLightColorText))
                }
            }
        }
    }
    presenter.metadata?.let { endpoint ->
        NoDimDialogWindow(onDismissRequest = { presenter.metadata = null }, properties = DialogProperties()) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = themeColor(R.attr.background)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Text(endpoint.name, style = MaterialTheme.typography.headlineSmall, color = themeColor(R.attr.primaryTextColor))
                    Text(endpoint.url + "\n\n" + endpoint.desc, Modifier.padding(vertical = 16.dp), color = themeColor(R.attr.primaryTextColor))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OriginalButton("@dialog_positive", onClick = { presenter.copy(endpoint); presenter.metadata = null }) { Text(stringResource(R.string.dns_info_neutral), color = themeColor(R.attr.secondaryTextColor)) }
                        OriginalButton("@dialog_positive", onClick = { if (presenter.editable(endpoint)) presenter.edit(endpoint); presenter.metadata = null }) { Text(stringResource(if (presenter.editable(endpoint)) R.string.rt_edit_dialog_positive else R.string.dns_info_positive), color = themeColor(androidx.appcompat.R.attr.colorPrimary)) }
                    }
                }
            }
        }
    }
}

@Composable private fun UpdateChip(state: RethinkChipState, label: Int, key: String) {
    if (!state.visible) return
    OriginalChip("fragment_rethink_list:" + when (key) { "update" -> "bslb_update_available_btn"; "check" -> "bslb_check_update_btn"; else -> "bslb_redownload_btn" }, selected = false, selectable = false, onClick = state.onClick, enabled = state.isEnabled, modifier = Modifier.testTag("rethink-$key"), label = { Text(stringResource(label), color = themeColor(R.attr.chipTextPositive)) }, leadingIcon = if (state.isChipIconVisible) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = themeColor(R.attr.chipTextPositive)) }) else null)
}
@Composable private fun RethinkChoice(control: DnsControl, label: Int, key: String) {
    // Original RadioButton suppresses android:button and uses drawableBottom with 5dp spacing.
    Column(Modifier.testTag("rethink-$key").clickable(role = Role.RadioButton) { control.isChecked = true; control.onClick() }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(label), color = themeColor(R.attr.primaryTextColor))
        Spacer(Modifier.height(5.dp))
        OriginalRadioButton("fragment_rethink_list:radio_$key", control.isChecked, null, indicatorOnly = true)
    }
}
@Composable private fun RethinkEndpointRow(endpoint: RethinkDnsEndpoint, presenter: RethinkEndpointPresenter, spacing: Dp) {
    val bound = remember(System.identityHashCode(endpoint), endpoint.url, endpoint.isActive, endpoint.blocklistCount) { presenter.bind(endpoint) }
    DisposableEffect(bound) { onDispose { bound.dispose() } }
    val label = stringResource(if (endpoint.isActive) R.string.dns_list_item_selected_cd else R.string.dns_list_item_select_cd, endpoint.name)
    SettingsCard(Modifier.padding(spacing), contentPadding = 0.dp, cornerRadius = 12.dp) {
        Row(Modifier.fillMaxWidth().semantics { contentDescription = label; selected = endpoint.isActive }.clickable { presenter.select(endpoint) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (bound.rethinkEndpointListUrlFlagText.visible) Text(bound.rethinkEndpointListUrlFlagText.text, Modifier.padding(end = 10.dp).sizeIn(minWidth = 32.dp, minHeight = 32.dp), fontSize = 25.sp)
            Column(Modifier.weight(1f)) {
                Text(endpoint.name, color = themeColor(R.attr.primaryTextColor), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (bound.rethinkEndpointListUrlExplanation.visible) Text(bound.rethinkEndpointListUrlExplanation.text, Modifier.padding(top = 2.dp), color = themeColor(R.attr.primaryLightColorText), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { presenter.metadata = endpoint }, modifier = Modifier.padding(end = 4.dp).alpha(.55f)) { Icon(painterResource(if (presenter.editable(endpoint)) R.drawable.ic_edit_icon else R.drawable.ic_info), stringResource(if (presenter.editable(endpoint)) R.string.rt_edit_dialog_positive else R.string.dns_info_positive), Modifier.size(24.dp), tint = themeColor(R.attr.primaryTextColor)) }
            RethinkSelection(endpoint.isActive)
        }
    }
}
@Composable private fun RethinkSelection(selected: Boolean) {
    val opacity by animateFloatAsState(if (selected) 1f else 0f, tween(if (selected) 180 else 160), label = "selection-opacity")
    val scale by animateFloatAsState(if (selected) 1f else .6f, tween(if (selected) 180 else 160), label = "selection-scale")
    Box(Modifier.padding(end = 12.dp).size(32.dp, 18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(dimensionResource(R.dimen.selection_orbital_size)).alpha(1f - opacity).border(dimensionResource(R.dimen.selection_orbital_stroke), themeColor(R.attr.primaryTextColor), CircleShape))
        Box(Modifier.size(dimensionResource(R.dimen.selection_pill_width), dimensionResource(R.dimen.selection_pill_height)).alpha(opacity).scale(scale).background(themeColor(R.attr.accentGood), RoundedCornerShape(dimensionResource(R.dimen.selection_pill_radius))), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.padding(end = dimensionResource(R.dimen.selection_switch_knob_inset)).size(dimensionResource(R.dimen.selection_switch_knob_size)).background(themeColor(R.attr.invertedPrimaryTextColor), CircleShape))
        }
    }
}
