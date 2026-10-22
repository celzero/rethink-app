package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.view.View
import com.celzero.bravedns.R

class RpnExcludeState(context: Context) {
    private var queryValue by androidx.compose.runtime.mutableStateOf("")
    val query: String get() = queryValue
    var revision by androidx.compose.runtime.mutableStateOf(0)
    var onQueryChanged: (String) -> Unit = {}
    fun setQuery(value: String) { queryValue = value; onQueryChanged(value) }
    val actionRow = RpnControlState() .apply { visibility = View.GONE }
    val btnClearSearch = RpnControlState() .apply { alpha = 0.0f; visibility = View.INVISIBLE; setImageResource(R.drawable.ic_cross) ; imageTintList = android.content.res.ColorStateList.valueOf(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryTextColor)) }
    val btnDone = RpnControlState() .apply { text = context.getString(R.string.config_add_success_toast) ; textSizeSp = 15f; bold = true }
    val loadingState = RpnControlState() .apply { visibility = View.VISIBLE }
    val rvCountries = RpnControlState() .apply { visibility = View.GONE }
    val tvEmpty = RpnControlState() .apply { text = context.getString(R.string.auto_exclude_cc_no_servers); alpha = 0.55f; visibility = View.GONE ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 14f }
    val tvMinInfo = RpnControlState() .apply { alpha = 0.75f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 12f }
    val tvSelectedCount = RpnControlState() .apply { alpha = 0.7f ; setTextColor(com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText)); textSizeSp = 12f }
}
