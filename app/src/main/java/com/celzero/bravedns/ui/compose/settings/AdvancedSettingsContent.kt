/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import com.celzero.bravedns.ui.compose.controls.*

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

/** Mirrors SeekBar's presentation callbacks without owning an Android View. */
class SettingsSeekBar {
    var progress by mutableIntStateOf(5)
    var max by mutableIntStateOf(5)
    private var listener: OnSeekBarChangeListener? = null
    interface OnSeekBarChangeListener {
        fun onProgressChanged(seekBar: SettingsSeekBar?, progress: Int, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: SettingsSeekBar?)
        fun onStopTrackingTouch(seekBar: SettingsSeekBar?)
    }
    fun setOnSeekBarChangeListener(value: OnSeekBarChangeListener) { listener = value }
    fun userProgress(value: Int) {
        val next = value.coerceIn(0, max)
        if (next == progress) return
        progress = next
        listener?.onProgressChanged(this, next, true)
    }
    fun start() { listener?.onStartTrackingTouch(this) }
    fun stop() { listener?.onStopTrackingTouch(this) }
}

class AdvancedSettingsState(context: Context) {
    val settingsExperimentalRl = WgControlState()
    val dvExperimentalSwitch = WgControlState(checkable = true)
    val settingsAutoDialRl = WgControlState()
    val dvAutoDialSwitch = WgControlState(checkable = true)
    val settingsResetTourRl = WgControlState()
    val settingsResetRpnTourRl = WgControlState()
    val settingsPtModeRl = WgControlState()
    val settingsGoMaxMemoryLl = WgControlState()
    val settingsClearResidueRl = WgControlState().apply { visibility = View.GONE }
    val settingsPtModeDesc = WgControlState().apply { text = "No override" }
    val settingsResetTourDesc = WgControlState().apply { text = context.getString(R.string.tour_debug_reset_desc) }
    val settingsResetRpnTourDesc = WgControlState().apply { text = "Show the RPN onboarding tour again on next dashboard visit." }
    val goMaxMemorySeekbar = SettingsSeekBar()
    val goMaxMemoryValueTxt = WgControlState().apply { text = "1024 MB" }
    fun saveChecks(bundle: Bundle) {
        bundle.putBoolean("compose.advanced.experimental", dvExperimentalSwitch.isChecked)
        bundle.putBoolean("compose.advanced.dial", dvAutoDialSwitch.isChecked)
        bundle.putInt("compose.advanced.memory", goMaxMemorySeekbar.progress)
    }
    fun restoreChecks(bundle: Bundle) {
        if (bundle.containsKey("compose.advanced.experimental")) dvExperimentalSwitch.isChecked = bundle.getBoolean("compose.advanced.experimental")
        if (bundle.containsKey("compose.advanced.dial")) dvAutoDialSwitch.isChecked = bundle.getBoolean("compose.advanced.dial")
        if (bundle.containsKey("compose.advanced.memory")) goMaxMemorySeekbar.progress = bundle.getInt("compose.advanced.memory")
    }
}

@Composable
fun SettingsRow(
    title: String,
    description: String,
    icon: Int,
    modifier: Modifier = Modifier,
    iconAlpha: Float = 1f,
    verticalPadding: androidx.compose.ui.unit.Dp = 15.dp,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = verticalPadding), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.padding(5.dp).padding(10.dp).size(24.dp).alpha(iconAlpha))
        Column(Modifier.weight(1f)) {
            Text(title, color = themeColor(R.attr.primaryTextColor), fontSize = 14.sp)
            Text(description, Modifier.padding(top = 5.dp), color = themeColor(R.attr.secondaryTextColor), fontSize = 12.sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun AdvancedSettingsContent(b: AdvancedSettingsState) {
    SettingsPage(stringResource(R.string.lbl_network)) {
        SettingsCard {
            if (b.settingsExperimentalRl.isVisible) SettingsRow(stringResource(R.string.adv_set_experimental_title),
                stringResource(R.string.adv_set_experimental_desc), R.drawable.ic_experimental,
                onClick = { b.settingsExperimentalRl.performClick() }) {
                OriginalSwitch("activity_advanced_setting:dv_experimental_switch", b.dvExperimentalSwitch.isChecked, { b.dvExperimentalSwitch.isChecked = it })
            }
            if (b.settingsAutoDialRl.isVisible) SettingsRow(stringResource(R.string.set_auto_dial_title),
                stringResource(R.string.set_auto_dial_desc), R.drawable.ic_auto_dial, iconAlpha = .5f,
                onClick = { b.settingsAutoDialRl.performClick() }) {
                OriginalSwitch("activity_advanced_setting:dv_auto_dial_switch", b.dvAutoDialSwitch.isChecked, { b.dvAutoDialSwitch.isChecked = it })
            }
            if (b.settingsPtModeRl.isVisible) SettingsRow("Protocol Translation Mode", b.settingsPtModeDesc.text.toString(),
                R.drawable.ic_ip_network, iconAlpha = .5f, onClick = { b.settingsPtModeRl.performClick() })
            if (b.settingsGoMaxMemoryLl.isVisible) Column(Modifier.padding(vertical = 15.dp)) {
                SettingsRow("Go Max Memory Limit", "Set the maximum memory the Go engine can consume", R.drawable.ic_experimental, iconAlpha = .5f, verticalPadding = 0.dp)
                OriginalSlider("activity_advanced_setting:go_max_memory_seekbar", b.goMaxMemorySeekbar.progress.toFloat(), { b.goMaxMemorySeekbar.userProgress(it.toInt()) },
                    Modifier.padding(start = 40.dp, end = 20.dp), valueRange = 0f..5f, steps = 4,
                    onValueChangeFinished = { b.goMaxMemorySeekbar.stop() })
                Text(b.goMaxMemoryValueTxt.text.toString(), Modifier.align(Alignment.End).padding(end = 25.dp), fontSize = 12.sp)
            }
            if (b.settingsResetTourRl.isVisible) SettingsRow(stringResource(R.string.tour_debug_reset_title), b.settingsResetTourDesc.text.toString(),
                R.drawable.ic_app_info_accent, onClick = { b.settingsResetTourRl.performClick() })
            if (b.settingsResetRpnTourRl.isVisible) SettingsRow("Reset RPN Onboarding Tour", b.settingsResetRpnTourDesc.text.toString(),
                R.drawable.ic_app_info_accent, onClick = { b.settingsResetRpnTourRl.performClick() })
        }
    }
}
