/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import android.view.WindowManager
import androidx.annotation.AttrRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.google.android.material.R as MaterialR

@Composable
fun SettingsPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Text(title, Modifier.padding(start = 60.dp, top = 15.dp, bottom = 10.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Retains the current MaterialCardView style rather than substituting M3 defaults. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 5.dp,
    cornerRadius: Dp? = null,
    @AttrRes styleAttribute: Int = MaterialR.attr.materialCardViewStyle,
    containerColor: Color? = null,
    contentColor: Color? = null,
    horizontalContentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    com.celzero.bravedns.ui.compose.controls.OriginalCardSurface(
        modifier = modifier.fillMaxWidth(), styleAttribute = styleAttribute,
        cornerRadius = cornerRadius, containerColor = containerColor, contentColor = contentColor,
    ) {
        Column(Modifier.padding(horizontal = horizontalContentPadding, vertical = contentPadding), content = content)
    }
}

/** Use inside an existing Compose Dialog to retain the baseline no-dim window. */
@Composable
fun NoDimDialogWindow() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
    }
}

@Composable
fun NoDimDialogWindow(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest, properties = properties) {
        NoDimDialogWindow()
        content()
    }
}
