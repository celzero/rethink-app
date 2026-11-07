/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.celzero.bravedns.ui.compose.NoDimDialogWindow

/** A transient, per-dialog terminal gate. It never changes a VPN or permission policy. */
class HomeDialog(
    val title: CharSequence,
    val message: CharSequence,
    val cancelable: Boolean,
    val buttons: List<HomeDialogAction>,
    private val onRemove: (HomeDialog) -> Unit,
) {
    private var terminal = false
    fun choose(action: HomeDialogAction) {
        if (terminal || action !in buttons) return
        terminal = true
        // Material's callback runs before the automatic dismissal. A callback may
        // open a replacement dialog, so remove this exact instance afterwards.
        try { action.callback(this, action.which) } finally { onRemove(this) }
    }
    fun cancel() {
        if (!cancelable || terminal) return
        terminal = true
        onRemove(this)
    }
}

data class HomeDialogAction(val label: CharSequence, val which: Int, val callback: (HomeDialog, Int) -> Unit)

/** Preserves the controller's existing dialog construction/callback order. */
class HomeDialogBuilder(private val context: Context, private val presentation: HomePresentation) {
    private var title: CharSequence = ""
    private var message: CharSequence = ""
    private var cancelable = true
    private val actions = mutableListOf<HomeDialogAction>()
    fun setTitle(value: Int) { title = context.getString(value) }
    fun setTitle(value: CharSequence) { title = value }
    fun setMessage(value: Int) { message = context.getString(value) }
    fun setMessage(value: CharSequence) { message = value }
    fun setCancelable(value: Boolean) { cancelable = value }
    fun setPositiveButton(value: Int, callback: (HomeDialog, Int) -> Unit) { actions += HomeDialogAction(context.getString(value), -1, callback) }
    fun setNegativeButton(value: Int, callback: (HomeDialog, Int) -> Unit) { actions += HomeDialogAction(context.getString(value), -2, callback) }
    fun setNeutralButton(value: Int, callback: (HomeDialog, Int) -> Unit) { actions += HomeDialogAction(context.getString(value), -3, callback) }
    fun show() {
        presentation.dialogs += HomeDialog(title, message, cancelable, actions.toList()) { presentation.dialogs.remove(it) }
    }
}

@Composable
fun HomeDialogs(presentation: HomePresentation) {
    presentation.dialogs.toList().forEach { dialog ->
        AlertDialog(
            onDismissRequest = dialog::cancel,
            properties = DialogProperties(dismissOnBackPress = dialog.cancelable, dismissOnClickOutside = dialog.cancelable),
            title = { Text(homeAnnotatedText(dialog.title)) },
            text = { Text(homeAnnotatedText(dialog.message)) },
            confirmButton = {
                NoDimDialogWindow()
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dialog.buttons.sortedBy { when (it.which) { -3 -> 0; -2 -> 1; else -> 2 } }.forEach { action ->
                        OriginalButton(when (action.which) { -2 -> "@dialog_negative"; -3 -> "@dialog_neutral"; else -> "@dialog_positive" }, onClick = { dialog.choose(action) }) { Text(action.label.toString()) }
                    }
                }
            },
        )
    }
}
