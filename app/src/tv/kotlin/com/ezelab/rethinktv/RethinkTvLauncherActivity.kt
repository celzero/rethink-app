/*
 * Copyright 2025 ezelab (rethink-tv fork)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Minimal Android TV launcher activity for the rethink-tv fork.
 *
 * Phase 2 ("flavor-scaffold") deliverable: proves the `tv` Gradle flavor
 * builds and produces an installable Leanback APK without touching the
 * upstream RethinkDNS engine in `app/src/main/`. The full Compose-for-TV
 * UI replaces this stub in later phases (`tv-ux-dashboard`, `tv-ux-settings`).
 */
class RethinkTvLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val text = TextView(this).apply {
            text = "Rethink TV — scaffold"
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { gravity = Gravity.CENTER }
        }

        root.addView(text)
        setContentView(root)
    }
}
