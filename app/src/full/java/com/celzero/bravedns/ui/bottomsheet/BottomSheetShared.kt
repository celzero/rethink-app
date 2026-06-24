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
package com.celzero.bravedns.ui.bottomsheet

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkFilterChip
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkModalBottomSheet
import com.celzero.bravedns.ui.compose.theme.RethinkTwoOptionSegmentedRow
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RuleSheetChipColors(
    val neutralText: Color,
    val neutralBg: Color,
    val negativeText: Color,
    val negativeBg: Color,
    val positiveText: Color,
    val positiveBg: Color
)

data class RuleSheetChipOption(
    val label: String,
    val selected: Boolean,
    val selectedText: Color,
    val selectedContainer: Color,
    val onClick: () -> Unit
)

val RuleSheetBottomPaddingWithActions: Dp = Dimensions.spacing3xl + Dimensions.spacingMd
val RuleSheetBottomPaddingCompact: Dp = Dimensions.spacing2xl + Dimensions.spacingSm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSheetModal(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    RethinkModalBottomSheet(
        onDismissRequest = onDismissRequest,
        contentPadding = PaddingValues(0.dp),
        verticalSpacing = 0.dp,
        includeBottomSpacer = false,
        content = content
    )
}

@Composable
fun RuleSheetLayout(
    modifier: Modifier = Modifier,
    bottomPadding: Dp,
    verticalSpacing: Dp = Dimensions.spacingMd,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        content()
    }
}

@Composable
fun RuleSheetLabeledControlRow(
    label: @Composable () -> Unit,
    control: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    labelWeight: Float = 1f,
    controlWeight: Float = 1f,
    horizontalPadding: Dp = Dimensions.spacingMd,
    spacing: Dp = Dimensions.spacingSmMd,
    controlAlignment: Alignment = Alignment.CenterEnd
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        Box(
            modifier = Modifier.weight(if (control == null) 1f else labelWeight),
            contentAlignment = Alignment.CenterStart
        ) {
            label()
        }
        if (control != null) {
            Box(
                modifier = Modifier.weight(controlWeight),
                contentAlignment = controlAlignment
            ) {
                control()
            }
        }
    }
}

@Composable
fun RuleSheetTextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    label: (@Composable (() -> Unit))? = null,
    placeholder: (@Composable (() -> Unit))? = null,
    fieldWeight: Float = 1f,
    spacing: Dp = Dimensions.spacingSm,
    trailingTopPadding: Dp = Dimensions.spacingMd,
    trailing: (@Composable (() -> Unit))? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Top
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(fieldWeight),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            label = label,
            placeholder = placeholder
        )
        if (trailing != null) {
            Box(
                modifier = Modifier.padding(top = trailingTopPadding),
                contentAlignment = Alignment.Center
            ) {
                trailing()
            }
        }
    }
}

@Composable
fun RuleSheetDualTextFieldRow(
    primaryValue: String,
    onPrimaryValueChange: (String) -> Unit,
    secondaryValue: String,
    onSecondaryValueChange: (String) -> Unit,
    primaryLabel: @Composable (() -> Unit),
    secondaryLabel: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primaryWeight: Float = 2f,
    secondaryWeight: Float = 1f,
    spacing: Dp = Dimensions.spacingSm,
    primaryKeyboardType: KeyboardType = KeyboardType.Text,
    secondaryKeyboardType: KeyboardType = KeyboardType.Number
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        OutlinedTextField(
            value = primaryValue,
            onValueChange = onPrimaryValueChange,
            modifier = Modifier.weight(primaryWeight),
            singleLine = true,
            label = primaryLabel,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = primaryKeyboardType)
        )
        OutlinedTextField(
            value = secondaryValue,
            onValueChange = onSecondaryValueChange,
            modifier = Modifier.weight(secondaryWeight),
            singleLine = true,
            label = secondaryLabel,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = secondaryKeyboardType)
        )
    }
}

