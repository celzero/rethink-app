/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class BubbleUiState(context: Context) {
    val bubbleQuickFixesLl = UtilityControl(context, "activity_bubble.bubble_quick_fixes_ll").apply { visibility = android.view.View.VISIBLE }
    val bubbleQuickFixesContainer = UtilityControl(context, "activity_bubble.bubble_quick_fixes_container").apply {  }
    val bubbleAllowedAppsLl = UtilityControl(context, "activity_bubble.bubble_allowed_apps_ll").apply { visibility = android.view.View.VISIBLE }
    val bubbleAllowedCount = UtilityControl(context, "activity_bubble.bubble_allowed_count").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorPrimary); textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Caption }
    val bubbleAllowedRecyclerView = UtilityControl(context, "activity_bubble.bubble_allowed_recycler_view").apply {  }
    val bubbleProgressCard = UtilityControl(context, "activity_bubble.bubble_progress_card").apply { visibility = android.view.View.GONE; centered = true }
    val bubbleProgressBar = UtilityControl(context, "activity_bubble.bubble_progress_bar").apply {  }
    val bubbleRecyclerView = UtilityControl(context, "activity_bubble.bubble_recycler_view").apply {  }
    val bubbleEmptyState = UtilityControl(context, "activity_bubble.bubble_empty_state").apply { visibility = android.view.View.VISIBLE; centered = true }
    val bubbleEmptyTitle = UtilityControl(context, "activity_bubble.bubble_empty_title").apply { text = context.getText(R.string.bubble_empty_state_title); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor); fontStyle = 1; textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead }
    var blockedAdapter by mutableStateOf<com.celzero.bravedns.adapter.BubbleBlockedAppsAdapter?>(null)
    var allowedAdapter by mutableStateOf<com.celzero.bravedns.adapter.BubbleAllowedAppsAdapter?>(null)
    val quickFixes = mutableStateListOf<BubbleQuickFix>()
    val savedControls get() = listOf<UtilityControl>()
}
