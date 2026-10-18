/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor

internal fun <T : Fragment> FragmentActivity.setSingleSettingsContent(fragmentClass: Class<T>) {
    setContentView(ComposeView(this).apply {
        // Each settings Fragment owns its Compose page. Keep native insets available to it.
        consumeWindowInsets = false
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SingleSettingsFragmentHost(supportFragmentManager, fragmentClass)
        }
    })
}

/**
 * Framework-only lifecycle container for an already-Compose settings page, never an XML
 * screen wrapper. The previous one-page ViewPager2 added its fragment with tag "f0" and
 * no container ID. Keep that restored instance, including its saved state and ViewModels.
 * AndroidFragment's generated container/tag cannot adopt that legacy identity.
 */
@Composable
private fun <T : Fragment> SingleSettingsFragmentHost(
    fragmentManager: FragmentManager,
    fragmentClass: Class<T>,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val container = remember(context, fragmentManager) {
        FragmentContainerView(context).apply {
            // Do not reuse the old pager ID: its saved ViewPager2 state is a different type.
            id = R.id.fragment_container
        }
    }
    RethinkTheme {
        AndroidView(
            factory = { container },
            modifier = Modifier.fillMaxSize().background(themeColor(R.attr.background)),
        )
    }

    DisposableEffect(fragmentManager, container, lifecycle, fragmentClass) {
        var installed = false
        fun install() {
            if (installed || fragmentManager.isDestroyed || fragmentManager.isStateSaved) return
            val restored = fragmentManager.findFragmentById(container.id)
                ?: fragmentManager.findFragmentByTag(SETTINGS_PAGE_TAG)
            val fragment = restored ?: fragmentManager.fragmentFactory.instantiate(
                context.classLoader,
                fragmentClass.name,
            )
            check(fragment.javaClass == fragmentClass) { "Unexpected settings Fragment" }

            if (restored == null) {
                fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(container, fragment, SETTINGS_PAGE_TAG)
                    .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                    .commitNow()
            } else if (fragment.id == 0) {
                check(fragment.tag == SETTINGS_PAGE_TAG && fragment.isAdded)
                // FragmentStateAdapter manually attached this page to its holder. Detach
                // only this verified legacy page if a holder view still exists.
                fragment.view?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
                // One reordered remove/add rebinds the SAME zero-ID instance. Reordering is
                // required so it is not destroyed between removal and addition. Keep "f0":
                // FragmentTransaction rejects changing an existing fragment's tag.
                fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .remove(fragment)
                    .add(container, fragment, SETTINGS_PAGE_TAG)
                    .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                    .commitNow()
            } else {
                check(fragment.id == container.id && fragment.tag == SETTINGS_PAGE_TAG)
                fragmentManager.beginTransaction()
                    .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                    .commitNow()
            }
            fragmentManager.onContainerAvailable(container)
            installed = true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) install()
        }
        lifecycle.addObserver(observer)
        install()
        onDispose {
            lifecycle.removeObserver(observer)
            // Activity FragmentManager owns saving/removing the page across recreation.
        }
    }
}

private const val SETTINGS_PAGE_TAG = "f0"
