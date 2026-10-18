package com.celzero.bravedns.ui.compose.rpn

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Presentation state only. The controller keeps ownership of effects and callback ordering. */
@Keep
open class RpnControlState {
    val children = androidx.compose.runtime.mutableStateListOf<RpnControlState>()
    var text: CharSequence by mutableStateOf("")
    var visibility by mutableStateOf(View.VISIBLE)
    var isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) { visibility = if (value) View.VISIBLE else View.GONE }
    var isEnabled by mutableStateOf(true)
    var isClickable by mutableStateOf(true)
    var isFocusable by mutableStateOf(true)
    var alpha by mutableStateOf(1f)
    var scaleX by mutableStateOf(1f)
    var scaleY by mutableStateOf(1f)
    var translationX by mutableStateOf(0f)
    var translationY by mutableStateOf(0f)
    var rotation by mutableStateOf(0f)
    var foregroundColor by mutableStateOf<Int?>(null)
    var backgroundTintList by mutableStateOf<ColorStateList?>(null)
    var imageTintList by mutableStateOf<ColorStateList?>(null)
    var iconTint by mutableStateOf<ColorStateList?>(null)
    var background by mutableStateOf<Drawable?>(null)
    var drawableValue by mutableStateOf<Drawable?>(null)
    var backgroundRes by mutableStateOf(0)
    var isSelected by mutableStateOf(false)
    var imageRes by mutableStateOf(0)
    var icon by mutableStateOf<Drawable?>(null)
    var textSizeSp: Float? = null
    var maxLines: Int = Int.MAX_VALUE
    var bold = false
    var monospace = false
    var contentDescription: CharSequence? by mutableStateOf(null)
    var width by mutableStateOf(0)
    var height by mutableStateOf(0)
    var windowX = 0
    var windowY = 0
    var attached = false
    var isIndeterminate by mutableStateOf(true)
    var progress by mutableStateOf(0)
    var max by mutableStateOf(100)
    var shimmerRunning by mutableStateOf(false)
    private var checkedValue by mutableStateOf(false)
    private var broadcasting = false
    private var checkedListener: ((RpnControlState, Boolean) -> Unit)? = null
    private var clickListener: ((RpnControlState) -> Unit)? = null
    var isChecked: Boolean
        get() = checkedValue
        set(value) {
            if (checkedValue == value) return
            checkedValue = value
            if (broadcasting) return
            broadcasting = true
            try { checkedListener?.invoke(this, value) } finally { broadcasting = false }
        }
    fun setOnCheckedChangeListener(listener: ((RpnControlState, Boolean) -> Unit)?) { checkedListener = listener }
    fun setOnClickListener(listener: ((RpnControlState) -> Unit)?) { clickListener = listener }
    fun performClick(): Boolean {
        if (!isEnabled || !isClickable || clickListener == null) return false
        clickListener?.invoke(this)
        return true
    }
    fun setTextColor(color: Int) { foregroundColor = color }
    fun setImageResource(resource: Int) { imageRes = resource; drawableValue = null }
    fun setImageDrawable(drawable: Drawable?) { drawableValue = drawable }
    fun setBackgroundResource(resource: Int) { backgroundRes = resource }
    fun setBackgroundColor(color: Int) { backgroundTintList = ColorStateList.valueOf(color) }
    fun getLocationInWindow(out: IntArray) { out[0] = windowX; out[1] = windowY }
    fun setProgressCompat(value: Int, animated: Boolean) { progress = value }
    fun show() { visibility = View.VISIBLE }
    fun hide() { visibility = View.GONE }
    fun startShimmer() { shimmerRunning = true }
    fun stopShimmer() { shimmerRunning = false }
    private val animator by lazy { RpnPropertyAnimator(this) }
    fun animate(): RpnPropertyAnimator = animator
}
