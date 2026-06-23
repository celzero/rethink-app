/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

/**
 * Drop-in replacement for [androidx.compose.runtime.livedata.observeAsState] that
 * survives upstream's "mutate-in-place" LiveData pattern.
 *
 * Why this exists
 * ---------------
 * Several upstream singletons (`FirewallManager`,
 * `WireguardManager`, `ProxyManager`) mutate cached objects in place
 * and then call `liveData.postValue(snapshotOfSameReferences())`.
 * That tickles a Compose snapshot bug:
 *
 *   * `observeAsState` writes into a `MutableState` that uses
 *     [androidx.compose.runtime.structuralEqualityPolicy].
 *   * Comparing old vs new emission returns "equal" because every
 *     element is the same `AppInfo` reference, just mutated.
 *   * Compose decides "no change" and skips recomposition — the
 *     screen freezes until forcibly rebuilt.
 *
 * The fix is to subscribe ourselves and project each emission through
 * a caller-supplied [transform] that converts mutable upstream data
 * classes into snapshotted immutable view-models. When fields the UI
 * cares about flip, the transform's output is structurally
 * non-equal, Compose recomposes, the UI updates.
 *
 * Usage
 * -----
 * ```
 * data class AppRow(val uid: Int, val name: String, val blocked: Boolean)
 *
 * val rows by rememberAsImmutableState(
 *     liveData = FirewallManager.getApplistObserver(),
 *     initial = emptyList(),
 * ) { apps -> apps.orEmpty().map { AppRow(it.uid, it.appName, it.isBlocked) } }
 * ```
 */
@Composable
fun <T, R> rememberAsImmutableState(
    liveData: LiveData<T>,
    initial: R,
    transform: (T?) -> R,
): State<R> {
    // Re-key on `liveData` reference so a re-emission of the same
    // observer (rare but possible during configuration changes)
    // re-establishes the subscription rather than leaking it.
    val ld = remember(liveData) { liveData }
    return produceState(initialValue = initial, ld) {
        val observer = Observer<T> { value = transform(it) }
        ld.observeForever(observer)
        awaitDispose { ld.removeObserver(observer) }
    }
}

/**
 * Convenience overload for the common case where the upstream
 * LiveData already carries a value the UI can render verbatim (no
 * mutate-in-place hazard). Still uses `produceState` so the
 * subscription lifecycle is tied to the composition, not to a
 * `LifecycleOwner` that may not exist (e.g. inside a Compose preview).
 */
@Composable
fun <T> LiveData<T>.collectAsImmutableState(initial: T): State<T> =
    rememberAsImmutableState(liveData = this, initial = initial) { it ?: initial }
