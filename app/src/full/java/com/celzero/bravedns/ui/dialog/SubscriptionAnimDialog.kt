package com.celzero.bravedns.ui.dialog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun SubscriptionAnimDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ConfettiOverlay()
            LaunchedEffect(Unit) {
                delay(DIALOG_DISPLAY_DURATION_MS)
                onDismiss()
            }
        }
    }
}

private const val DIALOG_DISPLAY_DURATION_MS = 2000L
private const val CONFETTI_COUNT = 90
private const val CONFETTI_DURATION_MS = 1600
private const val CONFETTI_SPAWN_Y = 1.05f
private const val CONFETTI_GRAVITY = 0.55f

@Composable
private fun ConfettiOverlay() {
    val palette =
        remember {
            listOf(
                Color(0xfff0efe4),
                Color(0xffe6e5de),
                Color(0xfff4306d),
                Color(0xfffbfbf7),
                Color(0xffd8d6c2)
            )
        }
    val particles =
        remember {
            val random = Random(42)
            List(CONFETTI_COUNT) {
                ConfettiParticle(
                    angle = random.nextFloat() * 80f + 50f,
                    speed = random.nextFloat() * 220f + 420f,
                    size = random.nextFloat() * 8f + 6f,
                    color = palette[random.nextInt(palette.size)],
                    spin = random.nextFloat() * 360f,
                    shape = if (random.nextBoolean()) Shape.Circle else Shape.Square,
                    drift = random.nextFloat() * 0.4f + 0.1f
                )
            }
        }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(
                    durationMillis = CONFETTI_DURATION_MS,
                    easing = LinearEasing
                )
        )
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val time = progress.value
        val width = size.width
        val height = size.height
        particles.forEachIndexed { index, p ->
            val theta = Math.toRadians(p.angle.toDouble())
            val vx = cos(theta).toFloat() * p.speed
            val vy = -sin(theta).toFloat() * p.speed
            val t = time + (index % 10) * 0.01f
            val x = width * 0.5f + vx * t + (t * t) * (p.drift * width * 0.02f)
            val y = height * CONFETTI_SPAWN_Y + vy * t + (t * t) * (CONFETTI_GRAVITY * height * 0.2f)
            val rotation = p.spin * t * 1.2f
            rotate(rotation, pivot = Offset(x, y)) {
                when (p.shape) {
                    Shape.Circle ->
                        drawCircle(
                            color = p.color,
                            radius = p.size,
                            center = Offset(x, y)
                        )
                    Shape.Square ->
                        drawRect(
                            color = p.color,
                            topLeft = Offset(x - p.size, y - p.size),
                            size = Size(p.size * 2f, p.size * 2f)
                        )
                }
            }
        }
    }
}

private data class ConfettiParticle(
    val angle: Float,
    val speed: Float,
    val size: Float,
    val color: Color,
    val spin: Float,
    val shape: Shape,
    val drift: Float
)

private enum class Shape {
    Circle,
    Square
}
