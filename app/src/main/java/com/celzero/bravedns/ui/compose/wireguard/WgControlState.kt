/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf

/** Presentation state only: no View, service or persistence is owned here. */
class WgControlState(val context: Context? = null, val checkable: Boolean = false, val textSource: String? = null) {
    private var textValue: CharSequence by mutableStateOf("")
    var maxLength: Int? = null
    internal var maxLinesOverride: Int? by mutableStateOf(null)
    var maxLines: Int
        get() = maxLinesOverride ?: Int.MAX_VALUE
        set(value) { maxLinesOverride = value }
    internal var ellipsizeOverride: Boolean? by mutableStateOf(null)
    var ellipsize: Boolean
        get() = ellipsizeOverride ?: false
        set(value) { ellipsizeOverride = value }
    var text: CharSequence
        get() = textValue
        set(value) {
            var end = maxLength?.coerceAtLeast(0)?.coerceAtMost(value.length) ?: value.length
            if (end < value.length && end > 0 && Character.isHighSurrogate(value[end - 1])) end--
            textValue = if (end == value.length) value else value.subSequence(0, end)
        }
    var hint: CharSequence by mutableStateOf("")
    var visibility by mutableStateOf(View.VISIBLE)
    var isEnabled by mutableStateOf(true)
    var isClickable by mutableStateOf(true)
    var isFocusable by mutableStateOf(true)
    var isAllCaps by mutableStateOf(false)
    var isPassword by mutableStateOf(false)
    var noSuggestions by mutableStateOf(false)
    var imeNext by mutableStateOf(false)
    internal var textStyleOverride: Int? by mutableStateOf(null)
    var textStyle: Int
        get() = textStyleOverride ?: 0
        set(value) { textStyleOverride = value }
    var textAppearanceStyle: Int? by mutableStateOf(null)
    var fontFamilyName: String? by mutableStateOf(null)
    var letterSpacingEm: Float? by mutableStateOf(null)
    var lineSpacingExtraDp: Float? by mutableStateOf(null)
    var lineSpacingMultiplier: Float? by mutableStateOf(null)
    var elevationDp: Float? by mutableStateOf(null)
    var isSelected by mutableStateOf(false)
    var alpha by mutableStateOf(1f)
    var switchThemeOverlay: Int? = null
    var buttonTint: Int? by mutableStateOf(null)
    var textColor: Int? by mutableStateOf(null)
    var backgroundColor: Int? by mutableStateOf(null)
    var backgroundTintList: ColorStateList? by mutableStateOf(null)
    var strokeColor by mutableStateOf(0)
    var strokeWidth by mutableStateOf(0)
    var radiusDp by mutableStateOf(8f)
    var textSizeSp: Float? by mutableStateOf(null)
    var drawable: Drawable? by mutableStateOf(null)
    var imageResource: Int? by mutableStateOf(null)
    var secondaryProgress by mutableStateOf(0)
    var progress by mutableStateOf(0)
    var animateProgress by mutableStateOf(false)
    var max by mutableStateOf(100)
    var indicatorColor: Int? by mutableStateOf(null)
    var isIndeterminate by mutableStateOf(false)
    var progressSource: String? = null
    var trackColor: Int? by mutableStateOf(null)
    var trackThicknessDp: Float? by mutableStateOf(null)
    var trackCornerRadiusDp: Float? by mutableStateOf(null)
    var indicatorTrackGapDp: Float? by mutableStateOf(null)
    var trackStopIndicatorSizeDp: Float? by mutableStateOf(null)
    var adapter: WgListContent? by mutableStateOf(null)
    var onTextChanged: ((CharSequence) -> Unit)? = null
    var onEndIconClick: (() -> Unit)? = null
    var onEditorDone: (() -> Unit)? = null
    private var click: ((WgControlState) -> Unit)? = null
    private var checkedListener: ((WgControlState, Boolean) -> Unit)? = null
    // Do not call this property `checked`: that collides with isChecked's JVM setter.
    private var checkedValue by mutableStateOf(false)
    private var broadcasting = false
    var isChecked: Boolean
        get() = checkedValue
        set(value) {
            if (checkedValue == value) return
            checkedValue = value
            if (broadcasting) return
            broadcasting = true
            try { checkedListener?.invoke(this, value) } finally { broadcasting = false }
        }
    val isVisible get() = visibility == View.VISIBLE
    val hasClickListener get() = click != null
    fun setText(value: Any?, bufferType: Any? = null) {
        val newText = (value as? CharSequence) ?: value?.toString().orEmpty()
        text = newText
        onTextChanged?.invoke(text)
    }
    fun setText(resource: Int) = setText(requireNotNull(context) { "Resource text requires a Context" }.getText(resource) as Any)
    fun setTextColor(value: Int) { textColor = value }
    fun setCardBackgroundColor(value: Int) { backgroundColor = value }
    fun setImageDrawable(value: Drawable?) { drawable = value }
    fun setImageResource(value: Int) { imageResource = value }
    fun setIndicatorColor(value: Int) { indicatorColor = value }
    fun setProgress(value: Int, animate: Boolean) { progress = value; animateProgress = animate }
    fun setOnClickListener(listener: ((WgControlState) -> Unit)?) { click = listener }
    fun setOnCheckedChangeListener(listener: ((WgControlState, Boolean) -> Unit)?) { checkedListener = listener }
    fun setEndIconOnClickListener(listener: (WgControlState) -> Unit) { onEndIconClick = { listener(this) } }
    fun doOnTextChanged(listener: (CharSequence?, Int, Int, Int) -> Unit) { onTextChanged = { listener(it, 0, 0, it.length) } }
    fun addTextChangedListener(listener: (CharSequence?) -> Unit) { onTextChanged = { listener(it) } }
    /** Framework programmatic clicks do not consult enabled; Compose gates user input. */
    fun performClick(): Boolean { if (checkable) isChecked = !isChecked; return callOnClick() }
    fun callOnClick(): Boolean { val action = click ?: return false; action(this); return true }
    fun postDelayed(action: () -> Unit, delayMillis: Long): Boolean =
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(action, delayMillis)
    fun startShimmer() { isIndeterminate = true }
    fun stopShimmer() { isIndeterminate = false }
}
