/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.about

import com.celzero.bravedns.ui.compose.controls.*
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import com.celzero.bravedns.util.UIUtils

/** Terminal button dispatches are action-before-dismiss and protected against repeated taps. */
class AboutDialogActionGate {
    private var terminal = false
    fun dispatch(dialog: DialogInterface, which: Int, action: (DialogInterface, Int) -> Unit) {
        if (terminal) return
        terminal = true
        action(dialog, which)
        dialog.dismiss()
    }
}

/** Platform Dialog owns the window/back/cancel lifecycle; every pixel is native Compose. */
class AboutDialogBuilder(private val context: Context, private val theme: Int = R.style.App_Dialog_NoDim) {
    private var title: CharSequence = ""
    private var message: CharSequence = ""
    private var icon: Int? = null
    private var cancelable = true
    private var closeButton = false
    private var content: (@Composable () -> Unit)? = null
    private val buttons = linkedMapOf<Int, Pair<CharSequence, (DialogInterface, Int) -> Unit>>()
    fun setTitle(value: CharSequence) = apply { title = value }
    fun setTitle(value: Int) = setTitle(context.getString(value))
    fun setMessage(value: CharSequence) = apply { message = value }
    fun setMessage(value: Int) = setMessage(context.getString(value))
    fun setIcon(value: Int) = apply { icon = value }
    fun setCloseButton() = apply { closeButton = true }
    fun setCancelable(value: Boolean) = apply { cancelable = value }
    fun setContent(value: @Composable () -> Unit) = apply { content = value }
    fun setPositiveButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { buttons[DialogInterface.BUTTON_POSITIVE] = value to action }
    fun setPositiveButton(value: Int, action: (DialogInterface, Int) -> Unit) = setPositiveButton(context.getString(value), action)
    fun setNegativeButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { buttons[DialogInterface.BUTTON_NEGATIVE] = value to action }
    fun setNegativeButton(value: Int, action: (DialogInterface, Int) -> Unit) = setNegativeButton(context.getString(value), action)
    fun setNeutralButton(value: CharSequence, action: (DialogInterface, Int) -> Unit) = apply { buttons[DialogInterface.BUTTON_NEUTRAL] = value to action }
    fun setNeutralButton(value: Int, action: (DialogInterface, Int) -> Unit) = setNeutralButton(context.getString(value), action)
    fun create(): Dialog {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().first()
        val dialog = Dialog(context, theme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        val gate = AboutDialogActionGate()
        dialog.setWgContent(activity) {
            AboutDialogContent(title, message, icon, content, buttons.mapValues { it.value.first }, closeAction = if (closeButton) ({ dialog.dismiss() }) else null) { which ->
                if (dialog.isShowing) gate.dispatch(dialog, which, buttons.getValue(which).second)
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

@Composable
fun AboutDialogContent(title: CharSequence, message: CharSequence = "", icon: Int? = null,
    content: (@Composable () -> Unit)? = null, buttons: Map<Int, CharSequence>, closeAction: (() -> Unit)? = null, onButton: (Int) -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (closeAction != null) IconButton(closeAction, Modifier.align(Alignment.End).size(30.dp)) {
                com.celzero.bravedns.ui.compose.utilities.utilityImage(android.R.drawable.ic_menu_close_clear_cancel, Modifier.size(30.dp), stringResource(R.string.close))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (closeAction != null) Arrangement.Center else Arrangement.Start) {
                icon?.let { Icon(painterResource(it), null, Modifier.padding(end = 15.dp)) }
                Text(title.toString(), style = MaterialTheme.typography.headlineSmall,
                    textAlign = if (closeAction != null) TextAlign.Center else TextAlign.Start)
            }
            Column(Modifier.weight(1f, fill = false)) {
                if (message.isNotEmpty()) AboutRichTextContent(message)
                content?.invoke()
            }
            if (buttons.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                listOf(DialogInterface.BUTTON_NEUTRAL, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_POSITIVE).forEach { which ->
                    buttons[which]?.let { label ->
                        OriginalButton(when (which) { android.content.DialogInterface.BUTTON_NEGATIVE -> "@dialog_negative"; android.content.DialogInterface.BUTTON_NEUTRAL -> "@dialog_neutral"; else -> "@dialog_positive" }, { onButton(which) }) { Text(label.toString()) }
                    }
                }
            }
        }
    }
}
