/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose

import androidx.annotation.IdRes
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Framework-only bridge for a fixed Activity-owned Fragment host. All screen/chrome content is
 * Compose. This is deliberately not an XML screen wrapped in AndroidView.
 *
 * AndroidFragment generates a container ID from its composition key and owns removal. These
 * existing Activity hosts must instead retain their resource IDs, FragmentManager saved state,
 * and (for Home) the primary NavHost and its child back stack across recreation and upgrades.
 * Use the same container-aware Fragment APIs as fragment-compose, but let the Activity retain
 * ownership. Keep this host in the composition for the entire Activity lifetime.
 */
@Composable
internal fun ActivityFragmentContainer(
    fragmentManager: FragmentManager,
    @IdRes containerId: Int,
    modifier: Modifier = Modifier,
    primaryNavigationHost: Boolean = false,
    fragmentTag: (Fragment) -> String? = { null },
    createFragment: () -> Fragment,
    onFragmentReady: (Fragment) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready = rememberUpdatedState(onFragmentReady)
    val container = remember(context, fragmentManager, containerId) {
        FragmentContainerView(context).apply { id = containerId }
    }
    // Own any safe edges not already reserved by the Activity chrome. AndroidView
    // subtracts this measured padding when dispatching insets to the Fragment, so
    // nested Compose screens can also request safeDrawing without double padding.
    AndroidView(factory = { container }, modifier = modifier.safeDrawingPadding())

    DisposableEffect(fragmentManager, container, lifecycle) {
        var installed = false
        fun install() {
            if (installed || fragmentManager.isDestroyed || fragmentManager.isStateSaved) return
            val fragment = fragmentManager.findFragmentById(containerId) ?: createFragment().also {
                fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    // The ViewGroup overload supplies the container even before the Compose
                    // AndroidView is attached to the Activity's view hierarchy.
                    .add(container, it, fragmentTag(it))
                    .apply { if (primaryNavigationHost) setPrimaryNavigationFragment(it) }
                    .commitNow()
            }
            // Reconnect an automatically restored Fragment view to the new Compose container.
            fragmentManager.onContainerAvailable(container)
            if (primaryNavigationHost && fragmentManager.primaryNavigationFragment !== fragment) {
                fragmentManager.beginTransaction().setPrimaryNavigationFragment(fragment).commitNow()
            }
            installed = true
            ready.value(fragment)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) install()
        }
        lifecycle.addObserver(observer)
        install()
        onDispose {
            lifecycle.removeObserver(observer)
            // FragmentManager, rather than composition disposal, owns saving/removing this host.
        }
    }
}
