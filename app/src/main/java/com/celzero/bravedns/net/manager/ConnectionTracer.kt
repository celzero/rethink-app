package com.celzero.bravedns.net.manager

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.TextUtils
import androidx.annotation.RequiresApi
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import inet.ipaddr.IPAddressString
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class ConnectionTracer(ctx: Context) {

    companion object {
        private const val CACHE_BUILDER_WRITE_EXPIRE_SEC: Long = 300
        private const val CACHE_BUILDER_MAX_SIZE: Long = 1000
        // key format (Prot 17|Src 10.111.222.1| Dst 10.111.222.3| Dst port 53)
        private const val DNS_KEY = "17|10.111.222.1|10.111.222.3|53"
        private const val SEPARATOR = "|"
    }

    private val cm: ConnectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Cache the UID for the next 60 seconds.
    // the UID will expire after 60 seconds of the write.
    // Key for the cache is protocol, local, remote
    private val uidCache: Cache<String, Int> = CacheBuilder.newBuilder()
        .maximumSize(CACHE_BUILDER_MAX_SIZE)
        .expireAfterWrite(CACHE_BUILDER_WRITE_EXPIRE_SEC, TimeUnit.SECONDS)
        .build()

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun getUidQ(
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        retryCount: Int = 0
    ): Int {
        var uid = Constants.INVALID_UID

        when (protocol) {
            Protocol.ICMP.protocolType,
            Protocol.ICMPV6.protocolType -> {
                // workaround for ICMP/ICMPv6
                // commenting this out as the workaround is not working as expected
                // revisit if a better workaround is found
                /* protocol = Protocol.UDP.protocolType
                updatedDstPort = 0
                updatedSrcPort = 0 // sourcePort is 0 for ICMP/ICMPv6, still setting for clarity */
                return uid
            }

            Protocol.TCP.protocolType,
            Protocol.UDP.protocolType -> {
            }

            else -> {
                // android.googlesource.com/platform/development/+/da84168fb/ndk/platforms/android-21/include/linux/in.h
                Logger.v(LOG_TAG_VPN, "getUidQ; unsupported protocol: $protocol")
                return uid
            }
        }

        val local: InetSocketAddress
        val remote: InetSocketAddress
        try {
            local =
                if (TextUtils.isEmpty(srcIp)) {
                    InetSocketAddress(srcPort)
                } else {
                    InetSocketAddress(srcIp, srcPort)
                }
            remote =
                if (TextUtils.isEmpty(dstIp)) {
                    InetSocketAddress(dstPort)
                } else {
                    InetSocketAddress(dstIp, dstPort)
                }
        } catch (e: IllegalArgumentException) {
            // InetSocketAddress throws IllegalArgumentException or SecurityException
            Logger.d(LOG_TAG_VPN, "err getUidQ: ${e.message}")
            return uid
        } catch (e: SecurityException) {
            Logger.d(LOG_TAG_VPN, "err getUidQ: ${e.message}")
            return uid
        }
        val key = makeCacheKey(protocol, local, remote)
        try {
            Logger.v(LOG_TAG_VPN, "getUidQ: used $srcIp, $srcPort, $dstIp, $dstPort, proto: $protocol")
            // must be called from io thread to avoid the NetworkOnMainThreadException issue#853
            uid = cm.getConnectionOwnerUid(protocol, local, remote)
            Logger.d(LOG_TAG_VPN, "getUidQ: $uid, $key, ${uidCache.getIfPresent(key)}")
            if (uid != Constants.INVALID_UID) {
                addUidToCache(key, uid)
                return uid
            }
        } catch (secEx: SecurityException) {
            Logger.e(LOG_TAG_VPN, "err getUidQ: " + secEx.message, secEx)
        } catch (ex: InterruptedException) { // InterruptedException is thrown by runBlocking
            Logger.e(LOG_TAG_VPN, "err getUidQ: " + ex.message, ex)
        } catch (ex: Exception) {
            Logger.e(LOG_TAG_VPN, "err getUidQ: " + ex.message, ex)
        }

        if (retryCount >= 2) return uid

        if (retryRequired(uid, protocol, dstIp, key)) {
            when (retryCount) {
                0 -> {
                    // change the destination port to 0 and try again
                    val dport = 0
                    val retryCount = 1
                    val res = getUidQ(protocol, srcIp, srcPort, dstIp, dport, retryCount)
                    Logger.d(LOG_TAG_VPN, "getUidQ retrying(1st) with: $protocol, $srcIp, $srcPort, $dstIp, $dport, res: $res old($dstPort)")
                    return res
                }
                1 -> {
                    // change the destination IP to unspecified IP and try again for unconnected UDP
                    val dip = if (IPAddressString(dstIp).isIPv6) {
                        Constants.UNSPECIFIED_IP_IPV6
                    } else {
                        Constants.UNSPECIFIED_IP_IPV4
                    }
                    val dport = 0
                    val retryCount = 2
                    val res = getUidQ(protocol, srcIp, srcPort, dip, dport, retryCount)
                    Logger.d(LOG_TAG_VPN, "getUidQ retrying(2nd) with: $protocol, $srcIp, $srcPort, $dip, $dport, old($dstIp, $dstPort), res: $res")
                    return res
                }
                else -> {
                    Logger.w(LOG_TAG_VPN, "getUidQ: retryRequired but retryCount ($retryCount) is not a handled case.")
                    return uid
                }
            }
        }

        // If the uid is not in connectivity manager, then return the uid from cache.
        uid = uidCache.getIfPresent(key) ?: Constants.INVALID_UID
        Logger.v(LOG_TAG_VPN, "getUidQ: returning from cache: $uid, $key")
        return uid
    }

    // handle unconnected UDP requests
    private fun retryRequired(uid: Int, protocol: Int, destIp: String, key: String): Boolean {
        if (uid != Constants.INVALID_UID) { // already got the uid, no need to retry
            return false
        }
        // if uid is already cached, no need to retry
        if (uidCache.getIfPresent(key) != null) {
            return false
        }
        // no need to retry for protocols other than UDP
        if (protocol != Protocol.UDP.protocolType) {
            return false
        }

        // no need to retry for unspecified IP, as it is already tried twice
        return !isUnspecifiedIp(destIp)
    }

    private fun addUidToCache(key: String, uid: Int) {
        // do not cache the DNS request (key: 17|10.111.222.1|10.111.222.3|53)
        if (key == DNS_KEY) return

        Logger.d(LOG_TAG_VPN, "getUidQ; cache put: $uid, $key")
        uidCache.put(key, uid)
    }

    private fun makeCacheKey(
        protocol: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress
    ): String {
        return protocol.toString() +
            SEPARATOR +
            local.address.hostAddress +
            SEPARATOR +
            remote.address.hostAddress +
            SEPARATOR +
            remote.port
    }
}
