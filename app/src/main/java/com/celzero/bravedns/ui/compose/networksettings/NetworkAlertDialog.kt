/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.networksettings

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle

/** Native confirmation content; action dispatch precedes dismissal, as in the original dialogs. */
class NetworkAlertDialogBuilder(private val context: Context, private val theme: Int = 0) {
    private var content: (@Composable () -> Unit)? = null
    private var policyAdapter: Pair<NetworkPolicyAdapter, (DialogInterface, Int) -> Unit>? = null
    fun setContent(value: @Composable () -> Unit) = apply { content = value }
    fun setAdapter(value: NetworkPolicyAdapter, action: (DialogInterface, Int) -> Unit) = apply { policyAdapter = value to action }
    private var title: CharSequence = ""
    private var message: CharSequence = ""
    private var cancelable = true
    private var maxHeightFraction: Float? = null
    private var icon: Int? = null
    private var rows: List<CharSequence> = emptyList()
    private var choices: Triple<Array<String>, Int, (DialogInterface, Int) -> Unit>? = null
    private var positive: Pair<CharSequence, (DialogInterface, Int) -> Unit>? = null
    private var negative: Pair<CharSequence, (DialogInterface, Int) -> Unit>? = null
    private var neutral: Pair<CharSequence, (DialogInterface, Int) -> Unit>? = null
    private var cancel: ((DialogInterface) -> Unit)? = null
    fun setTitle(value: CharSequence) = apply { title = value }
    fun setTitle(value: Int) = setTitle(context.getString(value))
    fun setMessage(value: CharSequence) = apply { message = value }
    fun setMessage(value: Int) = setMessage(context.getString(value))
    fun setIcon(value: Int) = apply { icon = value }
    fun setCancelable(value: Boolean) = apply { cancelable = value }
    /** Cap long lists while keeping the title and actions outside the scrolling body. */
    fun setMaxHeightFraction(value: Float) = apply {
        require(value > 0f && value <= 1f)
        maxHeightFraction = value
    }
    fun setOnCancelListener(value: (DialogInterface) -> Unit) = apply { cancel = value }
    fun setPositiveButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { positive = value to action }
    fun setPositiveButton(value: Int, action: (DialogInterface, Int) -> Unit) = setPositiveButton(context.getString(value), action)
    fun setNegativeButton(value: CharSequence, action: ((DialogInterface, Int) -> Unit)?) = apply { negative = value to (action ?: { _, _ -> }) }
    fun setNegativeButton(value: Int, action: ((DialogInterface, Int) -> Unit)?) = setNegativeButton(context.getString(value), action)
    fun setNeutralButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { neutral = value to action }
    fun setNeutralButton(value: Int, action: (DialogInterface, Int) -> Unit) = setNeutralButton(context.getString(value), action)
    fun setItems(value: Array<String>, unused: Nothing?) = apply { rows = value.toList() }
    fun setSingleChoiceItems(value: Array<String>, selected: Int, action: (DialogInterface, Int) -> Unit) = apply { choices = Triple(value, selected, action) }
    fun create(): NetworkNativeDialog {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
        val resolvedTheme = if (theme != 0) theme else {
            val value = android.util.TypedValue()
            if (context.theme.resolveAttribute(com.google.android.material.R.attr.materialAlertDialogTheme, value, true) && value.resourceId != 0) value.resourceId
            else {
                context.theme.resolveAttribute(androidx.appcompat.R.attr.alertDialogTheme, value, true)
                value.resourceId
            }
        }
        val dialog = NetworkNativeDialog(context, resolvedTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { cancel?.invoke(it) }
        var selectedChoice by mutableIntStateOf(choices?.second ?: -1)
        var terminalActionDispatched = false
        fun dispatchChoice(action: (DialogInterface, Int) -> Unit, index: Int) {
            if (terminalActionDispatched || !dialog.isShowing) return
            selectedChoice = index
            action(dialog, index)
            // A single-choice list does not dismiss by itself. Respect the caller's decision.
            if (!dialog.isShowing) terminalActionDispatched = true
        }
        fun dispatchButton(action: (DialogInterface, Int) -> Unit, which: Int) {
            if (terminalActionDispatched || !dialog.isShowing) return
            terminalActionDispatched = true
            action(dialog, which)
            dialog.dismiss()
        }
        val customOnly = title.isEmpty() && message.isEmpty() && rows.isEmpty() && choices == null && policyAdapter == null && positive == null && negative == null && neutral == null
        dialog.setWgContent(activity) {
            val heightLimit = maxHeightFraction?.let {
                Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * it).dp)
            } ?: Modifier
            Surface(modifier = heightLimit.testTag("network.dialog"), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(if (customOnly) 0.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (customOnly) 0.dp else 16.dp)) {
                    icon?.let { Icon(painterResource(it), null) }
                    if (title.isNotEmpty()) Text(networkAnnotatedText(title), style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (message.isNotEmpty()) Text(networkAnnotatedText(message))
                        rows.forEach { Text(networkAnnotatedText(it)) }
                        choices?.let { (labels, selected, action) ->
                            labels.forEachIndexed { index, label ->
                                Row(Modifier.fillMaxWidth().selectable(selected = index == selectedChoice, role = Role.RadioButton, onClick = { dispatchChoice(action, index) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(index == selectedChoice, onClick = null)
                                    Text(label)
                                }
                            }
                        }
                        policyAdapter?.let { (adapter, action) ->
                            adapter.items.forEachIndexed { index, item ->
                                Row(Modifier.fillMaxWidth().selectable(selected = index == adapter.selected(), role = Role.RadioButton,
                                    onClick = { dispatchButton(action, index) }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OriginalRadioButton("item_network_policy:radioButton", index == adapter.selected(), onClick = null)
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(item.first, style = utilityTextStyle(size = 14f, weight = android.graphics.Typeface.BOLD), color = themeColor(R.attr.primaryTextColor))
                                        Text(item.second, Modifier.padding(top = 2.dp), style = utilityTextStyle(size = 11f), color = themeColor(R.attr.primaryLightColorText))
                                    }
                                }
                            }
                        }
                        content?.invoke()
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        listOf(neutral to DialogInterface.BUTTON_NEUTRAL, negative to DialogInterface.BUTTON_NEGATIVE,
                            positive to DialogInterface.BUTTON_POSITIVE).forEach { (button, which) ->
                            button?.let { (label, action) ->
                                val color = if (theme == R.style.App_Dialog_NoDim && which != DialogInterface.BUTTON_POSITIVE) themeColor(R.attr.secondaryTextColor) else MaterialTheme.colorScheme.primary
                                OriginalButton(when (which) { android.content.DialogInterface.BUTTON_NEGATIVE -> "@dialog_negative"; android.content.DialogInterface.BUTTON_NEUTRAL -> "@dialog_neutral"; else -> "@dialog_positive" }, { dispatchButton(action, which) }, enabled = dialog.getButton(which).isEnabled) { Text(label.toString()) }
                            }
                        }
                    }
                }
            }
        }
        if (theme == R.style.App_Dialog_NoDim) {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setDimAmount(0f)
        }
        UIUtils.capDialogWidth(dialog)
        return dialog
    }
    fun show(): Dialog = create().also { it.show() }
}

/** Window/lifecycle host only. Every visible child is Compose. */
class NetworkNativeDialog(context: Context, theme: Int) : Dialog(context, theme) {
    private val buttons = mutableMapOf<Int, WgControlState>()
    fun getButton(which: Int): WgControlState = buttons.getOrPut(which) { WgControlState(context) }
}
class NetworkPolicyAdapter(val items: List<Pair<String, String>>, val selected: () -> Int) {
    fun notifyDataSetChanged() = Unit // The baseline adapter closes after row selection.
}
@Composable
fun NetworkCheckRow(control: WgControlState) {
    Row(Modifier.fillMaxWidth().clickable(enabled = control.isEnabled) { control.performClick() }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(control.isChecked, { control.performClick() }, enabled = control.isEnabled)
        Text(networkAnnotatedText(control.text), Modifier.weight(1f))
    }
}
