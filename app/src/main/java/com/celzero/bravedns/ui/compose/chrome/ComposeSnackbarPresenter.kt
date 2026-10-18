/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.chrome

import com.celzero.bravedns.ui.compose.controls.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.home.HomeTourAnchors
import com.celzero.bravedns.ui.compose.themeColor
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlin.math.abs

internal fun snackbarBaseTimeout(duration: Int): Long = when {
    duration == ComposeSnackbarPresenter.LENGTH_INDEFINITE -> Long.MAX_VALUE
    duration > 0 -> duration.toLong()
    duration == ComposeSnackbarPresenter.LENGTH_SHORT -> 1_500L
    else -> 2_750L
}

/** Native content attached to a coordinator/window boundary, never a View-rendered Snackbar. */
class ComposeSnackbarPresenter(
    private val view: View,
    private val message: String,
    private val duration: Int = LENGTH_LONG,
    private val actionLabel: String? = null,
    private val action: (() -> Unit)? = null,
    private val appearance: Appearance = Appearance.Themed,
    private val actionColor: Int? = null,
    private val onDismissed: (ComposeSnackbarPresenter) -> Unit = {},
) {
    enum class Appearance { Themed, Material }

    companion object {
        const val LENGTH_INDEFINITE = -2
        const val LENGTH_SHORT = -1
        const val LENGTH_LONG = 0
        // Material's manager was process-global: an update-complete bar and a helper bar
        // must not remain visible together. Keep one active bar and the latest pending one.
        private var active: ComposeSnackbarPresenter? = null
        private var pending: ComposeSnackbarPresenter? = null

        internal fun findBottomNavView(view: View): View? {
            val root = view.rootView ?: return null
            root.findViewById<View>(R.id.nav_view)?.takeIf { it.isShown }?.let { return it }
            fun find(group: ViewGroup): View? {
                for (index in 0 until group.childCount) {
                    val child = group.getChildAt(index)
                    // Discovery-only compatibility with an existing custom-ID/TV host.
                    if (child is BottomNavigationView && child.isShown) return child
                    if (child is ViewGroup) find(child)?.let { return it }
                }
                return null
            }
            return (root as? ViewGroup)?.let(::find)
        }

        private fun bestParent(view: View): ViewGroup {
            var candidate: View? = view
            while (candidate != null) {
                if (candidate is CoordinatorLayout) return candidate
                if (candidate is FrameLayout && candidate.id == android.R.id.content) return candidate
                candidate = candidate.parent as? View
            }
            return view.rootView as? FrameLayout ?: error("No window container for snackbar")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val parent = bestParent(view)
    // BaseActivity caps its content child at 600dp. Retain that horizontal boundary when
    // native Home chrome uses the window content FrameLayout rather than a CoordinatorLayout.
    private val composeActivityRoot = view.rootView.findViewById<View>(R.id.container)
        ?.takeIf { it is ComposeView }
    private val nav = if (appearance == Appearance.Themed) findBottomNavView(view) else null
    private val composeAnchors = if (appearance == Appearance.Themed)
        HomeTourAnchors.forContext(view.context) else null
    private var stopObservingNavigation: (() -> Unit)? = null
    private val hasAction = actionLabel != null && action != null
    private val host = ComposeView(view.context)
    private var dismissed = false
    private var dismissing = false
    private var requested = false
    private var actionInvoked = false
    private var touched = false
    private var sliding = false
    private var insetBottom = 0
    private var dragOffset by mutableFloatStateOf(0f)
    private val timeout = Runnable { dismiss() }
    private val reposition = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }

    fun show() {
        if (requested || dismissed) return
        requested = true
        if (active == null) {
            active = this
            attach()
        } else {
            pending = this
            active?.dismiss()
        }
    }

    private fun attach() {
        if (dismissed || dismissing) return
        host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        host.setContent { RethinkTheme { Content() } }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { finishDismissal() }
        })
        val params = when (parent) {
            is CoordinatorLayout -> CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            else -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        parent.addView(host, params)
        stopObservingNavigation = composeAnchors?.observeBounds(R.id.nav_view) { position() }
        parent.addOnLayoutChangeListener(reposition)
        composeActivityRoot?.addOnLayoutChangeListener(reposition)
        nav?.addOnLayoutChangeListener(reposition)
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            // Preserve the old helper's additive margin mutation, including 80 physical pixels.
            if (appearance == Appearance.Themed) insetBottom += navigationBottom + 80
            else insetBottom = navigationBottom
            position()
            insets
        }
        position()
        ViewCompat.requestApplyInsets(host)
        host.post {
            if (dismissed || dismissing || !host.isAttachedToWindow) return@post
            host.translationY = host.height.toFloat()
            host.animate().translationY(0f).setDuration(250L).withEndAction { scheduleTimeout() }.start()
        }
    }

    fun dismiss() {
        if (dismissed || dismissing) return
        dismissing = true
        handler.removeCallbacks(timeout)
        if (!host.isAttachedToWindow) {
            parent.removeView(host)
            finishDismissal()
            return
        }
        host.animate().cancel()
        host.animate().translationY(host.height.toFloat()).alpha(0f).setDuration(250L)
            .withEndAction { parent.removeView(host); finishDismissal() }.start()
    }

    private fun finishDismissal() {
        if (dismissed) return
        dismissed = true
        handler.removeCallbacks(timeout)
        host.animate().cancel()
        parent.removeOnLayoutChangeListener(reposition)
        stopObservingNavigation?.invoke()
        stopObservingNavigation = null
        composeActivityRoot?.removeOnLayoutChangeListener(reposition)
        nav?.removeOnLayoutChangeListener(reposition)
        if (pending === this) pending = null
        if (active === this) {
            active = null
            val next = pending
            pending = null
            if (next != null && !next.dismissed) {
                active = next
                next.attach()
            }
        }
        onDismissed(this)
    }

    private fun performAction() {
        if (dismissed || dismissing || actionInvoked || !hasAction) return
        actionInvoked = true
        action?.invoke() // Listener first, action dismissal second, as in Material Snackbar.
        dismiss()
    }

    private fun recommendedTimeout(): Long {
        val base = snackbarBaseTimeout(duration)
        if (base == Long.MAX_VALUE) return base
        val manager = view.context.getSystemService(AccessibilityManager::class.java) ?: return base
        if (Build.VERSION.SDK_INT >= 29) {
            val flags = AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT or
                (if (hasAction) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
            return manager.getRecommendedTimeoutMillis(base.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), flags).toLong()
        }
        return if (hasAction && manager.isTouchExplorationEnabled) Long.MAX_VALUE else base
    }

    private fun scheduleTimeout() {
        handler.removeCallbacks(timeout)
        if (dismissed || dismissing || touched || sliding) return
        val millis = recommendedTimeout()
        if (millis != Long.MAX_VALUE) handler.postDelayed(timeout, millis)
    }

    private fun position() {
        val params = host.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val resources = view.resources
        val hMargin = if (appearance == Appearance.Themed)
            resources.getDimensionPixelSize(R.dimen.snackbar_horizontal_margin) else (8 * resources.displayMetrics.density).toInt()
        // Home's bottom bar is now a native Compose node, so discover its window bounds
        // through the same Activity registry used by the tour instead of adding a dummy View.
        val composeNavBounds = composeAnchors?.find(R.id.nav_view)
        val baseBottom = if (appearance == Appearance.Themed)
            resources.getDimensionPixelSize(R.dimen.snackbar_bottom_margin) *
                (if (nav != null || composeNavBounds != null) 2 else 1) else 0
        val navOffset = if (nav?.isShown == true) {
            val parentAt = IntArray(2); val navAt = IntArray(2)
            parent.getLocationInWindow(parentAt); nav.getLocationInWindow(navAt)
            (parentAt[1] + parent.height - navAt[1]).coerceAtLeast(0)
        } else if (composeNavBounds != null) {
            val parentAt = IntArray(2)
            parent.getLocationInWindow(parentAt)
            (parentAt[1] + parent.height - composeNavBounds.top.toInt()).coerceAtLeast(0)
        } else 0
        val bottom = baseBottom + navOffset + insetBottom
        var left = hMargin
        var right = hMargin
        composeActivityRoot?.takeIf { it.isShown && it.width > 0 }?.let { root ->
            val parentAt = IntArray(2)
            val rootAt = IntArray(2)
            parent.getLocationInWindow(parentAt)
            root.getLocationInWindow(rootAt)
            left += (rootAt[0] - parentAt[0]).coerceAtLeast(0)
            right += (parentAt[0] + parent.width - rootAt[0] - root.width).coerceAtLeast(0)
        }
        if (params.leftMargin != left || params.rightMargin != right || params.bottomMargin != bottom) {
            params.setMargins(left, params.topMargin, right, bottom)
            host.layoutParams = params
        }
    }

    @Composable
    private fun Content() {
        NativeSnackbarContent(message, if (hasAction) actionLabel else null, appearance, actionColor,
            onAction = ::performAction, onDismiss = ::dismiss,
            modifier = Modifier.graphicsLayer {
                translationX = dragOffset
                alpha = (1f - abs(dragOffset) / host.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.any { it.pressed }
                        if (pressed != touched) {
                            touched = pressed
                            if (pressed) handler.removeCallbacks(timeout) else scheduleTimeout()
                        }
                    }
                }
            }.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { sliding = true; handler.removeCallbacks(timeout) },
                    onDragCancel = { sliding = false; dragOffset = 0f; scheduleTimeout() },
                    onDragEnd = {
                        sliding = false
                        if (abs(dragOffset) >= host.width / 2f) dismiss()
                        else { dragOffset = 0f; scheduleTimeout() }
                    },
                ) { change, amount ->
                    change.consume()
                    dragOffset = if (host.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                        (dragOffset + amount).coerceAtMost(0f) else (dragOffset + amount).coerceAtLeast(0f)
                }
            },
        )
    }
}

