package com.celzero.bravedns.ui.compose.rpn

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.google.android.material.R as MaterialR
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import android.graphics.Typeface

@Composable
fun RpnRemovalContent(ui: RpnRemovalState) {
    OriginalCardSurface(Modifier.fillMaxWidth().padding(16.dp).rpnMotion(ui.notificationCard),
        cornerRadius = 24.dp, border = BorderStroke(0.dp, Color.Transparent)) {
        Column(Modifier.fillMaxWidth().background(themeColor(MaterialR.attr.colorSurface)).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                OriginalCardSurface(Modifier.size(64.dp).rpnMotion(ui.iconContainer),
                    cornerRadius = 32.dp, elevation = 0.dp, border = BorderStroke(0.dp, Color.Transparent)) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        // gradient_premium_bg, angle135: bottom-right green through blue to top-left purple.
                        drawOval(Brush.linearGradient(listOf(Color(0xFF4CAF50), Color(0xFF2196F3), Color(0xFF9C27B0)),
                            start = Offset(size.width, size.height), end = Offset.Zero))
                    }, contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_info_white), null, Modifier.size(32.dp).rpnMotion(ui.notificationIcon), tint = Color.White)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    RpnText(ui.notificationTitle, style = utilityTextStyle(size = 20f, weight = Typeface.BOLD, spacing = .01f))
                    RpnText(ui.notificationSubtitle, Modifier.padding(top = 4.dp), utilityTextStyle(size = 13f))
                }
            }
            HorizontalDivider(Modifier.padding(top = 20.dp), thickness = 1.dp, color = themeColor(R.attr.primaryTextColor).copy(alpha = .1f))
            Column(Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
                ui.rows.forEach { (server, row) -> key(server.id) {
                    OriginalCardSurface(Modifier.fillMaxWidth().padding(bottom = 12.dp).rpnMotion(row),
                        cornerRadius = 16.dp, elevation = 0.dp, border = BorderStroke(1.dp, themeColor(R.attr.chipBgColorNeutral))) {
                        Row(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).padding(16.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Text(server.flagEmoji, style = utilityTextStyle(size = 24f))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(server.countryName, color = themeColor(R.attr.primaryTextColor), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = utilityTextStyle(size = 16f, weight = Typeface.BOLD))
                                Text(server.serverLocation, Modifier.padding(top = 4.dp).alpha(.6f),
                                    color = themeColor(R.attr.primaryLightColorText), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = utilityTextStyle(size = 13f))
                            }
                            OriginalLabel("item_removed_server:removal_badge", Modifier.align(Alignment.CenterVertically)) { Text(stringResource(R.string.server_removed)) }
                        }
                    }
                } }
            }
            OriginalButton("bottomsheet_server_removal_notification:btn_understand", { ui.btnUnderstand.performClick() },
                Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp).rpnMotion(ui.btnUnderstand), enabled = ui.btnUnderstand.isEnabled) {
                OriginalRpnText(ui.btnUnderstand)
            }
        }
    }
}
