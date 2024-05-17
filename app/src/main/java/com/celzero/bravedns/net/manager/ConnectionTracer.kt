package com.celzero.bravedns.net.manager

import Logger
import Logger.LOG_TAG_VPN
import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.TextUtils
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

    private val cm: ConnectivityManager
    private val uidCache: Cache<String, Int>

    init {
        cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Cache the UID for the next 60 seconds.
        // the UID will expire after 60 seconds of the write.
        // Key for the cache is protocol, local, remote
        uidCache =
            CacheBuilder.newBuilder()
                .maximumSize(CACHE_BUILDER_MAX_SIZE)
                .expireAfterWrite(CACHE_BUILDER_WRITE_EXPIRE_SEC, TimeUnit.SECONDS)
                .build()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    suspend fun getUidQ(
        protocol: Int,
        sourceIp: String,
        sourcePort: Int,
        destIp: String,
        destPort: Int
    ): Int {
        var uid = Constants.INVALID_UID
        // android.googlesource.com/platform/development/+/da84168fb/ndk/platforms/android-21/include/linux/in.h
        if (protocol != Protocol.TCP.protocolType && protocol != Protocol.UDP.protocolType) {
            return uid
        }
        val local: InetSocketAddress
        val remote: InetSocketAddress
        try {
            local =
                if (TextUtils.isEmpty(sourceIp)) {
                    InetSocketAddress(sourcePort)
                } else {
                    InetSocketAddress(sourceIp, sourcePort)
                }
            remote =
                if (TextUtils.isEmpty(destIp)) {
                    InetSocketAddress(destPort)
                } else {
                    InetSocketAddress(destIp, destPort)
                }
        } catch (ignored: IllegalArgumentException) {
            // InetSocketAddress throws IllegalArgumentException or SecurityException
            Logger.d(LOG_TAG_VPN, "err getUidQ: $ignored")
            return uid
        } catch (ignored: SecurityException) {
            Logger.d(LOG_TAG_VPN, "err getUidQ: $ignored")
            return uid
        }
        val key = makeCacheKey(protocol, local, remote)
        try {
            // must be called from io thread to avoid the NetworkOnMainThreadException issue#853
            uid = cm.getConnectionOwnerUid(protocol, local, remote)

            Logger.d(
                LOG_TAG_VPN,
                "getConnectionOwnerUid(): $uid, $key, ${uidCache.getIfPresent(key)}, ${local.address.hostAddress}, ${remote.address.hostAddress}"
            )
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

        if (retryRequired(uid, protocol, destIp, key)){
            // change the destination IP to unspecified IP and try again for unconnected UDP
            val dip =
                if (IPAddressString(destIp).isIPv6) {
                    Constants.UNSPECIFIED_IP_IPV6
                } else {
                    Constants.UNSPECIFIED_IP_IPV4
                }
            val dport = 0
            val res = getUidQ(protocol, sourceIp, sourcePort, dip, dport)
            Logger.d(
                LOG_TAG_VPN,
                "retrying with: $protocol, $sourceIp, $sourcePort, $dip, $dport old($destIp, $destPort), res: $res"
            )
            return res
        }

        // If the uid is not in connectivity manager, then return the uid from cache.
        uid = uidCache.getIfPresent(key) ?: Constants.INVALID_UID
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

        // no need to retry for unspecified IP, as it is already tried
        return !isUnspecifiedIp(destIp)
    }

    private fun addUidToCache(key: String, uid: Int) {
        // do not cache the DNS request (key: 17|10.111.222.1|10.111.222.3|53)
        if (key == DNS_KEY) return

        Logger.d(LOG_TAG_VPN, "getConnectionOwnerUid(): $uid, $key")
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
