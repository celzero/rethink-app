/* Copyright 2024 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.tour

import androidx.compose.ui.platform.LocalLocale
import com.celzero.bravedns.ui.compose.controls.*
import android.app.Activity
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import kotlin.math.pow
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.view.doOnLayout
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.home.HomeTourAnchors
import com.celzero.bravedns.util.Logger
import kotlinx.coroutines.*
import java.util.Locale

/** Native Compose spotlight/tooltip; the DecorView is only a platform host. */
class TourOverlayController(
    private val activity: Activity,
    private val steps: List<TourStep>,
    private val onComplete: () -> Unit,
    private val composeAnchor: (Int) -> RectF? = HomeTourAnchors.forActivity(activity)::find,
) {
    private val decorRoot = activity.window.decorView.rootView as ViewGroup
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentStepIndex by mutableIntStateOf(0)
    private var targetRect by mutableStateOf<RectF?>(null)
    private var closing by mutableStateOf(false)
    private var isAttached = false
    private var terminal = false
    private var host: ComposeView? = null
    private var positionJob: Job? = null

    fun start() {
        if (steps.isEmpty()) { onComplete(); return }
        if (isAttached) return
        isAttached = true
        host = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { RethinkTheme { TourOverlay() } }
        }.also { decorRoot.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        showStep(0)
    }

    private fun showStep(index: Int) {
        if (terminal) return
        if (index >= steps.size) { completeTour(); return }
        currentStepIndex = index
        val step = steps[index]
        positionJob?.cancel()
        positionJob = scope.launch {
            // Compose anchors may live below a scroll viewport. This uses only
            // the local UI's bring-into-view request and never runs card actions.
            HomeTourAnchors.forActivity(activity).reveal(step.targetViewId)
            yield()
            val nativeBounds = composeAnchor(step.targetViewId)
            if (nativeBounds != null && !nativeBounds.isEmpty) {
                positionOnRect(nativeBounds)
                return@launch
            }
            // Fallback is exclusively for retained platform/host UI. Never
            // create hidden proxy Views for a native Compose element.
            val target = activity.findViewById<View>(step.targetViewId)
            if (target == null) {
                Logger.w("TourOverlay", "TourOverlayController: target view ${step.targetViewId} not found, skipping step")
                showStep(index + 1)
                return@launch
            }
            target.doOnLayout {
                target.post {
                    if (isAttached && !terminal && currentStepIndex == index) positionOnView(target)
                }
            }
        }
    }

    private fun positionOnRect(windowBounds: RectF) {
        val location = IntArray(2)
        host?.getLocationInWindow(location)
        targetRect = RectF(windowBounds).apply { offset(-location[0].toFloat(), -location[1].toFloat()) }
    }

    private fun positionOnView(target: View) {
        if (target.width > 0 && target.height > 0) target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        val bounds = Rect()
        if (!target.getGlobalVisibleRect(bounds) || bounds.isEmpty) {
            Logger.w("TourOverlay", "TourOverlayController: target not visible on screen, skipping")
            showStep(currentStepIndex + 1)
            return
        }
        val screen = IntArray(2)
        val window = IntArray(2)
        target.getLocationOnScreen(screen)
        target.getLocationInWindow(window)
        positionOnRect(RectF(bounds).apply { offset((window[0] - screen[0]).toFloat(), (window[1] - screen[1]).toFloat()) })
    }

    private fun advanceOrComplete() {
        if (terminal) return
        if (currentStepIndex < steps.lastIndex) showStep(currentStepIndex + 1) else completeTour()
    }

    private fun skipTour() = completeTour()

    private fun completeTour() {
        if (terminal) return
        terminal = true
        closing = true
        scope.launch {
            delay(180)
            host?.let { decorRoot.removeView(it) }
            host = null
            isAttached = false
            onComplete()
            scope.cancel()
        }
    }

    @Composable
    private fun TourOverlay() {
        val step = steps.getOrNull(currentStepIndex) ?: return
        val target = targetRect
        val density = LocalDensity.current
        val fade = remember { Animatable(0f) }
        val movement = remember { Animatable(1f) }
        var from by remember { mutableStateOf<RectF?>(null) }
        var destination by remember { mutableStateOf<RectF?>(null) }
        LaunchedEffect(target) {
            if (target != null) {
                from = destination ?: RectF(target.centerX(), target.centerY(), target.centerX(), target.centerY())
                destination = target
                movement.snapTo(0f)
                movement.animateTo(1f, tween(220, easing = Easing { 1f - (1f - it).pow(3.6f) }))
            }
        }
        LaunchedEffect(closing, target != null) { fade.animateTo(if (closing || target == null) 0f else 1f, tween(180)) }
        val overlay = themeColor(R.attr.tourOverlayColor)
        val stroke = themeColor(R.attr.tourStrokeColor).copy(alpha = 140 / 255f)
        val gold = themeColor(R.attr.colorGolden)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            var tooltipHeight by remember { mutableIntStateOf(0) }
            val screenHeight = constraints.maxHeight
            val targetForTooltip = destination
            val top = targetForTooltip?.let { rect ->
                val side = if (step.tooltipSide == TooltipSide.AUTO) { if (screenHeight - rect.bottom >= 280) TooltipSide.BELOW else TooltipSide.ABOVE } else step.tooltipSide
                if (side == TooltipSide.BELOW) rect.bottom + 16f else (rect.top - tooltipHeight - 16f).coerceAtLeast(16f)
            } ?: 16f
            Canvas(Modifier.fillMaxSize().alpha(fade.value).pointerInput(currentStepIndex, terminal) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // Baseline consumes both cutout and outside touches and
                    // advances on touch-down. Neither invokes the target.
                    advanceOrComplete()
                    do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                }
            }) {
                val start = from
                val end = destination
                if (start == null || end == null) { drawRect(overlay); return@Canvas }
                val f = movement.value
                fun mix(a: Float, b: Float) = a + (b - a) * f
                val pad = 8.dp.toPx()
                val rect = ComposeRect(mix(start.left, end.left) - pad, mix(start.top, end.top) - pad, mix(start.right, end.right) + pad, mix(start.bottom, end.bottom) + pad)
                val spotlight = Path().apply {
                    if (step.spotlightShape == SpotlightShape.CIRCLE) {
                        val radius = maxOf(rect.width, rect.height) / 2
                        addOval(ComposeRect(rect.center.x - radius, rect.center.y - radius, rect.center.x + radius, rect.center.y + radius))
                    } else addRoundRect(RoundRect(rect, CornerRadius(16.dp.toPx())))
                }
                clipPath(spotlight, ClipOp.Difference) { drawRect(overlay) }
                if (step.isPremium) {
                    drawPath(spotlight, gold.copy(alpha = 40 / 255f), style = Stroke(6.dp.toPx()))
                    drawPath(spotlight, gold.copy(alpha = 150 / 255f), style = Stroke(2.dp.toPx()))
                }
                drawPath(spotlight, stroke, style = Stroke(1.5.dp.toPx()))
            }
            val tooltipMaxHeight = with(density) { (screenHeight - top.toInt()).coerceAtLeast(48).toDp() }
            Column(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, top.toInt()) }.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp).heightIn(max = tooltipMaxHeight)
                .onSizeChanged { tooltipHeight = it.height }.alpha(if (closing) 1f else fade.value)
                .clip(RoundedCornerShape(16.dp)).background(themeColor(R.attr.tourTooltipBackground))
                .border(if (step.isPremium) 1.5.dp else .5.dp, if (step.isPremium) gold else themeColor(R.attr.border), RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 16.dp)) {
                if (step.isPremium) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(6.dp)).background(gold.copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_rethink_plus_sparkle), null, Modifier.size(14.dp), tint = gold)
                    Text(stringResource(R.string.tour_premium_badge_label).uppercase(LocalLocale.current.platformLocale), Modifier.padding(start = 6.dp), color = gold, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tour_step_counter, step.position, step.total).uppercase(LocalLocale.current.platformLocale), Modifier.weight(1f), color = themeColor(R.attr.primaryTextColor).copy(alpha = .55f), fontSize = 11.sp)
                    IconButton(onClick = ::skipTour, modifier = Modifier.size(32.dp)) { Icon(painterResource(R.drawable.ic_cross), stringResource(R.string.tour_btn_skip), Modifier.size(24.dp), tint = themeColor(R.attr.primaryLightColorText)) }
                }
                Text(stringResource(step.titleRes), Modifier.padding(top = 8.dp), color = themeColor(R.attr.primaryTextColor), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(step.descRes), Modifier.padding(top = 6.dp), color = themeColor(R.attr.primaryTextColor).copy(alpha = .8f), fontSize = 13.sp)
                HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 2.dp), color = themeColor(R.attr.primaryTextColor).copy(alpha = .15f))
                FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OriginalButton("view_tour_tooltip:tour_skip_btn", onClick = ::skipTour) { Text(stringResource(R.string.tour_btn_skip), color = themeColor(R.attr.primaryTextColor).copy(alpha = .55f), fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        steps.indices.forEach { index -> Box(Modifier.size(6.dp).background(themeColor(R.attr.primaryTextColor).copy(alpha = if (index == currentStepIndex) 1f else .3f), CircleShape)) }
                    }
                    OriginalButton("view_tour_tooltip:tour_next_btn", onClick = ::advanceOrComplete) {
                        Text(stringResource(if (step.isLastStep) R.string.tour_btn_finish else R.string.next), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
