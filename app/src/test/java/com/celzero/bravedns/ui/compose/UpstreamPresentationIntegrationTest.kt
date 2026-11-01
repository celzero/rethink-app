/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.home.HomePresentation
import com.celzero.bravedns.ui.compose.networksettings.MiscSettingsState
import com.celzero.bravedns.ui.compose.networksettings.NetworkNode
import com.celzero.bravedns.ui.compose.rpn.RpnSelectionState
import com.celzero.bravedns.util.UIUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Presentation contracts for upstream changes retained by the Compose rebase (#3196).
 * These tests deliberately do not claim to exercise controllers, rendering, or VPN state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpstreamPresentationIntegrationTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppThemeWhite)

    @Test fun protectionStatusUsesMonochromeThemeColor() {
        val ctx = context
        val state = HomePresentation(ctx)
        assertEquals(UIUtils.fetchColor(ctx, R.attr.primaryTextColor), state.fhsProtectionLevelTxt.textColor)
        assertNotEquals(UIUtils.fetchColor(ctx, R.attr.accentGood), state.fhsProtectionLevelTxt.textColor)
    }

    @Test fun inactiveProxyHasSeparateHiddenLabelWithoutReplacingActiveHeadline() {
        val ctx = context
        val state = HomePresentation(ctx)
        assertEquals(View.GONE, state.fhsProxyInactiveTxt.visibility)
        assertEquals(ctx.getText(R.string.hsf_proxy_off_indicator), state.fhsProxyInactiveTxt.text)
        assertEquals(0.5f, state.fhsProxyInactiveTxt.alpha, 0f)
        state.fhsCardOtherProxyCount.text = "WireGuard"
        state.fhsCardOtherProxyCount.visibility = View.GONE
        state.fhsProxyHeaderSpace.visibility = View.GONE
        state.fhsProxyInactiveTxt.visibility = View.VISIBLE
        assertTrue(state.fhsProxyInactiveTxt.isVisible)
        assertFalse(state.fhsProxyHeaderSpace.isVisible)
        assertEquals("WireGuard", state.fhsCardOtherProxyCount.text)
        state.fhsProxyInactiveTxt.visibility = View.GONE
        state.fhsCardOtherProxyCount.visibility = View.VISIBLE
        state.fhsProxyHeaderSpace.visibility = View.VISIBLE
        assertTrue(state.fhsCardOtherProxyCount.isVisible)
        assertTrue(state.fhsProxyHeaderSpace.isVisible)
    }

    @Test fun connectionCheckRowAndDividerDefaultHiddenAndRowDispatchesClick() {
        val state = MiscSettingsState(context)
        assertEquals(View.GONE, state.settingsConnectionCheckRl.visibility)
        assertEquals(View.GONE, state.dividerConnectionCheck.visibility)
        val nodes = flatten(state.layout)
        val row = nodes.single { it.key == "network.settings_connection_check_rl" }
        assertSame(state.settingsConnectionCheckRl, row.control)
        assertTrue(nodes.any { it.control === state.dividerConnectionCheck })
        var calls = 0
        state.settingsConnectionCheckRl.setOnClickListener { calls++ }
        state.settingsConnectionCheckRl.visibility = View.VISIBLE
        assertTrue(state.settingsConnectionCheckRl.performClick())
        assertEquals(1, calls)
        assertFalse(state.settingsConnectionCheckRl.isChecked)
    }

    @Test fun remoteServiceTileDefaultsHiddenAndDispatchesOnlyWhenEnabled() {
        val state = RpnSelectionState(context)
        assertEquals(View.GONE, state.qsRemoteServiceTile.visibility)
        var calls = 0
        state.qsRemoteServiceTile.setOnClickListener { calls++ }
        state.qsRemoteServiceTile.visibility = View.VISIBLE
        state.qsRemoteServiceState.text = "On"
        assertTrue(state.qsRemoteServiceTile.performClick())
        assertEquals(1, calls)
        assertEquals("On", state.qsRemoteServiceState.text)
        state.qsRemoteServiceTile.isEnabled = false
        assertFalse(state.qsRemoteServiceTile.performClick())
        assertEquals(1, calls)
    }

    private fun flatten(node: NetworkNode): List<NetworkNode> =
        listOf(node) + node.children.flatMap(::flatten)
}
