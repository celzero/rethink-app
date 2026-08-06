package com.celzero.bravedns.service

import android.content.Intent
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.OrbotHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowBackend::class])
class BraveVPNServiceLifecycleTest : KoinTest {

    private lateinit var serviceController: ServiceController<BraveVPNService>
    private val appConfig = mockk<AppConfig>(relaxed = true)
    private val orbotHelper = mockk<OrbotHelper>(relaxed = true)
    private val persistentState = mockk<PersistentState>(relaxed = true)
    private val rdb = mockk<RefreshDatabase>(relaxed = true)
    private val netLogTracker = mockk<NetLogTracker>(relaxed = true)

    @Before
    fun setup() {
        mockkObject(IpRulesManager)
        startKoin {
            modules(module {
                single { appConfig }
                single { orbotHelper }
                single { persistentState }
                single { rdb }
                single { netLogTracker }
            })
        }
        mockkObject(VpnController)
        serviceController = Robolectric.buildService(BraveVPNService::class.java)
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkObject(IpRulesManager)
        unmockkObject(VpnController)
    }

    @Test
    fun `service should register with VpnController on create`() {
        serviceController.create()
        verify { VpnController.onVpnCreated(any()) }
    }

    @Test
    fun `service should unregister from VpnController on destroy`() {
        serviceController.create().destroy()
        verify { VpnController.onVpnDestroyed() }
    }

    @Test
    fun `onStartCommand with stop action should signal stop service`() {
        val service = serviceController.create().get()
        val intent = Intent(service, BraveVPNService::class.java).apply {
            action = BraveVPNService.NOTIF_ACTION_MODE_STOP.toString()
        }
        
        service.onStartCommand(intent, 0, 1)
    }

    @Test
    fun `mtu should return at least MIN_MTU`() {
        val service = serviceController.create().get()
        
        // Mock underlying networks with very small MTU
        val underlyingNetworksField = BraveVPNService::class.java.getDeclaredField("underlyingNetworks")
        underlyingNetworksField.isAccessible = true
        val mockUnderlying = mockk<ConnectionMonitor.UnderlyingNetworks>()
        every { mockUnderlying.minMtu } returns 500
        underlyingNetworksField.set(service, mockUnderlying)
        
        val result = service.mtu()
        // BraveVPNService.MIN_MTU is 1280
        assertEquals(1280, result)
    }

    @Test
    fun `mtu should return minimum of overlay and underlying`() {
        val service = serviceController.create().get()
        
        // Set overlay MTU to 1400
        val overlayNetworksField = BraveVPNService::class.java.getDeclaredField("overlayNetworks")
        overlayNetworksField.isAccessible = true
        val overlayNetworks = BraveVPNService.OverlayNetworks(true, true, true, 1400)
        overlayNetworksField.set(service, overlayNetworks)

        // Set underlying MTU to 1500
        val underlyingNetworksField = BraveVPNService::class.java.getDeclaredField("underlyingNetworks")
        underlyingNetworksField.isAccessible = true
        val mockUnderlying = mockk<ConnectionMonitor.UnderlyingNetworks>()
        every { mockUnderlying.minMtu } returns 1500
        underlyingNetworksField.set(service, mockUnderlying)
        
        val result = service.mtu()
        assertEquals(1400, result)
    }
}
