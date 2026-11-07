/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.*

/** Observable values/callbacks only. No View, external state or service is owned here. */
@androidx.annotation.Keep
class UtilityControl(val context: Context, val tag: String = "") {
    var translationX by mutableFloatStateOf(0f)
    var translationY by mutableFloatStateOf(0f)
    var rotation by mutableFloatStateOf(0f)
    var scaleX by mutableFloatStateOf(1f)
    var scaleY by mutableFloatStateOf(1f)
    var alpha by mutableFloatStateOf(1f)
    var text: CharSequence by mutableStateOf("")
    var hint: CharSequence by mutableStateOf("")
    var visibility by mutableIntStateOf(View.VISIBLE)
    var isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) { visibility = if (value) View.VISIBLE else View.GONE }
    var isEnabled by mutableStateOf(true)
    var isFocusable by mutableStateOf(true)
    var isFocusableInTouchMode by mutableStateOf(true)
    var isSelected by mutableStateOf(false)
    var error: CharSequence? by mutableStateOf(null)
    var background: Drawable? by mutableStateOf(null)
    var backgroundTintList: ColorStateList? by mutableStateOf(null)
    var colorFilter: Int? by mutableStateOf(null)
    var drawable: Drawable? by mutableStateOf(null)
    var imageRes: Int? by mutableStateOf(null)
    var textColor: Int? by mutableStateOf(null)
    var maxLines: Int = Int.MAX_VALUE
    var maxLength: Int? = null
    var ellipsize: Boolean = false
    var centered: Boolean = false
    var allCaps: Boolean = false
    var textAppearance: Int = 0
    var fontSize: Float? = null
    var fontFamily: String? = null
    var fontStyle: Int? = null
    var letterSpacing: Float? = null
    private var checkedValue by mutableStateOf(false)
    private var broadcasting = false
    private var click: ((UtilityControl) -> Unit)? = null
    private var checked: ((UtilityControl, Boolean) -> Unit)? = null
    private var longClick: ((UtilityControl) -> Boolean)? = null
    private var touch: ((UtilityControl, UtilityTouchEvent) -> Boolean)? = null
    private var editor: ((UtilityControl, Int, Any?) -> Boolean)? = null
    private var slider: ((UtilityControl, Float, Boolean) -> Unit)? = null
    private var floatValue by mutableFloatStateOf(0f)
    var label: (Float) -> String = { it.toString() }
    val children = mutableListOf<UtilityControl>()
    var isChecked: Boolean
        get() = checkedValue
        set(value) {
            if (value == checkedValue) return
            checkedValue = value
            if (broadcasting) return
            broadcasting = true
            try { checked?.invoke(this, value) } finally { broadcasting = false }
        }
    var value: Float
        get() = floatValue
        set(value) { if (value != floatValue) { floatValue = value; slider?.invoke(this, value, false) } }
    fun userValue(value: Float) { if (value != floatValue) { floatValue = value; slider?.invoke(this, value, true) } }
    fun setText(value: Any?) { text = utilityLengthLimitedInput("", value?.toString().orEmpty(), maxLength) }
    fun setText(value: Int) { text = context.getText(value) }
    fun setTextColor(value: Int) { textColor = value }
    fun setImageResource(value: Int) { imageRes = value; drawable = null }
    fun setImageDrawable(value: Drawable?) { drawable = value; imageRes = null }
    fun setColorFilter(value: Int) { colorFilter = value }
    fun setOnClickListener(value: (UtilityControl) -> Unit) { click = value }
    fun setOnCheckedChangeListener(value: (UtilityControl, Boolean) -> Unit) { checked = value }
    fun setOnLongClickListener(value: (UtilityControl) -> Boolean) { longClick = value }
    fun setOnTouchListener(value: (UtilityControl, UtilityTouchEvent) -> Boolean) { touch = value }
    fun setOnEditorActionListener(value: (UtilityControl, Int, Any?) -> Boolean) { editor = value }
    fun setLabelFormatter(value: (Float) -> String) { label = value }
    fun addOnChangeListener(value: (UtilityControl, Float, Boolean) -> Unit) { slider = value }
    fun performClick() { click?.invoke(this) }
    fun performLongClick() = longClick?.invoke(this) ?: false
    fun dispatchTouch(action: Int) = touch?.invoke(this, UtilityTouchEvent(action)) ?: false
    fun dispatchDone() = editor?.invoke(this, android.view.inputmethod.EditorInfo.IME_ACTION_DONE, null) ?: false
    fun clearCheck() { children.forEach { it.isChecked = false } }
}
data class UtilityTouchEvent(val action: Int)
fun saveUtilityControls(out: Bundle, controls: List<UtilityControl>) {
    controls.forEach { out.putString("utility.text.${it.tag}", it.text.toString()); out.putBoolean("utility.checked.${it.tag}", it.isChecked); out.putFloat("utility.value.${it.tag}", it.value) }
}
fun restoreUtilityControls(saved: Bundle?, controls: List<UtilityControl>) {
    if (saved == null) return
    controls.forEach {
        if (saved.containsKey("utility.text.${it.tag}")) it.text = saved.getString("utility.text.${it.tag}").orEmpty()
        if (saved.containsKey("utility.checked.${it.tag}")) it.isChecked = saved.getBoolean("utility.checked.${it.tag}")
        if (saved.containsKey("utility.value.${it.tag}")) it.value = saved.getFloat("utility.value.${it.tag}")
    }
}

/** Mirrors InputFilter.LengthFilter: limit only the changed insertion and retain the suffix. */
internal fun utilityLengthLimitedInput(previous: String, next: String, maximum: Int?): String {
    if (maximum == null || next.length <= maximum) return next
    val prefix = previous.zip(next).takeWhile { (a, b) -> a == b }.size
    var suffix = 0
    while (suffix < previous.length - prefix && suffix < next.length - prefix &&
        previous[previous.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
    val keep = (maximum - prefix - suffix).coerceAtLeast(0)
    var end = (prefix + keep).coerceAtMost(next.length - suffix)
    if (end > prefix && Character.isHighSurrogate(next[end - 1])) end--
    return next.substring(0, prefix) + next.substring(prefix, end) + next.takeLast(suffix)
}
