/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkMultiActionDialog

@Composable
internal fun HomeConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    isConfirmDestructive: Boolean = false
) {
    RethinkConfirmDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        message = message,
        confirmText = confirmText,
        dismissText = dismissText,
        onConfirm = onConfirm,
        onDismiss = onDismiss ?: onDismissRequest,
        isConfirmDestructive = isConfirmDestructive
    )
}

@Composable
internal fun HomeAlwaysOnStopDialog(
    title: String,
    message: String,
    stopText: String,
    openSettingsText: String,
    cancelText: String,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit
) {
    RethinkMultiActionDialog(
        onDismissRequest = {},
        title = title,
        message = message,
        primaryText = stopText,
        onPrimary = onStop,
        secondaryText = openSettingsText,
        onSecondary = onOpenSettings,
        tertiaryText = cancelText,
        onTertiary = onCancel
    )
}

@Composable
internal fun HomeStatsDialog(
    title: String,
    displayText: String,
    dismissText: String,
    copyText: String,
    onDismissRequest: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    RethinkMultiActionDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = {
            SelectionContainer {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(Dimensions.spacingSm)
                ) {
                    Text(text = displayText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        primaryText = dismissText,
        onPrimary = onDismiss,
        secondaryText = copyText,
        onSecondary = onCopy
    )
}

@Composable
internal fun HomeNewFeaturesDialog(
    title: String,
    dismissText: String,
    contactText: String,
    onDismissRequest: () -> Unit,
    onDismiss: () -> Unit,
    onContact: () -> Unit,
    content: @Composable () -> Unit
) {
    RethinkMultiActionDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = content,
        primaryText = dismissText,
        onPrimary = onDismiss,
        secondaryText = contactText,
        onSecondary = onContact
    )
}
