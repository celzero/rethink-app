package com.celzero.bravedns.data

import android.content.Context
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsCryptEndpointRepository
import com.celzero.bravedns.database.DnsCryptRelayEndpointRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.DnsProxyEndpointRepository
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.database.DoTEndpointRepository
import com.celzero.bravedns.database.ODoHEndpointRepository
import com.celzero.bravedns.database.ProxyEndpointRepository
import com.celzero.bravedns.database.RethinkDnsEndpointRepository
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.PcapMode
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppConfigProxyModeTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun `addProxy merges custom socks5 and http into http_socks5`() {
        val backing = Backing()
        val appConfig = newAppConfig(backing)

        appConfig.addProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
        assertEquals(AppConfig.ProxyType.SOCKS5.name, backing.proxyType)
        assertEquals(AppConfig.ProxyProvider.CUSTOM.name, backing.proxyProvider)

        appConfig.addProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
        assertEquals(AppConfig.ProxyType.HTTP_SOCKS5.name, backing.proxyType)
        assertEquals(AppConfig.ProxyProvider.CUSTOM.name, backing.proxyProvider)
    }

    @Test
    fun `removeProxy from http_socks5 keeps other custom proxy`() {
        val backing =
            Backing(
                proxyProvider = AppConfig.ProxyProvider.CUSTOM.name,
                proxyType = AppConfig.ProxyType.HTTP_SOCKS5.name
            )
        val appConfig = newAppConfig(backing)

        appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
        assertEquals(AppConfig.ProxyType.SOCKS5.name, backing.proxyType)

        backing.proxyType = AppConfig.ProxyType.HTTP_SOCKS5.name
        appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
        assertEquals(AppConfig.ProxyType.HTTP.name, backing.proxyType)
    }

    @Test
    fun `getTunProxyMode prioritizes provider and maps combined type to socks5`() {
        val orbotBacking =
            Backing(
                proxyProvider = AppConfig.ProxyProvider.ORBOT.name,
                proxyType = AppConfig.ProxyType.HTTP.name
            )
        val orbotConfig = newAppConfig(orbotBacking)
        assertEquals(AppConfig.TunProxyMode.ORBOT, orbotConfig.getTunProxyMode())

        val customBacking =
            Backing(
                proxyProvider = AppConfig.ProxyProvider.CUSTOM.name,
                proxyType = AppConfig.ProxyType.HTTP_SOCKS5.name
            )
        val customConfig = newAppConfig(customBacking)
        assertEquals(AppConfig.TunProxyMode.SOCKS5, customConfig.getTunProxyMode())
    }

    @Test
    fun `dns-only mode blocks all proxy enablement`() {
        val backing = Backing(braveMode = AppConfig.BraveMode.DNS.mode)
        val appConfig = newAppConfig(backing)

        assertFalse(appConfig.canEnableProxy())
        assertFalse(appConfig.canEnableSocks5Proxy())
        assertFalse(appConfig.canEnableHttpProxy())
        assertFalse(appConfig.canEnableWireguardProxy())
        assertFalse(appConfig.canEnableTcpProxy())
        assertFalse(appConfig.canEnableOrbotProxy())
    }

    @Test
    fun `provider gating allows only matching proxy toggles`() {
        val backing =
            Backing(
                braveMode = AppConfig.BraveMode.DNS_FIREWALL.mode,
                proxyProvider = AppConfig.ProxyProvider.ORBOT.name,
                proxyType = AppConfig.ProxyType.SOCKS5.name
            )
        val appConfig = newAppConfig(backing)

        assertTrue(appConfig.canEnableOrbotProxy())
        assertFalse(appConfig.canEnableHttpProxy())
        assertFalse(appConfig.canEnableSocks5Proxy())
        assertFalse(appConfig.canEnableWireguardProxy())
        assertFalse(appConfig.canEnableTcpProxy())
    }

    @Test
    fun `http and socks helpers are based on type while custom checks require custom provider`() {
        val backing =
            Backing(
                proxyProvider = AppConfig.ProxyProvider.CUSTOM.name,
                proxyType = AppConfig.ProxyType.HTTP_SOCKS5.name
            )
        val appConfig = newAppConfig(backing)

        assertTrue(appConfig.hasHttpProxyTypeEnabled())
        assertTrue(appConfig.hasSocks5ProxyTypeEnabled())
        assertTrue(appConfig.isCustomHttpProxyEnabled())
        assertTrue(appConfig.isCustomSocks5Enabled())

        backing.proxyProvider = AppConfig.ProxyProvider.ORBOT.name
        assertTrue(appConfig.hasHttpProxyTypeEnabled())
        assertTrue(appConfig.hasSocks5ProxyTypeEnabled())
        assertFalse(appConfig.isCustomHttpProxyEnabled())
        assertFalse(appConfig.isCustomSocks5Enabled())
    }

    @Test
    fun `isProxyEnabled requires non-none provider`() {
        val backing =
            Backing(
                proxyProvider = AppConfig.ProxyProvider.NONE.name,
                proxyType = AppConfig.ProxyType.SOCKS5.name
            )
        val appConfig = newAppConfig(backing)
        assertFalse(appConfig.isProxyEnabled())

        backing.proxyProvider = AppConfig.ProxyProvider.CUSTOM.name
        assertTrue(appConfig.isProxyEnabled())
    }

    private data class Backing(
        var proxyProvider: String = AppConfig.ProxyProvider.NONE.name,
        var proxyType: String = AppConfig.ProxyType.NONE.name,
        var braveMode: Int = AppConfig.BraveMode.DNS_FIREWALL.mode,
        var connectedDnsName: String = "",
        var pcapMode: Int = PcapMode.NONE.id,
        var pcapFilePath: String = ""
    )

    private fun newAppConfig(backing: Backing): AppConfig {
        val persistentState = mockk<PersistentState>(relaxed = true)

        every { persistentState.proxyProvider } answers { backing.proxyProvider }
        every { persistentState.proxyProvider = any() } answers {
            backing.proxyProvider = firstArg()
        }
        every { persistentState.proxyType } answers { backing.proxyType }
        every { persistentState.proxyType = any() } answers {
            backing.proxyType = firstArg()
        }
        every { persistentState.braveMode } answers { backing.braveMode }
        every { persistentState.braveMode = any() } answers {
            backing.braveMode = firstArg()
        }
        every { persistentState.connectedDnsName } answers { backing.connectedDnsName }
        every { persistentState.connectedDnsName = any() } answers {
            backing.connectedDnsName = firstArg()
        }
        every { persistentState.pcapMode } answers { backing.pcapMode }
        every { persistentState.pcapMode = any() } answers {
            backing.pcapMode = firstArg()
        }
        every { persistentState.pcapFilePath } answers { backing.pcapFilePath }
        every { persistentState.pcapFilePath = any() } answers {
            backing.pcapFilePath = firstArg()
        }
        every { persistentState.updateProxyStatus() } returns MutableLiveData(0)

        return AppConfig(
            context = mockk<Context>(relaxed = true),
            rethinkDnsEndpointRepository = mockk<RethinkDnsEndpointRepository>(relaxed = true),
            dnsProxyEndpointRepository = mockk<DnsProxyEndpointRepository>(relaxed = true),
            doHEndpointRepository = mockk<DoHEndpointRepository>(relaxed = true),
            dnsCryptEndpointRepository = mockk<DnsCryptEndpointRepository>(relaxed = true),
            dnsCryptRelayEndpointRepository = mockk<DnsCryptRelayEndpointRepository>(relaxed = true),
            doTEndpointRepository = mockk<DoTEndpointRepository>(relaxed = true),
            oDoHEndpointRepository = mockk<ODoHEndpointRepository>(relaxed = true),
            proxyEndpointRepository = mockk<ProxyEndpointRepository>(relaxed = true),
            persistentState = persistentState,
            networkLogs = mockk<ConnectionTrackerRepository>(relaxed = true),
            dnsLogs = mockk<DnsLogRepository>(relaxed = true),
            eventLogger = mockk<EventLogger>(relaxed = true)
        )
    }
}
