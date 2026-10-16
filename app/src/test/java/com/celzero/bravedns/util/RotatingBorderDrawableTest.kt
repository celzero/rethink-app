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

import android.graphics.Color
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotatingBorderDrawableTest {

    private class RecordingCallback : Drawable.Callback {
        var invalidations = 0
        override fun invalidateDrawable(who: Drawable) {
            invalidations++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
    }

    private fun drawable(): RotatingBorderDrawable {
        val d = RotatingBorderDrawable()
        d.configure(Color.RED, 4f, rainbow = true)
        return d
    }

    @Test
    fun `combined update skips work when wrapped values are unchanged`() {
        val d = drawable()
        val callback = RecordingCallback()
        d.callback = callback
        d.setBounds(0, 0, 100, 40)

        // -90 wraps to 270; first call rebuilds and invalidates once
        d.setHighlightHueAndRotation(30f, -90f)
        assertTrue(callback.invalidations >= 1)

        // identical wrapped rotation and hue (raw argument still negative and
        // unbounded): the guard must compare against the stored field, not the
        // raw parameter, so this is a no-op
        callback.invalidations = 0
        d.setHighlightHueAndRotation(30f, -90f)
        d.setHighlightHueAndRotation(30f, 630f) // 630 wraps to 270
        d.setHighlightHueAndRotation(30f, 720f + 270f)
        d.setHighlightHueAndRotation(390f, -90f - 360f) // hue 390 wraps to 30
        assertEquals(0, callback.invalidations)

        // any wrapped change invalidates exactly once
        d.setHighlightHueAndRotation(31f, 270f)
        assertEquals(1, callback.invalidations)
        callback.invalidations = 0
        d.setHighlightHueAndRotation(31f, 271f)
        assertEquals(1, callback.invalidations)
    }

    @Test
    fun `non-rainbow mode ignores hue changes but tracks rotation`() {
        val d = RotatingBorderDrawable()
        d.configure(Color.RED, 4f, rainbow = false)
        val callback = RecordingCallback()
        d.callback = callback
        d.setBounds(0, 0, 100, 40)

        d.setHighlightHueAndRotation(30f, 0f)
        assertTrue(callback.invalidations >= 1)

        callback.invalidations = 0
        // hue-only change: not tracked in non-rainbow mode, rotation unchanged
        d.setHighlightHueAndRotation(90f, 0f)
        assertEquals(0, callback.invalidations)

        d.setHighlightHueAndRotation(90f, 180f)
        assertEquals(1, callback.invalidations)
    }
}
