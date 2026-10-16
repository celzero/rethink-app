/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import android.content.Context
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextSelectionGuardTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun selectableTextView(): TextView =
        TextView(ctx).apply { setTextIsSelectable(true) }

    private fun tree(): ViewGroup =
        FrameLayout(ctx).apply {
            addView(selectableTextView())
            addView(
                FrameLayout(ctx).apply {
                    addView(selectableTextView())
                    addView(EditText(ctx).apply { setTextIsSelectable(true) })
                }
            )
        }

    private fun textViews(root: ViewGroup): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: android.view.View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `apply clears text selection on all TextViews when affected`() {
        val root = tree()

        TextSelectionGuard.apply(root, affected = true)

        val views = textViews(root)
        assertEquals(3, views.size)
        // EditText is deliberately left untouched by the guard.
        views.filter { it !is EditText }.forEach { assertFalse(it.isTextSelectable()) }
    }

    @Test
    fun `apply is a no-op when not affected`() {
        val root = tree()

        TextSelectionGuard.apply(root, affected = false)

        val views = textViews(root)
        assertEquals(3, views.size)
        views.filter { it !is EditText }.forEach { assertTrue(it.isTextSelectable()) }
    }

    @Test
    fun `apply handles null root`() {
        TextSelectionGuard.apply(null, affected = true)
    }
}
