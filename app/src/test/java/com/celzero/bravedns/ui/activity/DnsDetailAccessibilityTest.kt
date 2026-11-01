/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import com.celzero.bravedns.ui.fixture.SafeDnsHostTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class DnsDetailAccessibilityTest : SafeDnsHostTest() {
    @Test fun testDnsDetailAccessibility() {
        assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) is ComposeView)
        listOf("network", "custom").forEach { key ->
            compose.onNodeWithTag("dns-choice-$key").performScrollTo().assertIsDisplayed().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton))
        }
        // Device TalkBack traversal/contrast remains a separate, unexecuted acceptance gate.
    }
}
