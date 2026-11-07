/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class PingUiState(context: Context) {
    val root = UtilityControl(context, "activity_ping_test.root").apply {  }
    val headerContainer = UtilityControl(context, "activity_ping_test.header_container").apply {  }
    val testCard = UtilityControl(context, "activity_ping_test.test_card").apply {  }
    val progressIndicator = UtilityControl(context, "activity_ping_test.progress_indicator").apply { visibility = android.view.View.GONE }
    val statusIcon = UtilityControl(context, "activity_ping_test.status_icon").apply { imageRes = R.drawable.ic_shield_check; colorFilter = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor) }
    val statusTitle = UtilityControl(context, "activity_ping_test.status_title").apply { text = context.getText(R.string.settings_connectivity_checks); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = 22f; fontFamily = "sans-serif-medium"; fontStyle = 1; maxLines = 1; centered = true }
    val statusDescription = UtilityControl(context, "activity_ping_test.status_description").apply { text = context.getText(R.string.ping_ready_desc); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText); fontSize = 14f; centered = true }
    val latencyContainer = UtilityControl(context, "activity_ping_test.latency_container").apply { visibility = android.view.View.GONE }
    val latencyText = UtilityControl(context, "activity_ping_test.latency_text").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = 13f; fontFamily = "sans-serif-medium"; fontStyle = 1; maxLines = 1 }
    val reachInputLayout = UtilityControl(context, "activity_ping_test.reach_input_layout").apply { hint = context.getText(R.string.ping_reach_hint) }
    val reachInput = UtilityControl(context, "activity_ping_test.reach_input").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = 15f }
    val rpnInactiveNote = UtilityControl(context, "activity_ping_test.rpn_inactive_note").apply { text = context.getText(R.string.ping_vpn_inactive_note); visibility = android.view.View.GONE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText); fontSize = 12f; centered = true }
    val pingButton = UtilityControl(context, "activity_ping_test.ping_button").apply { text = context.getText(R.string.rpn_perform_test); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = 16f; letterSpacing = 0.02f }
    val historyScrollView = UtilityControl(context, "activity_ping_test.history_scroll_view").apply {  }
    val historyCard = UtilityControl(context, "activity_ping_test.history_card").apply { visibility = android.view.View.GONE }
    val historyRecycler = UtilityControl(context, "activity_ping_test.history_recycler").apply {  }
    val dolphinSignature = com.celzero.bravedns.ui.compose.rpn.RpnSignatureState()
    var keyboardRequest by mutableIntStateOf(0)
    var motion by mutableStateOf("none")
    var motionRevision by mutableIntStateOf(0)
    val savedControls get() = listOf<UtilityControl>(reachInput)
}
