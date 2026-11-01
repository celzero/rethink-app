package com.celzero.bravedns.ui.compose.rpn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout

/** Android timing semantics retained, but targets are snapshot values, never legacy Views. */
class RpnPropertyAnimator(private val state: RpnControlState) {
    private val targets = linkedMapOf<String, Float>()
    private var duration = 300L
    private var delay = 0L
    private var interpolator: TimeInterpolator? = null
    private var end: (() -> Unit)? = null
    private var active: AnimatorSet? = null
    fun alpha(value: Float) = apply { targets["alpha"] = value }
    fun scaleX(value: Float) = apply { targets["scaleX"] = value }
    fun scaleY(value: Float) = apply { targets["scaleY"] = value }
    fun translationX(value: Float) = apply { targets["translationX"] = value }
    fun translationY(value: Float) = apply { targets["translationY"] = value }
    fun rotation(value: Float) = apply { targets["rotation"] = value }
    fun setDuration(value: Long) = apply { duration = value }
    fun setStartDelay(value: Long) = apply { delay = value }
    fun setInterpolator(value: TimeInterpolator) = apply { interpolator = value }
    fun withEndAction(value: () -> Unit) = apply { end = value }
    fun cancel() { active?.removeAllListeners(); active?.cancel(); active = null; end = null; targets.clear() }
    fun start() {
        active?.removeAllListeners(); active?.cancel()
        val completion = end
        val values = targets.toMap()
        targets.clear(); end = null
        active = AnimatorSet().apply {
            playTogether(values.map { (name, value) -> ObjectAnimator.ofFloat(state, name, value) })
            duration = this@RpnPropertyAnimator.duration
            startDelay = delay
            this@RpnPropertyAnimator.interpolator?.let { interpolator = it }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { active = null; completion?.invoke() }
            })
            start()
        }
    }
}

fun Modifier.rpnMotion(state: RpnControlState): Modifier = composed {
    DisposableEffect(state) {
        state.attached = true
        onDispose { state.attached = false; state.animate().cancel() }
    }
    onGloballyPositioned {
        state.width = it.size.width; state.height = it.size.height
        val p = it.positionInWindow(); state.windowX = p.x.toInt(); state.windowY = p.y.toInt()
    }.graphicsLayer {
        alpha = state.alpha; scaleX = state.scaleX; scaleY = state.scaleY
        translationX = state.translationX; translationY = state.translationY; rotationZ = state.rotation
    }
}

/** GONE does not dispose retained selected-row polling presenters. */
fun Modifier.rpnVisibility(state: RpnControlState): Modifier = this
    .graphicsLayer { alpha = if (state.visibility == android.view.View.VISIBLE) 1f else 0f }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        if (state.visibility == android.view.View.GONE) layout(0, 0) {} else
            layout(placeable.width, placeable.height) { if (state.visibility == android.view.View.VISIBLE) placeable.place(0, 0) }
    }
