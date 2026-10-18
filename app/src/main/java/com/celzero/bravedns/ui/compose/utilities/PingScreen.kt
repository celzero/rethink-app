/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.PingHistoryRow
import com.celzero.bravedns.adapter.PingTestHistoryAdapter
import com.celzero.bravedns.ui.compose.rpn.RpnSignature
import com.celzero.bravedns.ui.compose.themeColor

@Composable fun PingScreen(ui: PingUiState, history: PingTestHistoryAdapter) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.keyboardRequest) { if (ui.keyboardRequest > 0) { focus.clearFocus(); keyboard?.hide() } }
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val scrollHero = maxHeight < 640.dp || LocalDensity.current.fontScale > 1.3f
            val scroll = rememberLazyListState()
            Column(Modifier.fillMaxSize()) {
                if (!scrollHero) PingHero(ui, Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ping.history.list"), state = scroll,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 140.dp)) {
                    if (scrollHero) item("hero") { PingHero(ui, Modifier.padding(bottom = 12.dp)) }
                    if (ui.historyCard.isVisible) itemsIndexed(history.entries, key = { index, entry -> "${entry.timestamp}-$index" }) { index, entry ->
                        Surface(color = themeColor(androidx.cardview.R.attr.cardBackgroundColor), shape = RoundedCornerShape(
                            topStart = if (index == 0) 16.dp else 0.dp, topEnd = if(index == 0) 16.dp else 0.dp,
                            bottomStart = if(index == history.entries.lastIndex) 16.dp else 0.dp, bottomEnd = if(index == history.entries.lastIndex) 16.dp else 0.dp)) {
                            Column { PingHistoryRow(entry); if(index < history.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 61.dp), color = themeColor(R.attr.colorSurfaceVariant).copy(alpha = .4f)) }
                        }
                    }
                }
            }
            val end by remember { derivedStateOf { !scroll.canScrollForward } }
            AnimatedVisibility(end, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) { RpnSignature(ui.dolphinSignature) }
        }
    }
}
@Composable private fun PingHero(ui: PingUiState, modifier: Modifier) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().utilityHeroBackground().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                if(ui.progressIndicator.isVisible) CircularProgressIndicator(Modifier.size(104.dp).testTag("ping.progress"), color = themeColor(R.attr.accentGood), strokeWidth = 4.dp)
                UtilityImage(ui.statusIcon, Modifier.size(60.dp).graphicsLayer {
                    translationX = ui.statusIcon.translationX; translationY = ui.statusIcon.translationY
                    rotationZ = ui.statusIcon.rotation; scaleX = ui.statusIcon.scaleX; scaleY = ui.statusIcon.scaleY; alpha = ui.statusIcon.alpha
                }, stringResource(R.string.settings_connectivity_checks))
            }
            UtilityText(ui.statusTitle, Modifier.padding(top = 12.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite })
            UtilityText(ui.statusDescription, Modifier.padding(top = 4.dp))
            if(ui.latencyContainer.isVisible) Surface(Modifier.padding(top = 12.dp), shape = RoundedCornerShape(12.dp), color = themeColor(R.attr.vpnHeaderGradientStart), border = BorderStroke(1.dp, themeColor(R.attr.serverHeaderStrokeColor))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("●", color = themeColor(R.attr.accentGood), modifier = Modifier.padding(end = 8.dp)); UtilityText(ui.latencyText)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = themeColor(R.attr.colorSurfaceVariant).copy(alpha = .25f))
            OriginalTextField(ui.reachInput.text.toString(), { ui.reachInput.text = it }, Modifier.fillMaxWidth().testTag(ui.reachInput.tag), style = OriginalFieldStyle.Ping,
                enabled = ui.reachInput.isEnabled && ui.reachInputLayout.isEnabled, singleLine = true,
                label = { Text(ui.reachInputLayout.hint.toString()) }, textStyle = utilityTextStyle(size = 15f),
                isError = ui.reachInputLayout.error != null, errorMessage = ui.reachInputLayout.error?.toString(), supportingText = ui.reachInputLayout.error?.let { error -> { Text(error.toString()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { ui.reachInput.dispatchDone() }))
            UtilityText(ui.rpnInactiveNote, Modifier.padding(top = 8.dp))
            OriginalButton("activity_ping_test:ping_button", { ui.pingButton.performClick() }, enabled = ui.pingButton.isEnabled, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp).testTag(ui.pingButton.tag)) {
                Text(ui.pingButton.text.toString(), style = utilityTextStyle(size = 16f, spacing = .02f))
            }
        }
    }
}
