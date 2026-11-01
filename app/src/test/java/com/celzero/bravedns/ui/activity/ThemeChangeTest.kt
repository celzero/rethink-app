/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import androidx.compose.ui.test.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.fixture.SafeRootHostTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class ThemeChangeTest : SafeRootHostTest() {
    @Test fun testThemeChangeRecreation() {
        launch()
        compose.onNodeWithTag("fhs_dns_on_off_btn").assertIsDisplayed()
        // Preserve DARK -> LIGHT transitions and extend to every stored theme.
        val themes = listOf(com.celzero.bravedns.util.Themes.DARK, com.celzero.bravedns.util.Themes.LIGHT) +
            com.celzero.bravedns.util.Themes.entries.filter { it != com.celzero.bravedns.util.Themes.DARK && it != com.celzero.bravedns.util.Themes.LIGHT }
        for (theme in themes) {
            compose.runOnIdle { preferences.theme = theme.id }
            recreateHost()
            assertEquals(theme.id, themeId)
            compose.onNodeWithTag("fhs_dns_on_off_btn").assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithTag("root_nav_${R.id.homeScreenFragment}").assertIsSelected()
        }
        assertEquals(themes.map { "theme:${it.id}" }, calls.filter { it.startsWith("theme:") })
    }
}
