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
package com.celzero.bravedns.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A self-contained premium "ocean moment" animation for the Rethink Plus
 * premium screen header, featuring a small pod of dolphins drawn entirely as
 * vector [Path] shapes — no bitmap/sprite-sheet asset is bundled or decoded.
 *
 * The scene: a calm underwater world below a subtly moving water surface.
 * The leader dolphin enters from the left underwater, swims calmly, gradually
 * approaches the surface, breaches in an elegant jump (splash), hangs briefly
 * at the apex, dives back in (second splash), and continues underwater
 * off-screen before the cycle repeats. The rest of the pod follows the same
 * trajectory, each offset by its own time lag and size, reading as a small
 * pod swimming and leaping together. The underwater cruise alternates
 * between a short and a longer variant per cycle so the loop feels organic
 * rather than metronomic.
 *
 * Architecture: a single time-driven custom view (no Animators). All phase
 * motion is analytic (piecewise waypoints plus easing), producing natural
 * slow -> fast -> slow pacing without keyframe popping.
 *
 * Rendering: the dolphin silhouette (body, dorsal fin, pectoral fin, tail
 * fluke, belly patch, eye) is authored once as a handful of [Path] objects in
 * an abstract unit space and rendered via [Canvas.scale] / [Canvas.rotate] /
 * [Canvas.translate] — the same handful of Path objects are reused for every
 * pod member and every frame, so on-screen size, banking, and pose all come
 * from cheap affine transforms rather than pre-rendered bitmaps at every
 * size. This keeps the whole animation's static memory footprint to a few
 * small Path/Paint objects (no multi-hundred-KB sprite sheet, no per-size
 * bitmap cache) while still allowing an arbitrarily large pod. Particle
 * pools are fixed, there are zero per-frame allocations, and the invalidate
 * loop self-suspends when stopped, detached, or the window is not visible.
 */
class DolphinOceanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------------------
    // Trajectory phases
    // ---------------------------------------------------------------------

    /** Ordered phases of one dolphin cycle. Durations in milliseconds. */
    private enum class DolphinPhase(val durationMs: Long) {
        /** Calm underwater entry from off-screen left. */
        ENTER(1_700L),

        /** Calm underwater cruise. Duration alternates per cycle variant. */
        SWIM(3_400L),

        /** Rises toward the surface, accelerating, nose pitching up. */
        APPROACH_SURFACE(1_000L),

        /** Exits the water and climbs, decelerating. Splash on exit. */
        BREACH(1_200L),

        /** Brief suspension at the top of the jump. */
        APEX(350L),

        /** Energetic dive back through the surface. Splash on re-entry. */
        DIVE(1_300L),

        /** Calm underwater exit toward off-screen right. */
        UNDERWATER_EXIT(1_400L)
    }

    /** Reusable pose result; filled by [calculateDolphinPose] each frame. */
    private class DolphinPose {
        var centerX = 0f
        var centerY = 0f
        var rotationDeg = 0f
        var scale = 1f
        var alpha = 1f
    }

    // ---------------------------------------------------------------------
    // The pod
    // ---------------------------------------------------------------------

    /**
     * One dolphin in the pod. Every member follows the same phase trajectory
     * offset by [lagMs] in time; a positive lag places the member behind the
     * leader along the path, which reads naturally as dolphins following.
     * [unitToPx] is this member's uniform scale from the shared unit-space
     * vector paths to on-screen pixels; recomputed only on size changes.
     */
    private class PodMember(
        val sizeFraction: Float,
        val lagMs: Long,
        val alphaFraction: Float
    ) {
        val pose = DolphinPose()
        var previousCenterY = 0f
        var unitToPx = 1f
    }

    private val pod =
        POD_SIZE_FRACTIONS.indices.map { i ->
            PodMember(POD_SIZE_FRACTIONS[i], POD_LAG_MS[i], POD_ALPHA_FRACTIONS[i])
        }

    // ---------------------------------------------------------------------
    // Scene geometry (recomputed on size change)
    // ---------------------------------------------------------------------

    private var surfaceY = 0f
    private var deepY = 0f
    private var exitDeepY = 0f
    private var apexY = 0f
    private var jumpHeight = 1f
    private var dolphinSize = 0f
    private val waterFillPath = Path()
    private val waveStrokePath = Path()

    // ---------------------------------------------------------------------
    // Particles (fixed pools; no per-frame allocation)
    // ---------------------------------------------------------------------

    private class Droplet {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var radius = 0f
        var ageMs = 0f
        var lifeMs = 0f
        var maxAlpha = 0
    }

    private class Ripple {
        var active = false
        var x = 0f
        var ageMs = 0f
    }

    private class Bubble {
        var active = false
        var x = 0f
        var y = 0f
        var radius = 0f
        var riseSpeed = 0f
        var ageMs = 0f
        var lifeMs = 0f
        var maxAlpha = 0
    }

    private val droplets = Array(DROPLET_POOL_SIZE) { Droplet() }
    private val ripples = Array(RIPPLE_POOL_SIZE) { Ripple() }
    private val bubbles = Array(BUBBLE_POOL_SIZE) { Bubble() }
    private var dropletCursor = 0
    private var rippleCursor = 0
    private var bubbleCursor = 0
    private var bubbleTimerMs = 0f

    // ---------------------------------------------------------------------
    // Animation state
    // ---------------------------------------------------------------------

    private val random = Random(RANDOM_SEED)
    private var started = false
    private var running = false
    private var lastFrameNanos = 0L
    private var elapsedInCycleMs = 0f
    private var cycleCount = 0
    private var wavePhase1 = 0f
    private var wavePhase2 = 0f

    // ---------------------------------------------------------------------
    // Paints (reused; alpha set per frame)
    // ---------------------------------------------------------------------

    private val waterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = WAVE_COLOR
    }
    private val dropletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DROPLET_COLOR
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = DROPLET_COLOR
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = WAVE_COLOR
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SHADOW_COLOR
    }
    private val shadowRect = RectF()

    // ---------------------------------------------------------------------
    // Dolphin vector artwork
    // ---------------------------------------------------------------------
    // The silhouette (body, dorsal fin, pectoral fin, tail fluke, belly
    // patch) is authored once as Path objects in an abstract unit space
    // (nose at +x, tail at -x; +y is the belly/underside, -y is the back —
    // matching Canvas's y-down convention directly). The same Path and
    // Paint instances are reused for every pod member and every frame: only
    // a translate/rotate/scale changes per member, so there is no bitmap,
    // no per-size cache, and no per-frame allocation.

    private val dolphinBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            0f,
            UNIT_TOP_Y,
            0f,
            UNIT_BOTTOM_Y,
            intArrayOf(BODY_COLOR_TOP, BODY_COLOR_BOTTOM),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val dolphinFinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = FIN_COLOR
    }
    private val dolphinBellyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BELLY_COLOR
    }
    private val dolphinEyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = EYE_COLOR
    }
    private val dolphinEyeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = EYE_HIGHLIGHT_COLOR
    }

    /** Main body: rounded beak -> back -> tapered peduncle -> belly. */
    private val dolphinBodyPath = Path().apply {
        moveTo(108f, -1f)
        cubicTo(105f, -12f, 95f, -20f, 78f, -25f)
        cubicTo(55f, -31f, 25f, -33f, -5f, -30f)
        cubicTo(-30f, -27f, -50f, -20f, -64f, -9f)
        cubicTo(-70f, -5f, -72f, -2f, -73f, 0f)
        cubicTo(-72f, 2f, -70f, 5f, -64f, 9f)
        cubicTo(-50f, 20f, -30f, 27f, -5f, 30f)
        cubicTo(25f, 33f, 52f, 30f, 72f, 22f)
        cubicTo(84f, 17f, 92f, 12f, 98f, 5f)
        cubicTo(102f, 2f, 105f, 1f, 108f, -1f)
        close()
    }

    /** Crescent tail fluke; rotated independently around [TAIL_PIVOT_X]. */
    private val dolphinTailPath = Path().apply {
        moveTo(-66f, -7f)
        cubicTo(-88f, -14f, -112f, -22f, -134f, -32f)
        cubicTo(-124f, -20f, -112f, -8f, -98f, -1f)
        cubicTo(-112f, 8f, -124f, 20f, -134f, 32f)
        cubicTo(-112f, 22f, -88f, 14f, -66f, 7f)
        close()
    }

    /** Dorsal fin, swept back. */
    private val dolphinDorsalFinPath = Path().apply {
        moveTo(2f, -30f)
        cubicTo(-4f, -50f, 4f, -68f, 20f, -74f)
        cubicTo(18f, -56f, 24f, -40f, 36f, -28f)
        cubicTo(22f, -32f, 10f, -31f, 2f, -30f)
        close()
    }

    /** Pectoral fin, near the belly. */
    private val dolphinPectoralFinPath = Path().apply {
        moveTo(36f, 15f)
        cubicTo(30f, 36f, 14f, 48f, -4f, 45f)
        cubicTo(6f, 33f, 18f, 23f, 28f, 13f)
        close()
    }

    /** Lighter belly patch for the two-tone look. */
    private val dolphinBellyPath = Path().apply {
        moveTo(80f, 10f)
        cubicTo(50f, 25f, 5f, 29f, -32f, 24f)
        cubicTo(-48f, 22f, -58f, 15f, -63f, 7f)
        cubicTo(-42f, 14f, -2f, 17f, 32f, 10f)
        cubicTo(52f, 6f, 68f, 2f, 80f, -4f)
        close()
    }

    private val density = resources.displayMetrics.density

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** Starts (or restarts) the animation loop from the beginning of a cycle. */
    fun start() {
        started = true
        elapsedInCycleMs = 0f
        cycleCount = 0
        pod.forEach {
            it.previousCenterY = 0f
        }
        clearParticles()
        ensureRunning()
    }

    /** Stops the animation loop and discards all live particles. */
    fun stop() {
        started = false
        running = false
        clearParticles()
        invalidate()
    }

    private fun clearParticles() {
        droplets.forEach { it.active = false }
        ripples.forEach { it.active = false }
        bubbles.forEach { it.active = false }
        bubbleTimerMs = 0f
    }

    private fun ensureRunning() {
        if (running || !started || !isAttachedToWindow || windowVisibility != VISIBLE) return
        running = true
        lastFrameNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            ensureRunning()
        } else {
            // keep `started`; only suspend the frame loop
            running = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeSceneGeometry(w.toFloat(), h.toFloat())
    }

    // ---------------------------------------------------------------------
    // Scene geometry
    // ---------------------------------------------------------------------

    private fun computeSceneGeometry(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        surfaceY = h * SURFACE_Y_FRACTION
        deepY = h * DEEP_Y_FRACTION
        exitDeepY = h * EXIT_DEEP_Y_FRACTION

        // dolphinSize is the on-screen nose-to-tail length for the leader
        // (sizeFraction 1.0); capped so the silhouette's on-screen height
        // (length * UNIT_HEIGHT_ASPECT) never exceeds a fraction of the view
        val maxLengthByHeight = (h * MAX_DOLPHIN_HEIGHT_FRACTION) / UNIT_HEIGHT_ASPECT
        dolphinSize = (DOLPHIN_LENGTH_DP * density).coerceAtMost(maxLengthByHeight)
        jumpHeight =
            (h * JUMP_HEIGHT_FRACTION).coerceAtLeast(dolphinSize * UNIT_HEIGHT_ASPECT * 0.9f)
        apexY = surfaceY - jumpHeight

        // uniform unit-space -> pixel scale per member; the same vector
        // paths are reused for every member, only this scale factor differs
        pod.forEach { it.unitToPx = (dolphinSize * it.sizeFraction) / UNIT_LENGTH }

        waterFillPaint.shader = LinearGradient(
            0f,
            surfaceY,
            0f,
            h,
            intArrayOf(WATER_TINT_TOP, WATER_TINT_BOTTOM),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** Swim phase duration; alternates per cycle for an organic loop. */
    private fun swimDurationMs(): Long {
        val variantExtra = if (cycleCount % 2 == 0) 0L else SWIM_VARIANT_EXTRA_MS
        return DolphinPhase.SWIM.durationMs + variantExtra
    }

    private fun cycleDurationMs(): Long {
        var total = 0L
        for (phase in DolphinPhase.entries) {
            total +=
                if (phase == DolphinPhase.SWIM) swimDurationMs() else phase.durationMs
        }
        return total
    }

    // ---------------------------------------------------------------------
    // Frame loop
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        val dtMs =
            if (lastFrameNanos == 0L) FRAME_BUDGET_MS else (now - lastFrameNanos) / 1_000_000f
        lastFrameNanos = now
        val dtSec = (dtMs / 1000f).coerceAtMost(MAX_FRAME_SEC)

        if (!running) {
            lastFrameNanos = 0L
            return
        }

        advanceTime(dtMs)
        drawScene(canvas, w, h, dtSec)
        postInvalidateOnAnimation()
    }

    private fun advanceTime(dtMs: Float) {
        elapsedInCycleMs += dtMs
        val cycleLen = cycleDurationMs().toFloat()
        if (elapsedInCycleMs >= cycleLen) {
            elapsedInCycleMs -= cycleLen
            cycleCount++
        }
        wavePhase1 += WAVE_SPEED_1 * dtMs / 1000f
        wavePhase2 -= WAVE_SPEED_2 * dtMs / 1000f
    }

    private fun drawScene(canvas: Canvas, w: Float, h: Float, dtSec: Float) {
        drawWater(canvas, w, h)

        for (member in pod) {
            drawMember(canvas, member, w, h, dtSec)
        }

        updateAndDrawSplash(canvas, dtSec)
        updateAndDrawBubbles(canvas, dtSec)
    }

    /** Computes one member's pose for the current frame and renders it. */
    private fun drawMember(canvas: Canvas, member: PodMember, w: Float, h: Float, dtSec: Float) {
        val cycleLen = cycleDurationMs().toFloat()
        var memberElapsed = elapsedInCycleMs - member.lagMs
        if (memberElapsed < 0f) {
            member.previousCenterY = 0f
            return // the member has not entered the scene yet
        }
        if (memberElapsed >= cycleLen) memberElapsed -= cycleLen

        calculateDolphinPose(memberElapsed, cycleLen, w, h, member.pose)

        // detect surface crossings (breach exit / dive entry) for splashes
        maybeSpawnSplashOnCrossing(member, w)

        drawShadowIfAirborne(canvas, member)
        drawDolphinGlyph(canvas, member, memberElapsed)
        // sparse bubbles trail the leader only, keeping the scene quiet
        if (member === pod[0]) maybeEmitBubble(member, dtSec)
    }

    // ---------------------------------------------------------------------
    // Dolphin trajectory
    // ---------------------------------------------------------------------

    /**
     * Fills [pose] for [elapsedMs] within the cycle.
     *
     * Horizontal motion is a single constant-speed glide across the entire
     * cycle: x has no per-phase easing, so velocity is continuous by
     * construction and the motion never stops, stalls, or jumps. All the
     * life is in the vertical curve, rotation, and scale, which are smooth
     * S-curves that start and end at zero velocity: steady swim ->
     * continuous rise through the surface -> brief suspension at the apex
     * (rotation passes through) -> more energetic but still smooth fall ->
     * calm underwater exit. Because the dolphin is a single freely-rotated
     * vector silhouette (not pre-rendered pose frames), rotationDeg alone is
     * enough to sell every part of the arc — there is no artwork to keep in
     * sync.
     */
    private fun calculateDolphinPose(
        elapsedMs: Float,
        cycleLen: Float,
        w: Float,
        h: Float,
        pose: DolphinPose
    ) {
        val half = dolphinSize / 2f
        val uwAlpha = 1f - UNDERWATER_ALPHA_DIP

        // steady glide: x is linear in cycle time
        pose.centerX =
            lerp(-half * 2f, w + half * 2f, (elapsedMs / cycleLen).coerceIn(0f, 1f))

        // phase windows (keep in sync with the DolphinPhase durations)
        val enterLen = DolphinPhase.ENTER.durationMs.toFloat()
        val swimLen = swimDurationMs().toFloat()
        val riseLen =
            DolphinPhase.APPROACH_SURFACE.durationMs + DolphinPhase.BREACH.durationMs
        val apexLen = DolphinPhase.APEX.durationMs
        val fallLen = DolphinPhase.DIVE.durationMs
        val preRiseLen = enterLen + swimLen
        val preApexLen = preRiseLen + riseLen
        val preFallLen = preApexLen + apexLen
        val preExitLen = preFallLen + fallLen

        var t = elapsedMs
        when {
            t < enterLen -> {
                // ENTER: settle in at depth
                val e = easeInOutSine(t / enterLen)
                pose.centerY = deepY
                pose.rotationDeg = lerp(ENTER_ROTATION, 0f, e)
                pose.scale = UNDERWATER_SCALE
                pose.alpha = uwAlpha
            }

            t < preRiseLen -> {
                // SWIM: calm cruise with a gentle porpoising bob that starts
                // and ends at zero offset so it never pops
                val u = (t - enterLen) / swimLen
                pose.centerY =
                    deepY + sin(u * 2f * PI.toFloat() * SWIM_BOB_CYCLES) * h * SWIM_BOB_FRACTION
                pose.rotationDeg = sin(u * 2f * PI.toFloat() * SWIM_BOB_CYCLES) * SWIM_ROTATION
                pose.scale = UNDERWATER_SCALE
                pose.alpha = uwAlpha
            }

            t < preApexLen -> {
                // RISE: one continuous ease up through the surface
                val u = (t - preRiseLen) / riseLen
                val e = easeInOutSine(u)
                pose.centerY = lerp(deepY, apexY, e)
                pose.rotationDeg = lerp(0f, NOSE_UP_ROTATION, e)
                pose.scale = lerp(UNDERWATER_SCALE, 1f, e)
                pose.alpha = lerp(uwAlpha, 1f, e)
            }

            t < preFallLen -> {
                // APEX: brief suspension; vertical velocity is naturally zero
                // at the top while the dolphin rotates through
                val u = (t - preApexLen) / apexLen
                val e = easeInOutSine(u)
                pose.centerY = apexY
                pose.rotationDeg = lerp(NOSE_UP_ROTATION, APEX_ROTATION, e)
                pose.scale = lerp(1f, APEX_SCALE, e)
                pose.alpha = 1f
            }

            t < preExitLen -> {
                // FALL: the dive — the same smooth curve over a shorter
                // window, so it feels more energetic without any jerk
                val u = (t - preFallLen) / fallLen
                val e = easeInOutSine(u)
                pose.centerY = lerp(apexY, exitDeepY, e)
                pose.rotationDeg = lerp(APEX_ROTATION, DIVE_ROTATION, e)
                pose.scale = lerp(APEX_SCALE, UNDERWATER_SCALE, e)
                pose.alpha = lerp(1f, uwAlpha, e)
            }

            else -> {
                // EXIT: calm level swim off-screen
                val exitLen = (cycleLen - preExitLen).coerceAtLeast(1f)
                val e = easeInOutSine(((t - preExitLen) / exitLen).coerceIn(0f, 1f))
                pose.centerY = exitDeepY
                pose.rotationDeg = lerp(DIVE_ROTATION, EXIT_ROTATION, e)
                pose.scale = UNDERWATER_SCALE
                pose.alpha = uwAlpha
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private fun drawWater(canvas: Canvas, w: Float, h: Float) {
        val a1 = density * WAVE_AMPLITUDE_1_DP
        val a2 = density * WAVE_AMPLITUDE_2_DP
        val k1 = (2f * PI.toFloat()) / (w * WAVE_LENGTH_1_FRACTION)
        val k2 = (2f * PI.toFloat()) / (w * WAVE_LENGTH_2_FRACTION)

        // water body below the surface
        waterFillPath.rewind()
        waterFillPath.moveTo(0f, waveY(0f, a1, a2, k1, k2))
        val step = w / WAVE_SEGMENTS
        var x = step
        var i = 1
        while (i <= WAVE_SEGMENTS) {
            waterFillPath.lineTo(x, waveY(x, a1, a2, k1, k2))
            x += step
            i++
        }
        waterFillPath.lineTo(w, h)
        waterFillPath.lineTo(0f, h)
        waterFillPath.close()
        canvas.drawPath(waterFillPath, waterFillPaint)

        // single quiet surface crest line
        waveStrokePath.rewind()
        waveStrokePath.moveTo(0f, waveY(0f, a1, a2, k1, k2))
        x = step
        i = 1
        while (i <= WAVE_SEGMENTS) {
            waveStrokePath.lineTo(x, waveY(x, a1, a2, k1, k2))
            x += step
            i++
        }
        wavePaint.strokeWidth = density * WAVE_STROKE_DP
        wavePaint.alpha = WAVE_STROKE_ALPHA
        canvas.drawPath(waveStrokePath, wavePaint)
    }

    private fun waveY(x: Float, a1: Float, a2: Float, k1: Float, k2: Float): Float =
        surfaceY + a1 * sin(k1 * x + wavePhase1) + a2 * sin(k2 * x + wavePhase2)

    /** Soft shadow on the surface while this dolphin is airborne. */
    private fun drawShadowIfAirborne(canvas: Canvas, member: PodMember) {
        val pose = member.pose
        if (pose.centerY >= surfaceY) return // underwater: no shadow

        val heightAbove = ((surfaceY - pose.centerY) / jumpHeight).coerceIn(0f, 1f)
        val strength = 1f - heightAbove
        if (strength <= 0f) return

        val rx = dolphinSize * member.sizeFraction * 0.42f * (1f - 0.35f * heightAbove)
        val ry = rx * SHADOW_ASPECT
        shadowRect.set(pose.centerX - rx, surfaceY - ry, pose.centerX + rx, surfaceY + ry)
        shadowPaint.alpha =
            (SHADOW_MAX_ALPHA * strength * member.alphaFraction).toInt().coerceIn(0, 255)
        canvas.drawOval(shadowRect, shadowPaint)
    }

    /**
     * Draws the member's dolphin, centered on the pose and banked along it.
     * The silhouette is a handful of shared vector [Path]s scaled/rotated by
     * [DolphinPose] — the same shape reads correctly at any rotation/scale,
     * so there is no pose-vs-artwork mismatch to keep in sync (unlike a
     * sprite sheet, a single rotated silhouette can never show, say, a
     * splash frame while still underwater). The tail fluke is additionally
     * rotated around its own pivot for a light flapping motion, giving a
     * cheap but convincing swimming cue independent of the body pose.
     */
    private fun drawDolphinGlyph(canvas: Canvas, member: PodMember, memberElapsedMs: Float) {
        val pose = member.pose
        val alpha = (pose.alpha * 255f * member.alphaFraction).toInt().coerceIn(0, 255)
        dolphinBodyPaint.alpha = alpha
        dolphinFinPaint.alpha = alpha
        dolphinBellyPaint.alpha = alpha
        dolphinEyePaint.alpha = alpha
        dolphinEyeHighlightPaint.alpha = alpha

        val tailFlapDeg =
            sin(memberElapsedMs / 1000f * 2f * PI.toFloat() * TAIL_FLAP_HZ) * TAIL_FLAP_AMPLITUDE_DEG

        canvas.save()
        canvas.translate(pose.centerX, pose.centerY)
        canvas.rotate(pose.rotationDeg)
        val s = pose.scale * member.unitToPx
        canvas.scale(s, s)

        // tail flaps independently, behind the body, pivoting at its
        // attachment point so the body-tail joint stays put
        canvas.save()
        canvas.rotate(tailFlapDeg, TAIL_PIVOT_X, TAIL_PIVOT_Y)
        canvas.drawPath(dolphinTailPath, dolphinFinPaint)
        canvas.restore()

        canvas.drawPath(dolphinBodyPath, dolphinBodyPaint)
        canvas.drawPath(dolphinDorsalFinPath, dolphinFinPaint)
        canvas.drawPath(dolphinPectoralFinPath, dolphinFinPaint)
        canvas.drawPath(dolphinBellyPath, dolphinBellyPaint)
        canvas.drawCircle(EYE_X, EYE_Y, EYE_RADIUS, dolphinEyePaint)
        canvas.drawCircle(EYE_HIGHLIGHT_X, EYE_HIGHLIGHT_Y, EYE_HIGHLIGHT_RADIUS, dolphinEyeHighlightPaint)
        canvas.restore()
    }

    // ---------------------------------------------------------------------
    // Splash (droplets + surface ripple)
    // ---------------------------------------------------------------------

    /** Spawns a splash whenever a member crosses the water surface. */
    private fun maybeSpawnSplashOnCrossing(member: PodMember, w: Float) {
        val centerY = member.pose.centerY
        if (member.previousCenterY > 0f) {
            val crossedDescending = member.previousCenterY <= surfaceY && centerY > surfaceY
            val crossedAscending = member.previousCenterY >= surfaceY && centerY < surfaceY
            if (crossedDescending || crossedAscending) {
                spawnSplash(member.pose.centerX.coerceIn(0f, w), member.sizeFraction)
            }
        }
        member.previousCenterY = centerY
    }

    private fun spawnSplash(x: Float, sizeFraction: Float) {
        // brief expanding ripple on the surface line
        val ripple = ripples[rippleCursor % RIPPLE_POOL_SIZE]
        rippleCursor++
        ripple.active = true
        ripple.x = x
        ripple.ageMs = 0f

        // small, sparse droplet burst
        var spawned = 0
        var attempts = 0
        while (spawned < SPLASH_DROPLET_COUNT && attempts < DROPLET_POOL_SIZE) {
            attempts++
            val d = droplets[dropletCursor % DROPLET_POOL_SIZE]
            dropletCursor++
            if (d.active) continue
            d.active = true
            d.x = x + randDp(-3f, 3f)
            d.y = surfaceY + randDp(-1f, 1f)
            d.vx = randDp(SPLASH_VX_MIN_DP, SPLASH_VX_MAX_DP) * sizeFraction
            d.vy = randDp(SPLASH_VY_MIN_DP, SPLASH_VY_MAX_DP) * sizeFraction
            d.radius = randDp(1.1f, 2.1f) * sizeFraction
            d.lifeMs = SPLASH_DROPLET_LIFE_MIN_MS + random.nextFloat() *
                (SPLASH_DROPLET_LIFE_MAX_MS - SPLASH_DROPLET_LIFE_MIN_MS)
            d.ageMs = 0f
            d.maxAlpha = SPLASH_MAX_ALPHA + random.nextInt(SPLASH_ALPHA_JITTER)
            spawned++
        }
    }

    private fun updateAndDrawSplash(canvas: Canvas, dtSec: Float) {
        val gravity = density * SPLASH_GRAVITY_DP_PER_S2
        for (d in droplets) {
            if (!d.active) continue
            d.ageMs += dtSec * 1000f
            if (d.ageMs >= d.lifeMs) {
                d.active = false
                continue
            }
            d.vy += gravity * dtSec
            d.x += d.vx * dtSec
            d.y += d.vy * dtSec
            val progress = d.ageMs / d.lifeMs
            dropletPaint.alpha = (d.maxAlpha * (1f - progress)).toInt().coerceIn(0, 255)
            canvas.drawCircle(d.x, d.y, d.radius, dropletPaint)
        }

        ripplePaint.strokeWidth = density * RIPPLE_STROKE_DP
        for (r in ripples) {
            if (!r.active) continue
            r.ageMs += dtSec * 1000f
            if (r.ageMs >= RIPPLE_LIFE_MS) {
                r.active = false
                continue
            }
            val progress = r.ageMs / RIPPLE_LIFE_MS
            val rx = dolphinSize * lerp(RIPPLE_START_FRACTION, RIPPLE_END_FRACTION, progress)
            val ry = rx * SHADOW_ASPECT * 0.6f
            ripplePaint.alpha = (RIPPLE_MAX_ALPHA * (1f - progress)).toInt().coerceIn(0, 255)
            canvas.drawOval(r.x - rx, surfaceY - ry, r.x + rx, surfaceY + ry, ripplePaint)
        }
    }

    // ---------------------------------------------------------------------
    // Bubbles (sparse, underwater only; tied to the leader)
    // ---------------------------------------------------------------------

    private fun maybeEmitBubble(leader: PodMember, dtSec: Float) {
        val pose = leader.pose
        val underwater = pose.centerY > surfaceY + dolphinSize * 0.1f
        if (!underwater) {
            bubbleTimerMs = BUBBLE_SPAWN_INTERVAL_MS // spawn soon after submerging
            return
        }

        bubbleTimerMs += dtSec * 1000f
        if (bubbleTimerMs < BUBBLE_SPAWN_INTERVAL_MS) return
        bubbleTimerMs = 0f

        val b = bubbles[bubbleCursor % BUBBLE_POOL_SIZE]
        bubbleCursor++
        b.active = true
        // emit behind/above the dolphin, near its tail
        b.x = pose.centerX - dolphinSize * 0.32f + randDp(-2f, 2f)
        b.y = pose.centerY - dolphinSize * 0.1f + randDp(-2f, 2f)
        b.radius = randDp(1.0f, 2.0f)
        b.riseSpeed = density * BUBBLE_RISE_DP_PER_S
        b.lifeMs = BUBBLE_LIFE_MS + random.nextFloat() * BUBBLE_LIFE_JITTER_MS
        b.ageMs = 0f
        b.maxAlpha = BUBBLE_MAX_ALPHA + random.nextInt(BUBBLE_ALPHA_JITTER)
    }

    private fun updateAndDrawBubbles(canvas: Canvas, dtSec: Float) {
        for (b in bubbles) {
            if (!b.active) continue
            b.ageMs += dtSec * 1000f
            if (b.ageMs >= b.lifeMs || b.y < surfaceY) {
                b.active = false
                continue
            }
            b.y -= b.riseSpeed * dtSec
            val progress = b.ageMs / b.lifeMs
            bubblePaint.strokeWidth = b.radius * 0.5f
            bubblePaint.alpha = (b.maxAlpha * (1f - progress)).toInt().coerceIn(0, 255)
            canvas.drawCircle(b.x, b.y, b.radius, bubblePaint)
        }
    }

    // ---------------------------------------------------------------------
    // Math helpers (allocation-free)
    // ---------------------------------------------------------------------

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private fun easeInOutSine(t: Float): Float = 0.5f * (1f - cos(PI.toFloat() * t))

    private fun randDp(from: Float, to: Float): Float {
        val v = from + (to - from) * random.nextFloat()
        return v * density
    }

    companion object {
        // --- the pod ---
        // Five members by default. Sizes are intentionally mixed rather
        // than strictly largest-to-smallest — real pods don't line up by
        // size — while lag/alpha still stagger entrance and depth so the
        // trail reads cleanly. All three arrays must be the same length;
        // index 0 enters first, but is not necessarily the biggest.
        private val POD_SIZE_FRACTIONS = floatArrayOf(0.47f, 0.60f, 0.35f, 0.54f, 0.41f)
        private val POD_LAG_MS = longArrayOf(0L, 670L, 1_400L, 2_170L, 3_010L)
        private val POD_ALPHA_FRACTIONS = floatArrayOf(0.90f, 1.00f, 0.78f, 0.96f, 0.84f)

        // --- vector dolphin geometry ---
        // Unit space the Path artwork is authored in: nose at +x, tail tip
        // at -x, back at -y, belly at +y (matches Canvas's y-down axis, so
        // no flips are needed). These spans are only used to convert the
        // authored shape into an on-screen size/aspect ratio.
        private const val UNIT_LENGTH = 242f // nose (x=108) to tail tip (x=-134)
        private const val UNIT_HEIGHT_ASPECT = 122f / UNIT_LENGTH // dorsal tip to pectoral tip
        private const val UNIT_TOP_Y = -74f // dorsal fin tip; gradient + bounds reference
        private const val UNIT_BOTTOM_Y = 48f // pectoral fin tip; gradient + bounds reference
        private const val TAIL_PIVOT_X = -68f
        private const val TAIL_PIVOT_Y = 0f
        private const val EYE_X = 82f
        private const val EYE_Y = -9f
        private const val EYE_RADIUS = 3.4f
        private const val EYE_HIGHLIGHT_X = 81f
        private const val EYE_HIGHLIGHT_Y = -10.2f
        private const val EYE_HIGHLIGHT_RADIUS = 1.0f
        private const val TAIL_FLAP_HZ = 1.7f
        private const val TAIL_FLAP_AMPLITUDE_DEG = 12f

        // --- vector dolphin colors ---
        private const val BODY_COLOR_TOP = 0xFF2F6FB4.toInt()
        private const val BODY_COLOR_BOTTOM = 0xFF4E97D9.toInt()
        private const val FIN_COLOR = 0xFF25518F.toInt()
        private const val BELLY_COLOR = 0xFFEAF3FF.toInt()
        private const val EYE_COLOR = 0xFF10151A.toInt()
        private const val EYE_HIGHLIGHT_COLOR = 0xFFFFFFFF.toInt()

        // --- scene layout (fractions of view size; density-independent) ---
        private const val SURFACE_Y_FRACTION = 0.42f
        private const val DEEP_Y_FRACTION = 0.70f
        private const val EXIT_DEEP_Y_FRACTION = 0.72f
        private const val JUMP_HEIGHT_FRACTION = 0.30f
        private const val DOLPHIN_LENGTH_DP = 108f
        private const val MAX_DOLPHIN_HEIGHT_FRACTION = 0.42f

        // --- depth / pose ---
        private const val UNDERWATER_SCALE = 0.92f
        private const val APEX_SCALE = 1.08f
        private const val UNDERWATER_ALPHA_DIP = 0.12f
        private const val SWIM_BOB_FRACTION = 0.02f
        // whole number so the bob always returns to zero at the phase end
        private const val SWIM_BOB_CYCLES = 2f
        private const val SWIM_ROTATION = 5f

        // --- rotation (degrees; negative = nose up). Each jump segment is a
        // smooth S-curve between these keys, so rotation velocity is zero at
        // every phase boundary — no snapping. A single vector silhouette can
        // be freely rotated without ever looking "wrong" (unlike a sprite
        // frame, which already depicts a fixed pose), so these can be as
        // dramatic as the motion calls for.
        private const val ENTER_ROTATION = 4f
        private const val NOSE_UP_ROTATION = -50f
        private const val APEX_ROTATION = -15f
        private const val DIVE_ROTATION = 65f
        private const val EXIT_ROTATION = 6f

        // --- cycle variants ---
        private const val SWIM_VARIANT_EXTRA_MS = 1_400L

        // --- water surface ---
        private const val WAVE_AMPLITUDE_1_DP = 1.6f
        private const val WAVE_AMPLITUDE_2_DP = 1.0f
        private const val WAVE_LENGTH_1_FRACTION = 0.5f
        private const val WAVE_LENGTH_2_FRACTION = 0.33f
        private const val WAVE_SPEED_1 = 1.2f // rad/s
        private const val WAVE_SPEED_2 = 0.8f
        private const val WAVE_SEGMENTS = 32
        private const val WAVE_STROKE_DP = 1.2f
        private const val WAVE_STROKE_ALPHA = 51 // ~20%
        private const val WAVE_COLOR = 0xFFFFFFFF.toInt()
        private const val WATER_TINT_TOP = 0x14000000 // ~8% black at the surface
        private const val WATER_TINT_BOTTOM = 0x06000000

        // --- splash ---
        private const val SPLASH_DROPLET_COUNT = 6
        private const val SPLASH_VX_MIN_DP = -70f
        private const val SPLASH_VX_MAX_DP = 70f
        private const val SPLASH_VY_MIN_DP = -170f
        private const val SPLASH_VY_MAX_DP = -90f
        private const val SPLASH_GRAVITY_DP_PER_S2 = 480f
        private const val SPLASH_DROPLET_LIFE_MIN_MS = 450f
        private const val SPLASH_DROPLET_LIFE_MAX_MS = 650f
        private const val SPLASH_MAX_ALPHA = 140
        private const val SPLASH_ALPHA_JITTER = 40
        private const val DROPLET_COLOR = 0xFFFFFFFF.toInt()
        // sized for up to POD_SIZE_FRACTIONS.size members splashing in
        // quick succession without droplets being recycled prematurely
        private const val DROPLET_POOL_SIZE = 28
        private const val RIPPLE_POOL_SIZE = 6
        private const val RIPPLE_LIFE_MS = 550f
        private const val RIPPLE_START_FRACTION = 0.15f
        private const val RIPPLE_END_FRACTION = 0.55f
        private const val RIPPLE_MAX_ALPHA = 70
        private const val RIPPLE_STROKE_DP = 1.4f

        // --- surface shadow ---
        private const val SHADOW_COLOR = 0xFF000000.toInt()
        private const val SHADOW_MAX_ALPHA = 46
        private const val SHADOW_ASPECT = 0.18f

        // --- bubbles ---
        private const val BUBBLE_SPAWN_INTERVAL_MS = 520f
        private const val BUBBLE_RISE_DP_PER_S = 14f
        private const val BUBBLE_LIFE_MS = 1200f
        private const val BUBBLE_LIFE_JITTER_MS = 500f
        private const val BUBBLE_MAX_ALPHA = 60
        private const val BUBBLE_ALPHA_JITTER = 30
        private const val BUBBLE_POOL_SIZE = 6

        // --- frame loop ---
        private const val FRAME_BUDGET_MS = 16.7f
        private const val MAX_FRAME_SEC = 0.05f
        private const val RANDOM_SEED = 20240827L
    }
}
