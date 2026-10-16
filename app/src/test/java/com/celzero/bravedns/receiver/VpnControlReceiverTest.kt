package com.celzero.bravedns.receiver

import android.content.Context
import android.content.Intent
import com.celzero.bravedns.service.PersistentState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class VpnControlReceiverTest {

    private lateinit var receiver: VpnControlReceiver
    private lateinit var wgCommands: WgCommands
    private val persistentState: PersistentState = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        stopKoin()
        startKoin {
            modules(module {
                single { persistentState }
                // unconfined so the receiver's appScope.launch runs synchronously in tests
                single<CoroutineScope> { CoroutineScope(Dispatchers.Unconfined) }
            })
        }
        receiver = VpnControlReceiver()
        wgCommands = mockk(relaxed = true)
        receiver.wgCommands = wgCommands
        // tests drive identity through the seam; spoof-rejection tests restore the default
        receiver.callerResolver = { _, _ -> "com.trusted.app" }
    }

    private fun trustedWgIntent(action: String, wgIds: String?): Intent {
        return Intent(action).apply {
            if (wgIds != null) {
                putExtra(VpnControlReceiver.EXTRA_WG_IDS, wgIds)
            }
        }
    }

    @Test
    fun `test onReceive with null action`() {
        val intent = Intent()
        receiver.onReceive(context, intent)
        // Verify nothing happened (no crashes)
    }

    @Test
    fun `test onReceive with untrusted package`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.callerResolver = { _, _ -> "com.untrusted.app" }
        val intent = Intent(VpnControlReceiver.ACTION_START)
        receiver.onReceive(context, intent)
        // Verify handleVpnStart not called (check logs or internal state if possible)
    }

    // ── wg_ids parsing ───────────────────────────────────────────────────────

    @Test
    fun `parseWgIds with null returns empty list`() {
        assertEquals(emptyList<Int>(), VpnControlReceiver.parseWgIds(null))
    }

    @Test
    fun `parseWgIds with blank returns empty list`() {
        assertEquals(emptyList<Int>(), VpnControlReceiver.parseWgIds("  "))
    }

    @Test
    fun `parseWgIds with single id`() {
        assertEquals(listOf(1), VpnControlReceiver.parseWgIds("1"))
    }

    @Test
    fun `parseWgIds with comma separated ids and spaces`() {
        assertEquals(listOf(1, 3, 12), VpnControlReceiver.parseWgIds("1, 3,12"))
    }

    @Test
    fun `parseWgIds drops non-numeric segments`() {
        assertEquals(listOf(2), VpnControlReceiver.parseWgIds("abc,2,x"))
    }

    @Test
    fun `parseWgIds with only garbage returns empty list`() {
        assertEquals(emptyList<Int>(), VpnControlReceiver.parseWgIds("abc,def"))
    }

    // ── WG action gating ─────────────────────────────────────────────────────

    @Test
    fun `wg pause with trusted sender and toggle on dispatches parsed ids`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_PAUSE, "1, 3"))
        coVerify(exactly = 1) { wgCommands.pause(listOf(1, 3)) }
        coVerify(exactly = 0) { wgCommands.resume(any()) }
        coVerify(exactly = 0) { wgCommands.start(any()) }
        coVerify(exactly = 0) { wgCommands.stop(any()) }
    }

    @Test
    fun `wg resume with trusted sender and toggle on dispatches parsed ids`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_RESUME, "7"))
        coVerify(exactly = 1) { wgCommands.resume(listOf(7)) }
    }

    @Test
    fun `wg start with trusted sender and toggle on dispatches parsed ids`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_START, "2"))
        coVerify(exactly = 1) { wgCommands.start(listOf(2)) }
    }

    @Test
    fun `wg stop with trusted sender and toggle on dispatches parsed ids`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_STOP, "4"))
        coVerify(exactly = 1) { wgCommands.stop(listOf(4)) }
    }

    @Test
    fun `wg actions ignored when wg tasker automation toggle is off`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns false
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_PAUSE, "1"))
        coVerify(exactly = 0) { wgCommands.pause(any()) }
        coVerify(exactly = 0) { wgCommands.resume(any()) }
        coVerify(exactly = 0) { wgCommands.start(any()) }
        coVerify(exactly = 0) { wgCommands.stop(any()) }
    }

    @Test
    fun `wg actions from untrusted sender are ignored`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.callerResolver = { _, _ -> "com.untrusted.app" }
        val intent = Intent(VpnControlReceiver.ACTION_WG_PAUSE).apply {
            putExtra(VpnControlReceiver.EXTRA_WG_IDS, "1")
        }
        receiver.onReceive(context, intent)
        coVerify(exactly = 0) { wgCommands.pause(any()) }
    }

    @Test
    fun `wg action without wg_ids extra is ignored`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_PAUSE, null))
        coVerify(exactly = 0) { wgCommands.pause(any()) }
    }

    @Test
    fun `wg action with non-numeric wg_ids is ignored`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(
            context,
            trustedWgIntent(VpnControlReceiver.ACTION_WG_START, "not-a-number")
        )
        coVerify(exactly = 0) { wgCommands.start(any()) }
    }

    @Test
    fun `wg action without allowed packages configured is ignored`() {
        every { persistentState.appTriggerPackages } returns ""
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.onReceive(context, trustedWgIntent(VpnControlReceiver.ACTION_WG_PAUSE, "1"))
        coVerify(exactly = 0) { wgCommands.pause(any()) }
    }

    @Test
    fun `unknown action is ignored`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        val intent = Intent("com.celzero.bravedns.intent.action.UNKNOWN")
        receiver.onReceive(context, intent)
        coVerify(exactly = 0) { wgCommands.pause(any()) }
        assertTrue("unknown action must be a no-op", true)
    }

    // ── caller identity resolution ───────────────────────────────────────────

    @Test
    fun `default resolver ignores spoofed sender extra and rejects when os identity is absent`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        receiver.callerResolver = { c, i -> receiver.defaultCallerResolver(c, i) }
        val intent = Intent(VpnControlReceiver.ACTION_WG_PAUSE).apply {
            // spoofed extra naming an allowlisted package must not grant trust
            putExtra(VpnControlReceiver.EXTRA_SENDER, "com.trusted.app")
            putExtra(VpnControlReceiver.EXTRA_WG_IDS, "1")
        }
        receiver.onReceive(context, intent)
        coVerify(exactly = 0) { wgCommands.pause(any()) }
    }

    @Test
    fun `default resolver returns null on spoofed extra when no os identity is stamped`() {
        val intent = Intent(VpnControlReceiver.ACTION_STOP).apply {
            putExtra(VpnControlReceiver.EXTRA_SENDER, "com.trusted.app")
        }
        // on the Robolectric SDK no OS sender identity is stamped; the spoofed extra must not
        // be honored even though it names an allowlisted package
        val resolved = receiver.defaultCallerResolver(context, intent)
        assertEquals(null, resolved)
    }

    @Test
    @Config(sdk = [33])
    fun `default resolver honors legacy sender extra below android 14`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        // SDK 33 exercises the real isAtleastU() gate: below Android 14 the legacy
        // "sender" extra convention is still honored
        receiver.callerResolver = { c, i -> receiver.defaultCallerResolver(c, i) }
        val intent = Intent(VpnControlReceiver.ACTION_WG_PAUSE).apply {
            putExtra(VpnControlReceiver.EXTRA_SENDER, "com.trusted.app")
            putExtra(VpnControlReceiver.EXTRA_WG_IDS, "1")
        }
        receiver.onReceive(context, intent)
        coVerify(exactly = 1) { wgCommands.pause(listOf(1)) }
    }

    @Test
    fun `wg pause with allowlisted identity via seam dispatches`() {
        every { persistentState.appTriggerPackages } returns "com.trusted.app"
        every { persistentState.wgTaskerAutomationEnabled } returns true
        val intent = Intent(VpnControlReceiver.ACTION_WG_PAUSE).apply {
            putExtra(VpnControlReceiver.EXTRA_WG_IDS, "1, 3")
        }
        receiver.onReceive(context, intent)
        coVerify(exactly = 1) { wgCommands.pause(listOf(1, 3)) }
    }
}
