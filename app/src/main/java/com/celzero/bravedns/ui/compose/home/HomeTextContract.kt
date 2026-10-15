/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import androidx.compose.ui.text.style.TextOverflow
import com.celzero.bravedns.R

/** Scalar presentation properties from the pinned Home XML; no layout data or inflation. */
internal data class HomeTextContract(val family: String? = null, val tracking: Float = 0f,
    val lines: Int = Int.MAX_VALUE, val overflow: TextOverflow = TextOverflow.Clip, val marquee: Boolean = false)

internal fun homeTextContract(id: Int): HomeTextContract = when (id) {
    R.id.fhs_protection_level_txt -> HomeTextContract("sans-serif-medium", .04f, 1, TextOverflow.Ellipsis)
    R.id.fhs_active_since_txt -> HomeTextContract(lines = 1, overflow = TextOverflow.Ellipsis)
    R.id.fhs_card_dns_connected_dns -> HomeTextContract("sans-serif-medium", lines = 1, marquee = true)
    R.id.fhs_card_dns_latency -> HomeTextContract(lines = 1, marquee = true)
    R.id.fhs_card_other_proxy_count -> HomeTextContract("sans-serif-medium", lines = 1, overflow = TextOverflow.Ellipsis)
    R.id.fhs_proxy_live_count, R.id.fhs_proxy_idle_count, R.id.fhs_proxy_failing_count, R.id.fhs_proxy_stopped_count -> HomeTextContract(lines = 1)
    R.id.fhs_card_apps_blocked_count, R.id.fhs_card_apps_isolated_count, R.id.fhs_card_apps_bypassed_count, R.id.fhs_card_apps_excluded_count -> HomeTextContract(tracking = .02f)
    R.id.fhs_logs_disabled_txt -> HomeTextContract(lines = 2, overflow = TextOverflow.Ellipsis)
    R.id.fhs_card_allowed_logs_label, R.id.fhs_card_blocked_logs_label -> HomeTextContract(tracking = .15f)
    R.id.fhs_logs_hour_start, R.id.fhs_logs_hour_start_plus_6, R.id.fhs_logs_hour_start_plus_12, R.id.fhs_logs_hour_start_plus_18 -> HomeTextContract(tracking = .05f, lines = 1)
    R.id.fhs_card_logs_duration -> HomeTextContract(tracking = .08f)
    R.id.fhs_top_apps_wifi_value, R.id.fhs_top_apps_mobile_value -> HomeTextContract(lines = 1, overflow = TextOverflow.Ellipsis)
    R.id.fhs_top_apps_wifi_label, R.id.fhs_top_apps_mobile_label -> HomeTextContract(tracking = .15f, lines = 1, overflow = TextOverflow.Ellipsis)
    R.id.fhs_top_apps_window -> HomeTextContract(tracking = .08f, lines = 1, overflow = TextOverflow.Ellipsis)
    else -> HomeTextContract()
}

/** Preserve native dimension SP at Android14+ nonlinear font scales. */
internal fun homeTextSizeSp(context: android.content.Context, pixels: Float): Float =
    if (android.os.Build.VERSION.SDK_INT >= 34) android.util.TypedValue.deriveDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, pixels, context.resources.displayMetrics)
    else pixels / context.resources.displayMetrics.scaledDensity
