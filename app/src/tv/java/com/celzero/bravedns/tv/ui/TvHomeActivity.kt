/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.tv.ui.nav.TvNavScaffold
import com.celzero.bravedns.ui.NotificationHandlerActivity
import com.celzero.bravedns.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Entry-point Activity for the `tv` flavor. Declared with the
 * LEANBACK_LAUNCHER intent filter in `app/src/tv/AndroidManifest.xml`,
 * which is what surfaces the Rethink TV icon in the Android TV launcher
 * row.
 *
 * The Activity is intentionally a thin shell — all UI logic lives in
 * [TvNavScaffold] and its child Composables under
 * `com.celzero.bravedns.tv.ui.*`. Keeping the Activity small means the
 * `tv` flavor reuses the upstream [com.celzero.bravedns.RethinkDnsApplication]
 * Application class (registered in this flavor's manifest as
 * `android:name=".RethinkDnsApplication"`), which in turn brings up
 * Koin with the same module graph the phone build uses. So by the
 * time `setContent` runs, every singleton our Composables pull via
 * Koin (`PersistentState`, repositories, etc.) is already wired up.
 *
 * Navigation inside the TV UI is handled by Navigation-Compose
 * inside [TvNavScaffold]; this Activity hosts the top-level NavHost
 * rather than the TV flavor needing a separate Activity per
 * destination. That keeps the TV-flavor manifest diff against
 * upstream's `full/AndroidManifest.xml` minimal — see
 * [`app/src/tv/AndroidManifest.xml`](../../../../../../AndroidManifest.xml)
 * for the drift policy.
 *
 * Route deep-links arrive via [NotificationHandlerActivity.EXTRA_RPN_ROUTE]
 * (billing-error trampolines); the manifest declares this Activity
 * `singleTask`, so re-launches are delivered to [onNewIntent].
 */
class TvHomeActivity : ComponentActivity() {

    private val persistentState by inject<PersistentState>()

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAppVersion()
        pendingRoute = intent.getStringExtra(NotificationHandlerActivity.EXTRA_RPN_ROUTE)
        setContent {
            TvNavScaffold(
                pendingRoute = pendingRoute,
                onRouteConsumed = {
                    pendingRoute = null
                    // drop the extra so recreation does not re-load the consumed route
                    intent.removeExtra(NotificationHandlerActivity.EXTRA_RPN_ROUTE)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(NotificationHandlerActivity.EXTRA_RPN_ROUTE)
    }

    /**
     * Persists the app version code, mirroring what
     * `HomeScreenActivity` does on phone variants — the TV launcher
     * replaces that activity, so without this the stored version stays
     * at the 0 default forever. Version-gated server endpoints key off
     * this value (e.g. the RPN availability probe compares it against
     * the server's `minvcode` and rejects the app as "unsupported
     * version" when it is 0).
     */
    private fun initAppVersion() {
        val prevVersion = persistentState.appVersion

        // Persisted unconditionally so version-gated server endpoints never
        // observe the 0 default on fresh installs.
        persistAppVersion(prevVersion)

        // Same one-time firewall exemption HomeScreenActivity applies to
        // installs at or below v53 (fresh installs included).
        if (prevVersion <= 53) {
            val rethinkUid = android.os.Process.myUid()
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { FirewallManager.exemptRethinkApp(rethinkUid) }
                    .onFailure {
                        Logger.w(Logger.LOG_TAG_VPN, "tv launcher: self-exemption failed: ${it.message}")
                    }
            }
        }

        // Parity with HomeScreenActivity: purge pre-v055o WARP/SEC_WARP
        // residue rows so they never surface in the TV tunnel lists.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { WireguardManager.deleteResidueWgs() }
        }
    }

    private fun persistAppVersion(prevVersion: Int) {
        persistentState.appVersion = BuildConfig.BASE_VERSION_CODE
        Logger.i(Logger.LOG_TAG_VPN, "tv launcher: app version set to ${BuildConfig.BASE_VERSION_CODE} (prev: $prevVersion)")
    }
}
