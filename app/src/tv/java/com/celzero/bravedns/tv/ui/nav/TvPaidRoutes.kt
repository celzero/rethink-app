/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * Bridge between the channel-agnostic TV nav shell and the paid RPN
 * sub-graph that only exists in billing-capable variants.
 *
 * The checkout/dashboard/manage screens live in the `tvBilling`
 * source set (`app/src/tvBilling/`, grafted onto playTv and websiteTv
 * in `app/build.gradle.kts`) because they import Google-Play-billing
 * classes that the fdroid channel cannot link. This `tv`-source-set
 * shell therefore cannot reference them directly. Instead, billing-capable
 * TV variants register an init ContentProvider (`RpnTvInitProvider`,
 * declared in `src/playTv/AndroidManifest.xml` and
 * `src/websiteTv/AndroidManifest.xml`) which installs the graph builder
 * here at process start — before any Compose code runs. Variants without
 * that provider keep `null` here and neither the "RPN" rail entry
 * nor the paid routes materialize.
 */
object TvPaidRoutes {

    /**
     * Registers the paid `rpn` composables into the main NavHost.
     * Invoked once from [TvNavScaffold]'s NavHost builder; assigned only
     * by the billing-capable TV init provider.
     */
    @Volatile
    var rpnGraphBuilder: (NavGraphBuilder.(NavController) -> Unit)? = null
        private set

    /** Installs [builder]; idempotent (first writer wins). */
    fun install(builder: NavGraphBuilder.(NavController) -> Unit) {
        if (rpnGraphBuilder == null) {
            rpnGraphBuilder = builder
        }
    }

    /** True when a paid graph has been installed for this process. */
    val isInstalled: Boolean
        get() = rpnGraphBuilder != null
}
