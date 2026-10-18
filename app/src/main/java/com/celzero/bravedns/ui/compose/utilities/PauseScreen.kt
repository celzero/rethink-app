/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.view.MotionEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged

@Composable fun PauseScreen(ui: PauseUiState) {
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UtilityText(ui.fhsTitleRethink, Modifier.padding(top = 10.dp).semantics { heading() })
            UtilityText(ui.fhsTitleRethinkDesc)
            Spacer(Modifier.height(64.dp))
            UtilityText(ui.pacTimerText)
            Text(ui.pacTimer.text.toString(), Modifier.testTag(ui.pacTimer.tag).semantics { liveRegion = LiveRegionMode.Polite }, style = utilityTextStyle(ui.pacTimer.textAppearance, 75f), color = themeColor(R.attr.secondaryTextColor), textAlign = TextAlign.Center)
            Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                PauseRepeatControl(ui.pacMinusIv, "Decrease pause duration", 48)
                IconButton({ ui.pacStopIv.performClick() }, Modifier.size(80.dp).testTag(ui.pacStopIv.tag)) { UtilityImage(ui.pacStopIv, Modifier.size(80.dp), stringResource(R.string.notification_action_resume_vpn), tagged = false) }
                PauseRepeatControl(ui.pacPlusIv, "Increase pause duration", 48)
            }
            UtilityText(ui.pacTimerDesc, Modifier.padding(top = 16.dp))
        }
    }
}
/** Baseline long-click returns false: a completed long hold also dispatches the release click. */
@Composable private fun PauseRepeatControl(control: UtilityControl, label: String, size: Int) {
    DisposableEffect(control) { onDispose { control.dispatchTouch(MotionEvent.ACTION_CANCEL) } }
    var hadFocus by remember { mutableStateOf(false) }
    Box(Modifier.size(size.dp).testTag(control.tag).semantics {
        role = Role.Button; contentDescription = label
        onClick { control.performClick(); true }
        onLongClick { control.performLongClick(); control.dispatchTouch(MotionEvent.ACTION_UP); control.performClick(); true }
    }.onKeyEvent { event ->
        if (event.key != Key.Enter && event.key != Key.Spacebar && event.key != Key.DirectionCenter) false
        else { if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1) control.performLongClick(); if (event.type == KeyEventType.KeyUp) { control.dispatchTouch(MotionEvent.ACTION_UP); control.performClick() }; true }
    }.onFocusChanged { state ->
        if (hadFocus && !state.isFocused) control.dispatchTouch(MotionEvent.ACTION_CANCEL)
        hadFocus = state.isFocused
    }.focusable().pointerInput(control) {
        detectTapGestures(onLongPress = { control.performLongClick() }, onPress = {
            val released = tryAwaitRelease()
            control.dispatchTouch(if (released) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL)
            if (released) control.performClick()
        })
    }, contentAlignment = Alignment.Center) { UtilityImage(control, Modifier.fillMaxSize(), tagged = false) }
}