@Composable
fun rememberRuleSheetChipColors(): RuleSheetChipColors {
    return RuleSheetChipColors(
        neutralText = MaterialTheme.colorScheme.onSurfaceVariant,
        neutralBg = MaterialTheme.colorScheme.surfaceVariant,
        negativeText = MaterialTheme.colorScheme.error,
        negativeBg = MaterialTheme.colorScheme.errorContainer,
        positiveText = MaterialTheme.colorScheme.tertiary,
        positiveBg = MaterialTheme.colorScheme.tertiaryContainer
    )
}

@Composable
fun RuleSheetAppHeader(
    appName: String?,
    appIcon: Drawable?,
    modifier: Modifier = Modifier,
    iconSize: Dp = Dimensions.iconSizeSm,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    horizontalPadding: Dp = Dimensions.screenPaddingHorizontal,
    onClick: (() -> Unit)? = null
) {
    if (appName.isNullOrBlank()) return

    val clickableModifier =
        if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        appIcon?.let { icon ->
            val painter = rememberDrawablePainter(icon)
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize)
                )
                Spacer(modifier = Modifier.width(Dimensions.spacingSmMd))
            }
        }
        Text(
            text = appName,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun RuleSheetSummaryPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    fontWeight: FontWeight = FontWeight.SemiBold,
    horizontalPadding: Dp = Dimensions.spacingMd,
    verticalPadding: Dp = Dimensions.spacingSm
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor
    ) {
        Text(
            text = text,
            style = textStyle,
            color = textColor,
            fontWeight = fontWeight,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding)
        )
    }
}

@Composable
fun RuleSheetFlagDestinationRow(
    flag: String,
    destination: String,
    modifier: Modifier = Modifier,
    destinationStyle: TextStyle = MaterialTheme.typography.titleLarge,
    destinationFontFamily: FontFamily = FontFamily.Monospace,
    horizontalPadding: Dp = Dimensions.spacingMd
) {
    if (destination.isBlank()) return

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (flag.isNotBlank()) {
            Text(
                text = flag,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(end = Dimensions.spacingSm)
            )
        }
        SelectionContainer {
            Text(
                text = destination,
                style = destinationStyle,
                fontFamily = destinationFontFamily,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun RuleSheetSplitDetailsRow(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Dimensions.spacingXl,
    dividerColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dividerHeight: Dp = 32.dp,
    leftContent: @Composable ColumnScope.() -> Unit,
    rightContent: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
            content = leftContent
        )
        Spacer(modifier = Modifier.width(Dimensions.spacingSmMd))
        Box(modifier = Modifier.width(1.dp).height(dividerHeight).background(dividerColor))
        Spacer(modifier = Modifier.width(Dimensions.spacingSmMd))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            content = rightContent
        )
    }
}

@Composable
fun TrustBlockToggleStrip(
    isTrustSelected: Boolean,
    isBlockSelected: Boolean,
    onTrustClick: () -> Unit,
    onBlockClick: () -> Unit,
    iconSize: Dp = 28.dp,
    spacingBefore: Dp = Dimensions.spacingLg,
    spacingBetween: Dp = Dimensions.spacingMd
) {
    val trustIcon = if (isTrustSelected) R.drawable.ic_trust_accent else R.drawable.ic_trust
    val blockIcon = if (isBlockSelected) R.drawable.ic_block_accent else R.drawable.ic_block

    Spacer(modifier = Modifier.width(spacingBefore))
    Icon(
        painter = painterResource(id = trustIcon),
        contentDescription = null,
        modifier = Modifier.size(iconSize).clickable(onClick = onTrustClick)
    )
    Spacer(modifier = Modifier.width(spacingBetween))
    Icon(
        painter = painterResource(id = blockIcon),
        contentDescription = null,
        modifier = Modifier.size(iconSize).clickable(onClick = onBlockClick)
    )
}

@Composable
fun RuleSheetSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Dimensions.screenPaddingHorizontal
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding)
    )
}

@Composable
fun RuleSheetSupportingText(
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Dimensions.screenPaddingHorizontal
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding)
    )
}

@Composable
fun RuleSheetDeleteAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimensions.screenPaddingHorizontal),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text(text = stringResource(R.string.lbl_delete))
        }
    }
}

