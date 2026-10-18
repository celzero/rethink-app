/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class SupportUiState(context: Context) {
    val root = UtilityControl(context, "activity_customer_support.root").apply {  }
    val appBar = UtilityControl(context, "activity_customer_support.app_bar").apply {  }
    val collapsingToolbar = UtilityControl(context, "activity_customer_support.collapsing_toolbar").apply {  }
    val toolbar = UtilityControl(context, "activity_customer_support.toolbar").apply {  }
    val tvHeroSubtitle = UtilityControl(context, "activity_customer_support.tv_hero_subtitle").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = utilityDimensionSp(context, R.dimen.mini_font_text_view); fontFamily = "sans-serif"; maxLines = 1; ellipsize = true }
    val nestedScroll = UtilityControl(context, "activity_customer_support.nested_scroll").apply {  }
    val chipGroupCategory = UtilityControl(context, "activity_customer_support.chip_group_category").apply {  }
    val chipPayment = UtilityControl(context, "activity_customer_support.chip_payment").apply { text = context.getText(R.string.support_category_payment) }
    val chipActivation = UtilityControl(context, "activity_customer_support.chip_activation").apply { text = context.getText(R.string.support_category_activation) }
    val chipConnectivity = UtilityControl(context, "activity_customer_support.chip_connectivity").apply { text = context.getText(R.string.support_category_connectivity) }
    val chipRefund = UtilityControl(context, "activity_customer_support.chip_refund").apply { text = context.getText(R.string.support_category_refund) }
    val chipMoneyback = UtilityControl(context, "activity_customer_support.chip_moneyback").apply { text = context.getText(R.string.support_category_moneyback) }
    val chipOther = UtilityControl(context, "activity_customer_support.chip_other").apply { text = context.getText(R.string.category_name_others) }
    val tilDescription = UtilityControl(context, "activity_customer_support.til_description").apply { hint = context.getText(R.string.support_description_hint) }
    val etDescription = UtilityControl(context, "activity_customer_support.et_description").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontSize = 14f; maxLines = 10; maxLength = 1000 }
    val switchAttachStatus = UtilityControl(context, "activity_customer_support.switch_attach_status").apply { isChecked = true }
    val switchAttachStats = UtilityControl(context, "activity_customer_support.switch_attach_stats").apply { isChecked = true }
    val switchAttachProcInfo = UtilityControl(context, "activity_customer_support.switch_attach_proc_info").apply { isChecked = true }
    val layoutBottomBar = UtilityControl(context, "activity_customer_support.layout_bottom_bar").apply {  }
    val btnSendEmail = UtilityControl(context, "activity_customer_support.btn_send_email").apply { text = context.getText(R.string.about_bug_report_dialog_positive_btn); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood); fontSize = 16f }
    val layoutLoading = UtilityControl(context, "activity_customer_support.layout_loading").apply { visibility = android.view.View.GONE; centered = true }
    val progressBar = UtilityControl(context, "activity_customer_support.progress_bar").apply {  }
    var keyboardRequest by mutableIntStateOf(0)
    var headerTitle by mutableStateOf("")
    val categories get() = listOf(chipPayment, chipActivation, chipConnectivity, chipRefund, chipMoneyback, chipOther)
    fun selectCategory(value: UtilityControl) { val checked = !value.isChecked; categories.forEach { it.isChecked = it === value && checked } }
    val savedControls get() = listOf<UtilityControl>(chipPayment, chipActivation, chipConnectivity, chipRefund, chipMoneyback, chipOther, etDescription, switchAttachStatus, switchAttachStats, switchAttachProcInfo)
}
