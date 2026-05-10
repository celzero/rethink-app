/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ezelab.rethinktv.ui.home.TvHomeApp

/**
 * Entry-point Activity for the `tv` flavor. Declared with the
 * LEANBACK_LAUNCHER intent filter in `app/src/tv/AndroidManifest.xml`,
 * which is what surfaces the Rethink TV icon in the Android TV launcher
 * row.
 *
 * The Activity is intentionally a thin shell — all UI logic lives in
 * [TvHomeApp] and its child composables under
 * `com.ezelab.rethinktv.ui.home`. Keeping the Activity small means the
 * `tv` flavor reuses the upstream [com.celzero.bravedns.RethinkDnsApplication]
 * Application class (registered in this flavor's manifest as
 * `android:name=".RethinkDnsApplication"`), which in turn brings up Koin
 * with the same module graph the phone build uses. So by the time
 * `setContent` runs, every singleton our Composables pull via Koin
 * (`PersistentState`, repositories, etc.) is already wired up.
 */
class TvHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvHomeApp() }
    }
}