/** Pure renderer entry point, with no preferences, services, startup or queue mutation. */
@Composable
internal fun NativeSnackbarContent(
    message: String,
    actionLabel: String?,
    appearance: ComposeSnackbarPresenter.Appearance,
    actionColor: Int?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themed = appearance == ComposeSnackbarPresenter.Appearance.Themed
    val background = if (themed) themeColor(R.attr.bottomSheetBackground) else MaterialTheme.colorScheme.inverseSurface
    val foreground = if (themed) themeColor(R.attr.primaryTextColor) else MaterialTheme.colorScheme.inverseOnSurface
    val actionForeground = actionColor?.let(::Color)
        ?: if (themed) themeColor(R.attr.accentGood) else MaterialTheme.colorScheme.inversePrimary
    Surface(shape = RoundedCornerShape(if (themed) 12.dp else 4.dp), color = background,
        border = if (themed) BorderStroke(0.5.dp, themeColor(R.attr.border)) else null,
        shadowElevation = if (themed) 8.dp else 6.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native_snackbar")
            .semantics { liveRegion = LiveRegionMode.Polite; dismiss { onDismiss(); true } },
    ) {
        Row(Modifier.padding(start = 16.dp, end = if (actionLabel != null) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f).padding(vertical = 14.dp), color = foreground,
                fontSize = 14.sp, maxLines = if (themed) 3 else 2, overflow = TextOverflow.Ellipsis)
            if (actionLabel != null) {
                OriginalButton("@text", onClick = onAction, modifier = Modifier.testTag("native_snackbar_action")) {
                    Text(actionLabel, color = actionForeground, fontSize = 14.sp)
                }
            }
        }
    }
}
