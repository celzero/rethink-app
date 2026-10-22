/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/** Window-space bounds shared by independently composed Home, RPN and navigation. */
class HomeTourAnchors {
    private data class Anchor(val owner: Any, val bounds: RectF, val isShown: () -> Boolean, val reveal: suspend () -> Unit)
    private val anchors = mutableMapOf<Int, Anchor>()
    private val boundsListeners = mutableMapOf<Int, MutableSet<(RectF?) -> Unit>>()
    fun find(targetId: Int): RectF? = anchors[targetId]?.takeIf { it.isShown() }?.bounds?.let(::RectF)?.takeUnless { it.isEmpty }
    suspend fun reveal(targetId: Int) { anchors[targetId]?.takeIf { it.isShown() }?.reveal?.invoke() }
    /** Observe native geometry without a placeholder View; callers dispose with their UI lifetime. */
    fun observeBounds(id: Int, listener: (RectF?) -> Unit): () -> Unit {
        boundsListeners.getOrPut(id) { mutableSetOf() }.add(listener)
        listener(find(id))
        return {
            boundsListeners[id]?.let { listeners ->
                listeners.remove(listener)
                if (listeners.isEmpty()) boundsListeners.remove(id)
            }
        }
    }
    internal fun put(id: Int, owner: Any, bounds: RectF, isShown: () -> Boolean, reveal: suspend () -> Unit) {
        val before = find(id)
        anchors[id] = Anchor(owner, RectF(bounds), isShown, reveal)
        notifyBoundsChanged(id, before)
    }
    internal fun remove(id: Int, owner: Any) {
        if (anchors[id]?.owner !== owner) return
        val before = find(id)
        anchors.remove(id)
        notifyBoundsChanged(id, before)
    }
    private fun notifyBoundsChanged(id: Int, before: RectF?) {
        val after = find(id)
        if (before == after) return
        boundsListeners[id]?.toList()?.forEach { it(after?.let(::RectF)) }
    }
    companion object {
        private val activityAnchors = WeakHashMap<Activity, HomeTourAnchors>()
        fun forActivity(activity: Activity): HomeTourAnchors = activityAnchors.getOrPut(activity) { HomeTourAnchors() }
        fun forContext(context: Context): HomeTourAnchors? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return forActivity(current)
                if (current.baseContext === current) break
                current = current.baseContext
            }
            return (current as? Activity)?.let(::forActivity)
        }
    }
}

fun Modifier.homeTourAnchor(targetId: Int, anchors: HomeTourAnchors? = null): Modifier = composed {
    val registry = anchors ?: HomeTourAnchors.forContext(LocalContext.current)
    val host = LocalView.current
    val owner = remember { Any() }
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(registry, targetId) { onDispose { registry?.remove(targetId, owner) } }
    bringIntoViewRequester(requester).onGloballyPositioned { coordinates ->
        // Full window bounds, including currently clipped content: reveal first,
        // then the controller measures again after the scroll has settled.
        val position = coordinates.localToWindow(Offset.Zero)
        registry?.put(targetId, owner, RectF(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height), { host.isShown }) { requester.bringIntoView() }
    }
}
