package com.celzero.bravedns.ui.compose.rpn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Path
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.custom.EmbeddedDolphinContent

class RpnSignatureState {
    var signature by mutableStateOf<EmbeddedDolphinContent.Signature?>(null)
        private set
    var entrancePlayed = false
    internal val dolphin = RpnControlState().apply { alpha = 0f }
    internal val quote = RpnControlState().apply { alpha = 0f }
    internal var animation: AnimatorSet? = null
    internal var cycling = false
    internal fun settle() {
        dolphin.translationX = 0f; dolphin.translationY = 0f; dolphin.rotation = 0f
        dolphin.scaleX = 1f; dolphin.scaleY = 1f; dolphin.alpha = .92f
        quote.translationY = 0f; quote.alpha = .55f
    }
    internal fun retire() {
        animation?.removeAllListeners(); animation?.cancel(); animation = null
        cycling = false; settle()
    }
    fun setContent(value: EmbeddedDolphinContent.Signature) {
        if (entrancePlayed) retire()
        signature = value
    }
    fun setContentForFailure() = setContent(EmbeddedDolphinContent.randomFailure())
    fun next() { signature?.let { setContent(EmbeddedDolphinContent.next(it)) } }
    internal fun swap(value: EmbeddedDolphinContent.Signature) { signature = value }
}

/** Native artwork and text. Identical entrance/cycle timing, with no embedded custom View. */
@Composable
fun RpnSignature(state: RpnSignatureState, modifier: Modifier = Modifier) {
    val signature = state.signature ?: return
    val context = LocalContext.current
    val host = LocalView.current
    val density = LocalDensity.current.density
    var becameVisible by remember(state) { mutableStateOf(false) }
    fun reduced() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    LaunchedEffect(becameVisible) {
        if (!becameVisible || state.entrancePlayed) return@LaunchedEffect
        state.entrancePlayed = true
        if (reduced()) { state.settle(); return@LaunchedEffect }
        state.quote.translationY = 4 * density
        val path = Path().apply { moveTo(-14*density, -16*density); quadTo(-14*density*.35f, -16*density*.45f, 0f, 0f) }
        val swim = ObjectAnimator.ofFloat(state.dolphin, "translationX", "translationY", path).apply {
            duration = 520; interpolator = PathInterpolator(.2f, .7f, .3f, 1f)
        }
        val fade = ObjectAnimator.ofFloat(state.dolphin, "alpha", 0f, .92f).apply { duration = 220 }
        val tilt = ObjectAnimator.ofPropertyValuesHolder(state.dolphin, keys("rotation", 0f to 8f, .65f to -2.5f, 1f to 0f)).apply { duration = 520 }
        val scale = ObjectAnimator.ofPropertyValuesHolder(state.dolphin,
            keys("scaleX", 0f to .88f, .7f to 1.03f, 1f to 1f), keys("scaleY", 0f to .88f, .7f to 1.03f, 1f to 1f)).apply { duration = 520 }
        val quoteIn = AnimatorSet().apply {
            startDelay = 360; duration = 240; interpolator = DecelerateInterpolator(1.2f)
            playTogether(ObjectAnimator.ofFloat(state.quote, "alpha", 0f, .55f), ObjectAnimator.ofFloat(state.quote, "translationY", 4*density, 0f))
        }
        state.animation = AnimatorSet().apply {
            playTogether(swim, fade, tilt, scale, quoteIn)
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { state.animation = null; state.settle() } })
            start()
        }
    }
    DisposableEffect(state) { onDispose { state.retire() } }
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 10.dp).onGloballyPositioned {
        val rect = it.boundsInWindow()
        if (rect.height > 0 && rect.bottom > 0 && rect.top < host.height) becameVisible = true
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(signature.image), null, Modifier.size(48.dp, 40.dp).rpnMotion(state.dolphin).clickable {
            if (state.cycling) return@clickable
            val next = EmbeddedDolphinContent.next(state.signature ?: return@clickable)
            if (reduced()) { state.setContent(next); return@clickable }
            state.entrancePlayed = true; state.retire(); state.cycling = true
            val out = AnimatorSet().apply {
                duration = 140; interpolator = DecelerateInterpolator(1.5f)
                playTogether(ObjectAnimator.ofFloat(state.dolphin, "translationY", 0f, 6*density), ObjectAnimator.ofFloat(state.dolphin, "rotation", 0f, -6f),
                    ObjectAnimator.ofFloat(state.quote, "alpha", state.quote.alpha, 0f), ObjectAnimator.ofFloat(state.quote, "translationY", 0f, 2*density))
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { state.swap(next) } })
            }
            val back = AnimatorSet().apply {
                duration = 200; interpolator = DecelerateInterpolator(1.2f)
                playTogether(ObjectAnimator.ofFloat(state.dolphin, "translationY", 6*density, 0f),
                    ObjectAnimator.ofPropertyValuesHolder(state.dolphin, keys("rotation", 0f to -6f, .75f to 2f, 1f to 0f)),
                    ObjectAnimator.ofFloat(state.quote, "alpha", 0f, .55f), ObjectAnimator.ofFloat(state.quote, "translationY", 2*density, 0f))
            }
            state.animation = AnimatorSet().apply {
                playSequentially(out, back)
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { state.animation = null; state.cycling = false; state.settle() } })
                start()
            }
        })
        Text(stringResource(signature.quote), Modifier.padding(top = 4.dp).rpnMotion(state.quote), color = themeColor(R.attr.primaryLightColorText),
            textAlign = TextAlign.Center, style = TextStyle(fontSize = 11.5.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

private fun keys(name: String, vararg values: Pair<Float, Float>) = PropertyValuesHolder.ofKeyframe(name, *values.map { Keyframe.ofFloat(it.first, it.second) }.toTypedArray())
