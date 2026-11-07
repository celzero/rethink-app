/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.trafficlogs

import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.runtime.*

/** Observable presentation values. This class is not a View and never loads an XML layout. */
class LogControlState(val checkable: Boolean = false) {
    var text: CharSequence? by mutableStateOf("")
    var visibility by mutableIntStateOf(View.VISIBLE)
    var isEnabled by mutableStateOf(true)
    var alpha by mutableFloatStateOf(1f)
    var isSelected by mutableStateOf(false)
    var imageResource: Int? by mutableStateOf(null)
    var drawable: Drawable? by mutableStateOf(null)
    var colorFilter: ColorFilter? by mutableStateOf(null)
    var textColor: Int? by mutableStateOf(null)
    var backgroundColor: Int? by mutableStateOf(null)
    var tag: Any? = null
    var glyph by mutableStateOf(LogRuleGlyph.NEUTRAL)
    private var checkedValue by mutableStateOf(false)
    private var broadcasting = false
    private var click: ((LogControlState) -> Unit)? = null
    private var change: ((LogControlState, Boolean) -> Unit)? = null
    var isChecked: Boolean
        get() = checkedValue
        set(value) {
            if (checkedValue == value) return
            checkedValue = value
            if (broadcasting) return
            broadcasting = true
            try { change?.invoke(this, value) } finally { broadcasting = false }
        }
    val isVisible get() = visibility == View.VISIBLE
    fun setTextColor(color: Int) { textColor = color }
    fun setBackgroundColor(color: Int) { backgroundColor = color }
    fun setImageResource(value: Int) { imageResource = value }
    fun setImageDrawable(value: Drawable?) { drawable = value }
    fun setRuleState(value: LogRuleGlyph) { glyph = value }
    fun setOnClickListener(action: ((LogControlState) -> Unit)?) { click = action }
    fun setOnCheckedChangeListener(action: ((LogControlState, Boolean) -> Unit)?) { change = action }
    val hasClickListener get() = click != null
    fun performClick() { if (checkable) isChecked = !isChecked; click?.invoke(this) }
}
enum class LogRuleGlyph { NEUTRAL, OFF, ON }
