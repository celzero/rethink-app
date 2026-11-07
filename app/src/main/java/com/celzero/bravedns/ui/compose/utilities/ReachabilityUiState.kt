/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class ReachabilityUiState(context: Context) {
    val toggleGroup = UtilityControl(context, "dialog_input_ips.toggle_group").apply {  }
    val autoToggleBtn = UtilityControl(context, "dialog_input_ips.auto_toggle_btn").apply { text = context.getText(R.string.settings_ip_text_ipv46); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.defaultToggleBtnTxt); fontSize = utilityDimensionSp(context, R.dimen.extra_large_font_text_view) }
    val manualToggleBtn = UtilityControl(context, "dialog_input_ips.manual_toggle_btn").apply { text = context.getText(R.string.lbl_manual); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.defaultToggleBtnTxt); fontSize = utilityDimensionSp(context, R.dimen.extra_large_font_text_view) }
    val protocolV4 = UtilityControl(context, "dialog_input_ips.protocol_v4").apply { visibility = android.view.View.VISIBLE }
    val resetChip = UtilityControl(context, "dialog_input_ips.reset_chip").apply { text = context.getText(R.string.brbs_restore_title); visibility = android.view.View.VISIBLE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.chipTextPositive); fontSize = utilityDimensionSp(context, R.dimen.large_font_text_view) }
    val ipv4Address1 = UtilityControl(context, "dialog_input_ips.ipv4_address_1").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressIpv41 = UtilityControl(context, "dialog_input_ips.progress_ipv4_1").apply { visibility = android.view.View.GONE }
    val statusIpv41 = UtilityControl(context, "dialog_input_ips.status_ipv4_1").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val ipv4Address2 = UtilityControl(context, "dialog_input_ips.ipv4_address_2").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressIpv42 = UtilityControl(context, "dialog_input_ips.progress_ipv4_2").apply { visibility = android.view.View.GONE }
    val statusIpv42 = UtilityControl(context, "dialog_input_ips.status_ipv4_2").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val urlV4Layout1 = UtilityControl(context, "dialog_input_ips.url_v4_layout_1").apply {  }
    val urlV4Address1 = UtilityControl(context, "dialog_input_ips.url_v4_address_1").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressUrlV41 = UtilityControl(context, "dialog_input_ips.progress_url_v4_1").apply { visibility = android.view.View.GONE }
    val statusUrlV41 = UtilityControl(context, "dialog_input_ips.status_url_v4_1").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val urlV4Layout2 = UtilityControl(context, "dialog_input_ips.url_v4_layout_2").apply {  }
    val urlV4Address2 = UtilityControl(context, "dialog_input_ips.url_v4_address_2").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressUrlV42 = UtilityControl(context, "dialog_input_ips.progress_url_v4_2").apply { visibility = android.view.View.GONE }
    val statusUrlV42 = UtilityControl(context, "dialog_input_ips.status_url_v4_2").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val protocolV6 = UtilityControl(context, "dialog_input_ips.protocol_v6").apply { visibility = android.view.View.VISIBLE }
    val ipv6Address1 = UtilityControl(context, "dialog_input_ips.ipv6_address_1").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressIpv61 = UtilityControl(context, "dialog_input_ips.progress_ipv6_1").apply { visibility = android.view.View.GONE }
    val statusIpv61 = UtilityControl(context, "dialog_input_ips.status_ipv6_1").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val ipv6Address2 = UtilityControl(context, "dialog_input_ips.ipv6_address_2").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressIpv62 = UtilityControl(context, "dialog_input_ips.progress_ipv6_2").apply { visibility = android.view.View.GONE }
    val statusIpv62 = UtilityControl(context, "dialog_input_ips.status_ipv6_2").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val urlV6Layout1 = UtilityControl(context, "dialog_input_ips.url_v6_layout_1").apply {  }
    val urlV6Address1 = UtilityControl(context, "dialog_input_ips.url_v6_address_1").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressUrlV61 = UtilityControl(context, "dialog_input_ips.progress_url_v6_1").apply { visibility = android.view.View.GONE }
    val statusUrlV61 = UtilityControl(context, "dialog_input_ips.status_url_v6_1").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val urlV6Layout2 = UtilityControl(context, "dialog_input_ips.url_v6_layout_2").apply {  }
    val urlV6Address2 = UtilityControl(context, "dialog_input_ips.url_v6_address_2").apply { background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.edittext_default) }
    val progressUrlV62 = UtilityControl(context, "dialog_input_ips.progress_url_v6_2").apply { visibility = android.view.View.GONE }
    val statusUrlV62 = UtilityControl(context, "dialog_input_ips.status_url_v6_2").apply { visibility = android.view.View.GONE; imageRes = R.drawable.ic_tick }
    val errorMessage = UtilityControl(context, "dialog_input_ips.error_message").apply {  }
    val saveButton = UtilityControl(context, "dialog_input_ips.save_button").apply { text = context.getText(R.string.lbl_save); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood); fontSize = utilityDimensionSp(context, R.dimen.extra_large_font_text_view); textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead; centered = true }
    val testButton = UtilityControl(context, "dialog_input_ips.test_button").apply { text = context.getText(R.string.lbl_test); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood); fontSize = utilityDimensionSp(context, R.dimen.extra_large_font_text_view); textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead; centered = true }
    val savedControls get() = listOf<UtilityControl>(resetChip, ipv4Address1, ipv4Address2, urlV4Address1, urlV4Address2, ipv6Address1, ipv6Address2, urlV6Address1, urlV6Address2)
}
