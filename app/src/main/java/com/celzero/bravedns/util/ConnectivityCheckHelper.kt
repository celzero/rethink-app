package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_CONNECTION
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.system.ErrnoException
import android.system.OsConstants.ECONNREFUSED
import com.celzero.bravedns.service.ConnectionMonitor.Companion.netId
import com.celzero.bravedns.service.ConnectionMonitor.NetworkProperties
import com.celzero.bravedns.service.ConnectionMonitor.ProbeResult
import com.celzero.bravedns.service.ProbeController
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.ConnectivityCheckHelper.isReachableTcpUdp
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object ConnectivityCheckHelper {

    private const val CONN_TYPE = "auto"
    private const val TAG = "ConnCheckHelper"
    /**
     * Probes connectivity to a list of IP addresses on a given network.
     *
     * This function iterates through the provided IP addresses and attempts to establish
     * connectivity using [VpnController.performConnectivityCheck].
     *
     * If `useActive` is true, it first attempts to probe using the active network (socket
     * will be protected but not bound). If that fails or `useActive` is false, it proceeds
     * to probe using the specified `nw` (socket will be bound to this network).
     *
     * If both attempts fail and `useKotlinChecks` is true, it falls back to
     * [isReachableTcpUdp] for an additional check.
     *
     * The function returns true as soon as any probe is successful.
     *
     * @param probes A collection of IP addresses (as strings) to probe. Empty strings are skipped.
     * @param nw The [Network] to use for probing. If null and `useActive` is false,
     *           the probe controller will protect the socket to the default network.
     * @return True if connectivity is established to any of the probed IPs, false otherwise.
     */
    private suspend fun probeConnectivity(
        probes: Collection<String>,
        nw: Network?,
        useKotlinChecks: Boolean
    ): Boolean {
        var ok = false
        val probeController = if (nw != null) {
            ProbeController(nw.networkHandle, nw)
        } else {
            // in case of use active network, the network is null, so the controller will
            // protect the socket to the default network instead of bind
            // assign the networkHandle as 0 and nw as null, so that the controller will not
            // bind the socket
            ProbeController(-1, null)
        }
        probes.forEach { ipOrUrl ->
            if (ipOrUrl.isEmpty()) return@forEach // skip empty IPs

            val id = "probe:${netId(nw?.networkHandle)}:$ipOrUrl"
            ok = VpnController.performConnectivityCheck(probeController, id, ipOrUrl)

            if (!ok && useKotlinChecks) {
                ok = isReachableTcpUdp(nw, ipOrUrl)
            }
            Logger.v(
                LOG_TAG_CONNECTION,
                "$TAG probeConnectivity, id($id): $ipOrUrl, ok? $ok, nw: ${nw?.networkHandle}, netid: ${
                    netId(nw?.networkHandle)
                }"
            )
            if (ok) {
                return@forEach // break
            }
        }
        return ok
    }


    /**
     * Probes connectivity in automatic mode for a given scheme and protocol on a specific network.
     *
     * This function uses [VpnController.performAutoConnectivityCheck] to determine if
     * a connection can be established.
     *
     * If `nw` is null (indicating the active network should be used), the probe controller
     * will protect the socket to the default network but will not bind it.
     * Otherwise, the controller will bind the socket to the specified `nw`.
     *
     * The probe's mode is constructed as "$CONN_TYPE:$scheme:$protocol".
     *
     * @param scheme The connection scheme (e.g., "https", "http").
     * @param nw The [Network] to use for probing. If null, the active network is used.
     * @param protocol The protocol to use (e.g., "tcp", "udp").
     * @return True if connectivity is established, false otherwise.
     */
    suspend fun probeConnectivityInAutoMode(
        nw: Network? = null,
        scheme: String,
        protocol: String
    ): Boolean {
        val ok: Boolean
        val probeController = if (nw != null) {
            ProbeController(nw.networkHandle, nw)
        } else {
            // in case of use active network, the network is null, so the controller will
            // protect the socket to the default network instead of bind
            // assign the networkHandle as 0 and nw as null, so that the controller will not
            // bind the socket
            ProbeController(-1, null)
        }

        val mode = "$CONN_TYPE:$scheme:$protocol"
        val id = "probe:${netId(nw?.networkHandle)}:$mode"
        ok = VpnController.performAutoConnectivityCheck(probeController, id, mode)
        Logger.v(
            LOG_TAG_CONNECTION,
            "$TAG probeConnectivity, id($id): $mode, ok? $ok, nw: ${nw?.networkHandle}, netid: ${
                netId(nw?.networkHandle)
            }"
        )
        return ok
    }


    /**
     * Probes the given IP address for connectivity on the available networks.
     *
     * This function attempts to establish a connection to the specified IP address
     * using the provided set of networks. It prioritizes networks based on their
     * type (active, non-metered, metered).
     *
     * If initial probes fail and `USE_KOTLIN_REACHABILITY_CHECKS` is enabled,
     * it will fall back to ICMP or TCP/UDP reachability checks.
     *
     * @param ipOrUrl The IP address or url to probe.
     * @param nws The set of available networks to use for probing, can be empty.
     * @param activeNwCapabilities The [NetworkCapabilities] of the currently active network,
     *                             or null if no active network is available.
     * @param useKotlinChecks A boolean indicating whether to use Kotlin-based reachability checks
     * @return A [ProbeResult] object containing the IP address, a boolean indicating
     *         if the probe was successful, and the [NetworkCapabilities] of the
     *         network on which the successful probe occurred (or null if unsuccessful).
     */
    suspend fun probeIpOrUrl(ipOrUrl: String, nws: Set<NetworkProperties>, activeNwCapabilities: NetworkCapabilities?, useKotlinChecks: Boolean): ProbeResult {
        if (nws.isEmpty()) {
            var ok = probeConnectivity(listOf(ipOrUrl), nw = null, useKotlinChecks)
            if (!ok && useKotlinChecks) {
                // if no networks are available, try to reach the ip using ICMP
                // or TCP/UDP checks
                ok = isReachable(ipOrUrl)
            }
            val cap = activeNwCapabilities
            Logger.i(LOG_TAG_CONNECTION, "$TAG probeIpOrUrl: $ipOrUrl, ok? $ok, cap: $cap")
            return ProbeResult(ipOrUrl, ok, cap)
        }
        nws.forEach { nw ->
            val ok = probeConnectivity(listOf(ipOrUrl), nw.network, useKotlinChecks)
            val cap = nw.capabilities
            if (!ok && useKotlinChecks) {
                // if the probe fails, try to reach the ip using ICMP or TCP/UDP checks
                val reachable = isReachableTcpUdp(nw.network, ipOrUrl)
                if (reachable) {
                    return ProbeResult(ipOrUrl, true, cap)
                }
            }
            if (ok) {
                Logger.i(
                    LOG_TAG_CONNECTION,
                    "$TAG probeIpOrUrl: $ipOrUrl, ok? $ok, netid: ${netId(nw.network.networkHandle)}, cap: $cap"
                )
                return ProbeResult(ipOrUrl, true, cap)
            }
        }
        return ProbeResult(ipOrUrl, false, null)
    }

    /**
     * Checks if the host is reachable via TCP/UDP on the given network.
     * It tries to connect to the host on port 80 (HTTP) and port 53 (DNS) via TCP.
     * If either of these connections is successful, the host is considered reachable.
     * The socket is bound to the given network before attempting to connect.
     * @param nw The network to use for the reachability check.
     * @param host The host to check for reachability.
     * @return True if the host is reachable, false otherwise.
     */
    private suspend fun isReachableTcpUdp(nw: Network?, host: String): Boolean {
        try {
            // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
            TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())

            val yes = tcp80(nw, host) || tcp53(nw, host)

            Logger.d(
                LOG_TAG_CONNECTION,
                "$TAG $host isReachable on network ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)},($nw): $yes"
            )
            return yes
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err isReachable: ${e.message}")
        }
        return false
    }

    /**
     * Checks if the host is reachable using ICMP echo request.
     *
     * The 'isReachable()' function sends an ICMP echo request to the target host. In the
     * event of the 'Rethink within Rethink' option being used, ICMP checks will fail.
     * Ideally, there is no need to perform ICMP checks. However, if 'Rethink within
     * Rethink' is not supplied to this handler, these checks will be carried out but will
     * result in failure.
     *
     * @param host The hostname or IP address to check.
     * @return True if the host is reachable, false otherwise.
     */
    private suspend fun isReachable(host: String): Boolean {
        try {
            val timeout = 500 // ms
            // https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
            TrafficStats.setThreadStatsTag(Thread.currentThread().id.toIntOrDefault())
            // https://developer.android.com/reference/java/net/InetAddress#isReachable(int)
            // network.getByName() binds the socket to that network; InetAddress.getByName.
            // isReachable(network-interface, ttl, timeout) cannot be used here since
            // "network-interface" is a java-construct while "Network" is an android-construct
            // InetAddress.getByName() will bind the socket to the default active network.
            val yes = InetAddress.getByName(host).isReachable(timeout)

            Logger.d(LOG_TAG_CONNECTION, "$TAG $host isReachable on network: $yes")
            return yes
        } catch (e: Exception) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err isReachable: ${e.message}")
        }
        return false
    }

    /**
     * Checks if the host is reachable via TCP on port 80.
     *
     * The check involves creating a socket, binding it to the provided network (if any),
     * protecting the socket using VpnController, and then attempting to connect to the
     * host on port 80 with a timeout.
     *
     * android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-benchmark-release/java/net/Inet6AddressImpl.java#217
     *
     * @param nw The network to use for the connection. If null, the default network is used.
     * @param host The hostname or IP address to connect to.
     * @return True if the connection is successful or if the connection is refused (ECONNREFUSED),
     *         false otherwise.
     */

    private suspend fun tcp80(nw: Network?, host: String): Boolean {
        val timeout = 500 // ms
        val port80 = 80 // port
        var socket: Socket? = null
        try {
            // port 7 is echo port, blocked by most firewalls. use port 80 instead
            val s = InetSocketAddress(host, port80)
            socket = Socket()
            nw?.bindSocket(socket)
            VpnController.protectSocket(socket)
            socket.connect(s, timeout)
            val c = socket.isConnected
            val b = socket.isBound
            Logger.d(
                LOG_TAG_CONNECTION,
                "$TAG tcpEcho80: $host, ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)}, $c, $b, $nw"
            )

            return true
        } catch (e: IOException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err tcpEcho80: ${e.message}, ${e.cause}")
            val cause: Throwable = e.cause ?: return false

            return (cause is ErrnoException && cause.errno == ECONNREFUSED)
        } catch (e: IllegalArgumentException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err tcpEcho80: ${e.message}, ${e.cause}")
            return false
        } catch (e: SecurityException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err tcpEcho80: ${e.message}, ${e.cause}")
            return false
        } finally {
            clos(socket)
        }
    }

    /**
     * Check if the host is reachable on port 53 (DNS) using TCP.
     * Tries to establish a TCP connection to the host on port 53. If the connection is
     * successful, the host is considered reachable. If the connection is refused (ECONNREFUSED),
     * it's also considered reachable as it implies a service is listening but refused the
     * specific connection. Other IOExceptions or errors indicate unreachability.
     *
     * @param nw The network to use for the connection. If null, the default network is used.
     * @param host The hostname or IP address of the host to check.
     * @return True if the host is reachable on port 53, false otherwise.
     */
    private suspend fun tcp53(nw: Network?, host: String): Boolean {
        val timeout = 500 // ms
        val port53 = 53 // port
        var socket: Socket? = null

        try {
            socket = Socket()
            val s = InetSocketAddress(host, port53)
            nw?.bindSocket(socket)
            VpnController.protectSocket(socket)
            socket.connect(s, timeout)
            val c = socket.isConnected
            val b = socket.isBound
            Logger.d(
                LOG_TAG_CONNECTION,
                "$TAG tcpEcho53: $host, ${nw?.networkHandle}, netid: ${netId(nw?.networkHandle)}: $c, $b, $nw"
            )
            return true
        } catch (e: IOException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err tcpEcho53: ${e.message}, ${e.cause}")
            val cause: Throwable = e.cause ?: return false

            return (cause is ErrnoException && cause.errno == ECONNREFUSED)
        } catch (e: IllegalArgumentException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err tcpEcho53: ${e.message}, ${e.cause}")
        } catch (e: SecurityException) {
            Logger.w(LOG_TAG_CONNECTION, "$TAG err tcpEcho53: ${e.message}, ${e.cause}")
        } finally {
            clos(socket)
        }
        return false
    }


    /**
     * Closes the given [Closeable] and ignores any [IOException] that may occur.
     *
     * @param socket The [Closeable] to close.
     */
    private fun clos(socket: Closeable?) {
        try {
            socket?.use {
                it.close()
            }
        } catch (ignored: IOException) {
        }
    }

    // convert long to int, if the value is out of range return 10000
    private fun Long.toIntOrDefault(): Int {
        return if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
            10000
        } else {
            this.toInt()
        }
    }

}
