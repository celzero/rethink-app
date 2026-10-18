@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.rpn

import com.celzero.bravedns.ui.compose.controls.*
import android.app.Activity
import android.app.Dialog
import android.util.TypedValue
import android.view.Window
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import com.celzero.bravedns.util.UIUtils

fun rpnFilterDialog(activity: Activity, loads: List<String>, selectedLoad: Int, speeds: List<String>, selectedSpeed: Int,
                    favourite: Boolean, onApply: (Int, Int, Boolean) -> Unit, onReset: () -> Unit) {
    val value = TypedValue()
    activity.theme.resolveAttribute(com.google.android.material.R.attr.materialAlertDialogTheme, value, true)
    val dialog = Dialog(activity, value.resourceId)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.setWgContent(activity) { RpnFilterContent(loads, selectedLoad, speeds, selectedSpeed, favourite,
        onApply = { load, speed, fav -> onApply(load, speed, fav); dialog.dismiss() },
        onReset = { onReset(); dialog.dismiss() }, onCancel = { dialog.dismiss() }) }
    dialog.show()
    UIUtils.capDialogWidth(dialog)
}

@Composable
fun RpnFilterContent(loads: List<String>, selectedLoad: Int, speeds: List<String>, selectedSpeed: Int, favourite: Boolean,
                     onApply: (Int, Int, Boolean) -> Unit, onReset: () -> Unit, onCancel: () -> Unit) {
    var load by remember { mutableIntStateOf(selectedLoad) }
    var speed by remember { mutableIntStateOf(selectedSpeed) }
    var fav by remember { mutableStateOf(favourite) }
    val accent = themeColor(R.attr.accentGood)
    val neutral = themeColor(R.attr.primaryLightColorText)
    Surface(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Text(stringResource(R.string.server_selection_filter_locations), style = MaterialTheme.typography.headlineSmall)
            FilterLabel(R.string.server_selection_filter_by_load)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { loads.forEachIndexed { index, label -> RpnFilterChip(label, load == index) { load = index } } }
            FilterLabel(R.string.server_selection_filter_by_speed)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { speeds.forEachIndexed { index, label -> RpnFilterChip(label, speed == index) { speed = index } } }
            FilterLabel(R.string.server_selection_filter_favourites)
            RpnFilterChip(stringResource(R.string.server_selection_filter_favourites_only), fav) { fav = !fav }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OriginalButton("@dialog_neutral", onReset, Modifier.testTag("rpn_filter_reset")) { Text(stringResource(R.string.lbl_reset)) }
                OriginalButton("@dialog_neutral", onCancel, Modifier.testTag("rpn_filter_cancel")) { Text(stringResource(R.string.lbl_cancel)) }
                OriginalButton("@dialog_positive", { onApply(load, speed, fav) }, Modifier.testTag("rpn_filter_apply")) { Text(stringResource(R.string.lbl_apply)) }
            }
        }
    }
}
@Composable private fun FilterLabel(text: Int) { Text(stringResource(text).uppercase(), Modifier.padding(top = 10.dp, bottom = 6.dp), fontSize = 10.5.sp, letterSpacing = 1.365.sp, color = themeColor(R.attr.primaryLightColorText)) }
@Composable private fun RpnFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = themeColor(R.attr.accentGood)
    OriginalChip("@rpn_filter", selected, onClick, { Text(label) }, Modifier.heightIn(min = 40.dp))
}
