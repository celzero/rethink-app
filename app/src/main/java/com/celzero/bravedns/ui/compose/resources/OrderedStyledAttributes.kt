/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.resources

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.util.TypedValue

/**
 * Android's style lookup requires ascending resource IDs. Presentation resolvers group attributes
 * by purpose instead; sort only the platform query and map every access back to declaration order.
 * Never sort a caller's array in place: its numeric indices and explicit-null checks must stay valid.
 * https://developer.android.com/reference/android/content/res/Resources.Theme#obtainStyledAttributes(int[])
 */
internal class AttributeOrder(attributes: IntArray) {
    val sortedIds: IntArray = attributes.distinct().sorted().toIntArray()
    private val declaredToSorted = IntArray(attributes.size) { sortedIds.binarySearch(attributes[it]) }
    fun index(declaredIndex: Int): Int = declaredToSorted[declaredIndex]
}

/** Deliberately exposes only the TypedArray operations used by presentation code. */
internal class OrderedStyledAttributes(private val values: TypedArray, private val order: AttributeOrder) {
    fun hasValue(index: Int) = values.hasValue(order.index(index))
    fun hasValueOrEmpty(index: Int) = values.hasValueOrEmpty(order.index(index))
    fun peekValue(index: Int) = values.peekValue(order.index(index))
    fun getValue(index: Int, outValue: TypedValue): Boolean = values.getValue(order.index(index), outValue)
    fun getResourceId(index: Int, fallback: Int) = values.getResourceId(order.index(index), fallback)
    fun getDimension(index: Int, fallback: Float) = values.getDimension(order.index(index), fallback)
    fun getDimensionPixelSize(index: Int, fallback: Int) = values.getDimensionPixelSize(order.index(index), fallback)
    fun getBoolean(index: Int, fallback: Boolean) = values.getBoolean(order.index(index), fallback)
    fun getInt(index: Int, fallback: Int) = values.getInt(order.index(index), fallback)
    fun getFloat(index: Int, fallback: Float) = values.getFloat(order.index(index), fallback)
    fun getColor(index: Int, fallback: Int) = values.getColor(order.index(index), fallback)
    fun getColorStateList(index: Int) = values.getColorStateList(order.index(index))
    fun getDrawable(index: Int) = values.getDrawable(order.index(index))
    fun getString(index: Int) = values.getString(order.index(index))
    fun recycle() = values.recycle()
}

internal fun Context.obtainOrderedStyledAttributes(attributes: IntArray): OrderedStyledAttributes {
    val order = AttributeOrder(attributes)
    return OrderedStyledAttributes(obtainStyledAttributes(order.sortedIds), order)
}

internal fun Context.obtainOrderedStyledAttributes(style: Int, attributes: IntArray): OrderedStyledAttributes {
    val order = AttributeOrder(attributes)
    return OrderedStyledAttributes(obtainStyledAttributes(style, order.sortedIds), order)
}

internal fun Context.obtainOrderedStyledAttributes(set: AttributeSet?, attributes: IntArray,
    defaultStyleAttribute: Int, defaultStyleResource: Int): OrderedStyledAttributes {
    val order = AttributeOrder(attributes)
    return OrderedStyledAttributes(obtainStyledAttributes(set, order.sortedIds, defaultStyleAttribute, defaultStyleResource), order)
}
