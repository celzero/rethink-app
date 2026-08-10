package com.celzero.bravedns.receiver

import android.content.Context
import android.content.Intent
import com.celzero.bravedns.service.PersistentState
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VpnControlReceiverTest {

    private lateinit var receiver: VpnControlReceiver
    private val persistentState: PersistentState = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        stopKoin()
        startKoin {
            modules(module {
                single { persistentState }
            })
        }
        receiver = VpnControlReceiver()
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
        val intent = Intent("com.celzero.bravedns.intent.action.VPN_START").apply {
            putExtra("sender", "com.untrusted.app")
        }
        receiver.onReceive(context, intent)
        // Verify handleVpnStart not called (check logs or internal state if possible)
    }
}
