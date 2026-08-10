package com.celzero.bravedns.net.manager

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Protocol
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
class ConnectionTracerTest {

    private lateinit var connectionTracer: ConnectionTracer
    private val context = mockk<Context>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)

    @Before
    fun setup() {
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        connectionTracer = ConnectionTracer(context)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `getUidQ should return UID from ConnectivityManager on API 29+`() = runTest {
        val protocol = Protocol.TCP.protocolType
        val srcIp = "10.111.222.1"
        val srcPort = 12345
        val dstIp = "8.8.8.8"
        val dstPort = 443
        val expectedUid = 10001

        every { 
            connectivityManager.getConnectionOwnerUid(
                protocol, 
                any<InetSocketAddress>(), 
                any<InetSocketAddress>()
            ) 
        } returns expectedUid

        val uid = connectionTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort)

        assertEquals(expectedUid, uid)
        
        // Verify that the call was made with correct addresses
        verify {
            connectivityManager.getConnectionOwnerUid(
                protocol,
                match { it.address.hostAddress == srcIp && it.port == srcPort },
                match { it.address.hostAddress == dstIp && it.port == dstPort }
            )
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `getUidQ should return invalid UID for unsupported protocols`() = runTest {
        val protocol = 99 // Unsupported
        val uid = connectionTracer.getUidQ(protocol, "1.1.1.1", 80, "2.2.2.2", 80)
        assertEquals(Constants.INVALID_UID, uid)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `getUidQ should retry with port 0 for UDP when first attempt fails`() = runTest {
        val protocol = Protocol.UDP.protocolType
        val srcIp = "10.111.222.1"
        val srcPort = 12345
        val dstIp = "8.8.8.8"
        val dstPort = 443
        val expectedUid = 10002

        // First call fails (returns INVALID_UID)
        every { 
            connectivityManager.getConnectionOwnerUid(
                protocol, 
                match { it.port == dstPort }, 
                any()
            ) 
        } returns Constants.INVALID_UID

        // Retry call with port 0 succeeds
        every { 
            connectivityManager.getConnectionOwnerUid(
                protocol, 
                any(), 
                match { it.port == 0 }
            ) 
        } returns expectedUid

        val uid = connectionTracer.getUidQ(protocol, srcIp, srcPort, dstIp, dstPort)

        assertEquals(expectedUid, uid)
        verify(exactly = 2) { connectivityManager.getConnectionOwnerUid(any(), any(), any()) }
    }
}
