@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.celzero.bravedns.ui.compose.firewallapps
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.appdetails.DrawableImage
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.wireguard.wgAnnotatedText
import kotlinx.coroutines.launch

@Composable fun FirewallAppsContent(b:FirewallAppsState) {
    val focus=LocalFocusManager.current;val scroll=rememberLazyListState();val coroutine=rememberCoroutineScope()
    val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current)
    var wasImeOpen by remember{mutableStateOf(false)}
    LaunchedEffect(b.ffaSearch.focusReset){focus.clearFocus()}
    LaunchedEffect(imeBottom){if(imeBottom==0&&wasImeOpen)b.ffaSearch.clearFocus();wasImeOpen=imeBottom>0}
    LaunchedEffect(b.scrollToTopGeneration){if(b.scrollToTopGeneration>0)scroll.scrollToItem(0)}
    Surface(color=themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // The search, chips and filter summary shared one themed card before migration.
            SettingsCard(Modifier.testTag("firewall_search_card"), contentPadding = 0.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(.8f)) {
                        OriginalTextField(
                            b.ffaSearch.query, { b.ffaSearch.setQuery(it, false) },
                            Modifier.fillMaxWidth().testTag("firewall_search"),
                            style = OriginalFieldStyle.Search, singleLine = true,
                            placeholder = { Text(stringResource(R.string.search_firewall_all_apps), fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { b.ffaSearch.setQuery(b.ffaSearch.query, true) }),
                            trailingIcon = {
                                if (b.ffaSearch.query.isNotEmpty()) IconButton({ b.ffaSearch.setQuery("", false) }) {
                                    Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material),
                                        stringResource(R.string.lbl_cancel), Modifier.size(24.dp),
                                        tint = themeColor(androidx.appcompat.R.attr.colorControlNormal))
                                }
                            }
                        )
                    }
                    Box(Modifier.weight(.1f).height(48.dp)) {
                        AppListIcon(b.ffaRefreshList, R.drawable.ic_refresh_white, R.string.refresh_complete,
                            themeColor(androidx.appcompat.R.attr.colorControlNormal), Modifier.fillMaxSize())
                    }
                    Box(Modifier.weight(.1f).height(48.dp)) {
                        AppListIcon(b.ffaFilterIcon, R.drawable.ic_filter, R.string.fapps_filter_filter_heading,
                            themeColor(androidx.appcompat.R.attr.colorControlNormal), Modifier.fillMaxSize())
                    }
                }
                // The former chips had 3dp margins around their 32dp visual height.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val context = LocalContext.current
                        AppListActivity.FirewallFilter.entries.forEach { filter ->
                            OriginalChip("item_chip_filter:(anonymous)", b.selectedFilter == filter.id,
                                { b.selectFirewall(filter.id) }, label = { Text(filter.getLabel(context)) },
                                modifier = Modifier.padding(3.dp))
                        }
                    }
                }
                Text(wgAnnotatedText(b.firewallAppLabelTv.text),
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 5.dp, top = 5.dp, bottom = 10.dp).basicMarquee(),
                    style = utilityTextStyle(size = utilityDimensionSp(LocalContext.current, R.dimen.default_font_text_view)),
                    maxLines = 1)
            }
            // Keep the weighted spacer in a bounded row: horizontalScroll would give it infinite width.
            Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp).testTag("firewall_bulk_actions"),
                verticalAlignment = Alignment.CenterVertically) {
                AppListIcon(b.ffaAppInfoIcon, R.drawable.ic_info_white, R.string.fapps_info_dialog_message,
                    modifier = Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                AppListIcon(b.ffaToggleAllLockdown, R.drawable.ic_firewall_lockdown_off, R.string.ada_app_isolate)
                AppListIcon(b.ffaToggleAllExclude, R.drawable.ic_firewall_exclude_off, R.string.ada_app_exclude)
                AppListIcon(b.ffaToggleAllBypass, R.drawable.ic_firewall_bypass_off, R.string.ada_app_bypass_univ)
                AppListIcon(b.ffaToggleAllBypassDnsFirewall, R.drawable.ic_bypass_dns_firewall_off, R.string.fapps_info_bypass_dns_firewall_msg)
                AppListIcon(b.ffaToggleAllWifi, R.drawable.ic_firewall_wifi_on_grey, R.string.ada_app_unmetered)
                AppListIcon(b.ffaToggleAllMobileData, R.drawable.ic_firewall_data_on_grey, R.string.ada_app_metered,
                    modifier = Modifier.width(34.dp).height(44.dp))
            }
            b.ffaAppList.adapter?.let { adapter ->
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().padding(2.dp).testTag("ffa_app_list"),state=scroll){items(adapter.itemCount,key={index->adapter.peek(index)?.let{"${it.uid}:${it.packageName}"}?:"placeholder:$index"}){adapter.RowAt(it)}}
                    if(adapter.itemCount>scroll.layoutInfo.visibleItemsInfo.size && scroll.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                        var section by remember{mutableStateOf("")}
                        BoxWithConstraints(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(20.dp).pointerInput(adapter.itemCount){
                            detectVerticalDragGestures(onDragEnd={section=""},onDragCancel={section=""}) { change,_ ->
                                val index=(change.position.y/size.height*adapter.itemCount).toInt().coerceIn(0,adapter.itemCount-1)
                                section=adapter.getSectionName(index);coroutine.launch{scroll.scrollToItem(index)};change.consume()
                            }
                        }) {
                            val firstSize = scroll.layoutInfo.visibleItemsInfo.firstOrNull {
                                it.index == scroll.firstVisibleItemIndex
                            }?.size?.coerceAtLeast(1) ?: 1
                            val remaining = (adapter.itemCount - scroll.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
                            val progress = if (!scroll.canScrollForward) 1f else
                                ((scroll.firstVisibleItemIndex + scroll.firstVisibleItemScrollOffset.toFloat() / firstSize) / remaining).coerceIn(0f, 1f)
                            Box(Modifier.align(Alignment.TopEnd).offset(y = (maxHeight - 48.dp).coerceAtLeast(0.dp) * progress)
                                .width(4.dp).height(48.dp).background(themeColor(R.attr.chipColorBgNormal),
                                    androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                        }
                        if(section.isNotEmpty())Surface(Modifier.align(Alignment.CenterEnd).padding(end=24.dp)){Text(section,Modifier.padding(12.dp))}
                    }
                }
            }
        }
    }
}
@Composable private fun AppListIcon(node:AppListControlState,fallback:Int,label:Int,tint:Color=Color.Unspecified, modifier:Modifier=Modifier.size(42.dp)) {
    val tooltip=rememberTooltipState()
    LaunchedEffect(node.tooltipGeneration){if(node.tooltipGeneration>0)tooltip.show()}
    val rotation=if(node.spinning) {
        val transition=rememberInfiniteTransition(label="refresh")
        transition.animateFloat(0f,360f,infiniteRepeatable(tween(750,easing=Easing { ((kotlin.math.cos((it + 1f) * Math.PI) / 2.0) + .5).toFloat() })),label="refresh rotation").value
    } else 0f
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    TooltipBox(modifier = modifier, positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip{Text(stringResource(label))}},state=tooltip) {
        IconButton({node.performClick()},enabled=node.isEnabled,modifier=Modifier.fillMaxSize()) {
            Icon(painterResource(node.imageRes.takeIf{it!=0}?:fallback),stringResource(label), Modifier.size(24.dp).rotate(rotation).alpha(if (!node.isEnabled && tint != Color.Unspecified) .38f else if (fallback == R.drawable.ic_info_white) .5f else 1f), tint=tint)
        }
    }
    }
}
@Composable fun FirewallAppRowContent(b:FirewallAppRowState) {
    // Explicit row style and margins mirror list_item_firewall_app, independently of other cards.
    OriginalCardSurface(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        style = R.style.Widget_Rethink_MaterialCardView,
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("firewall_app_row"),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 72.dp)
                .clickable(enabled = b.firewallAppDetailsLl.isEnabled) { b.firewallAppDetailsLl.performClick() }
                .padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable(enabled = b.firewallAppIconIv.isEnabled) { b.firewallAppIconIv.performClick() }
                    .semanticsLabel(stringResource(R.string.apps_icon_content_desc))) {
                    DrawableImage(b.firewallAppIconIv.drawable, Modifier.size(40.dp).alpha(b.firewallAppIconIv.alpha))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)
                    .clickable(enabled = b.firewallAppTextLl.isEnabled) { b.firewallAppTextLl.performClick() }) {
                    Text(b.firewallAppLabelTv.text.toString(),
                        Modifier.alpha(b.firewallAppLabelTv.alpha).basicMarquee().testTag("firewall_app_title"),
                        style = MaterialTheme.typography.titleMedium, color = themeColor(R.attr.colorOnSurface),
                        maxLines = 1, textDecoration = if (b.strikeThrough) TextDecoration.LineThrough else null)
                    val secondaryStyle = utilityTextStyle(
                        size = utilityDimensionSp(LocalContext.current, R.dimen.small_font_subheading_text))
                    Text(b.firewallAppToggleOther.text.toString(), style = secondaryStyle,
                        color = themeColor(R.attr.colorOnSurfaceVariant), fontStyle = FontStyle.Italic)
                    Text(b.firewallAppDataUsage.text.toString(), style = secondaryStyle,
                        color = themeColor(R.attr.colorOnSurfaceVariant))
                }
            }
            listOf(b.firewallAppToggleWifi to R.string.ada_app_unmetered,
                b.firewallAppToggleMobileData to R.string.ada_app_metered).forEachIndexed { i, (node, label) ->
                if (node.isVisible) {
                    IconButton({ node.performClick() }, enabled = node.isEnabled,
                        modifier = Modifier.size(48.dp)
                            .border(.1.dp, themeColor(R.attr.colorOutline), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .testTag("firewall_row_network_$i")) {
                        Box(Modifier.size(24.dp).semanticsLabel(stringResource(label))) {
                            DrawableImage(node.drawable, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.width(if (i == 0) 4.dp else 8.dp))
                }
            }
        }
    }
}
private fun Modifier.semanticsLabel(label:String)=this.then(Modifier.semantics{contentDescription=label})