@Composable
fun RuleSheetSelectionValue(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimensions.screenPaddingHorizontal)
        )
    }
}

@Composable
fun RuleSheetTrustBlockRow(
    value: String,
    isTrustSelected: Boolean,
    isBlockSelected: Boolean,
    onTrustClick: () -> Unit,
    onBlockClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueTextStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimensions.screenPaddingHorizontal,
                    vertical = Dimensions.spacingSm
                ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = valueTextStyle,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TrustBlockToggleStrip(
            isTrustSelected = isTrustSelected,
            isBlockSelected = isBlockSelected,
            onTrustClick = onTrustClick,
            onBlockClick = onBlockClick
        )
    }
}

@Composable
fun RuleSheetChipOptionsRow(
    options: List<RuleSheetChipOption>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimensions.screenPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
    ) {
        options.forEach { option ->
            Box(modifier = Modifier.weight(1f).widthIn(min = 0.dp)) {
                RuleSheetFilterChip(
                    label = option.label,
                    selected = option.selected,
                    selectedText = option.selectedText,
                    selectedContainer = option.selectedContainer
                ) {
                    option.onClick()
                }
            }
        }
    }
}

@Composable
fun RuleSheetDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
        confirmText = stringResource(R.string.lbl_delete),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        isConfirmDestructive = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSheetFilterChip(
    label: String,
    selected: Boolean,
    selectedText: Color,
    selectedContainer: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    RethinkFilterChip(
        label = label,
        selected = selected,
        onClick = onClick,
        selectedLabelColor = selectedText,
        selectedContainerColor = selectedContainer,
        modifier = modifier,
        minHeight = Dimensions.touchTargetSm
    )
}

@Composable
fun RuleSheetModeToggle(
    autoLabel: String,
    manualLabel: String,
    isAutoSelected: Boolean,
    onAutoClick: () -> Unit,
    onManualClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    RethinkTwoOptionSegmentedRow(
        leftLabel = autoLabel,
        rightLabel = manualLabel,
        leftSelected = isAutoSelected,
        onLeftClick = onAutoClick,
        onRightClick = onManualClick,
        modifier = modifier,
        minHeight = Dimensions.touchTargetSm
    )
}

suspend fun fetchRuleSheetAppIdentity(
    context: Context,
    uid: Int
): Pair<List<String>, Drawable?> {
    val appNames = FirewallManager.getAppNamesByUid(uid)
    val packageName = appNames.firstOrNull()?.let { FirewallManager.getPackageNameByAppName(it) }
    val icon =
        if (packageName.isNullOrEmpty()) {
            null
        } else {
            Utilities.getIcon(context, packageName)
        }

    return appNames to icon
}

fun formatRuleSheetAppName(context: Context, appNames: List<String>): String? {
    return when {
        appNames.isEmpty() -> null
        appNames.size >= 2 ->
            context.getString(
                R.string.ctbs_app_other_apps,
                appNames[0],
                appNames.size.minus(1).toString()
            )
        else -> appNames[0]
    }
}

fun formatCustomRuleSheetAppName(context: Context, uid: Int, appNames: List<String>): String {
    return when {
        uid == UID_EVERYBODY ->
            context.getString(R.string.firewall_act_universal_tab)
        appNames.isEmpty() ->
            context.getString(R.string.network_log_app_name_unknown) + " ($uid)"
        appNames.size >= 2 ->
            context.getString(
                R.string.ctbs_app_other_apps,
                appNames[0],
                appNames.size.minus(1).toString()
            )
        else -> appNames[0]
    }
}

fun logFirewallRuleChange(
    eventLogger: EventLogger,
    title: String,
    details: String,
    tag: String? = null
) {
    eventLogger.log(
        EventType.FW_RULE_MODIFIED,
        Severity.LOW,
        title,
        EventSource.UI,
        false,
        details
    )
    tag?.let { Napier.v("$it $details") }
}

fun <T> launchRuleMutation(
    scope: CoroutineScope,
    mutation: suspend () -> T,
    onUpdated: (T) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val result = mutation()
        withContext(Dispatchers.Main) {
            onUpdated(result)
        }
    }
}
