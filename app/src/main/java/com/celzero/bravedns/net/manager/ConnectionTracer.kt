package com.celzero.bravedns.net.manager

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.TextUtils
import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    fun getUidQ(
        protocol: Int,
        sourceIp: String,
        sourcePort: Int,
        destIp: String,
        destPort: Int
    ): Int {
        var uid = Constants.INVALID_UID
        // android.googlesource.com/platform/development/+/da84168fb/ndk/platforms/android-21/include/linux/in.h
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) {
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
            return uid
        } catch (ignored: SecurityException) {
            return uid
        }
        val key = makeCacheKey(protocol, local, remote, destPort)
        try {
            // executing inside a coroutine to avoid the NetworkOnMainThreadException issue#853
            runBlocking(Dispatchers.IO) { uid = cm.getConnectionOwnerUid(protocol, local, remote) }

            if (DEBUG)
                Log.d(
                    LoggerConstants.LOG_TAG_VPN,
                    "getConnectionOwnerUid(): $uid, $key, ${uidCache.getIfPresent(key)}, ${local.address.hostAddress}, ${remote.address.hostAddress}"
                )
            if (uid != Constants.INVALID_UID) {
                addUidToCache(key, uid)
                return uid
            }
        } catch (secEx: SecurityException) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "err getUidQ: " + secEx.message, secEx)
        } catch (ex: InterruptedException) { // InterruptedException is thrown by runBlocking
            Log.e(LoggerConstants.LOG_TAG_VPN, "err getUidQ: " + ex.message, ex)
        } catch (ex: Exception) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "err getUidQ: " + ex.message, ex)
        }
        // If the uid is not in connectivity manager, then return the uid from cache.
        uid = uidCache.getIfPresent(key) ?: Constants.INVALID_UID
        return uid
    }

    private fun addUidToCache(key: String, uid: Int) {
        // do not cache the DNS request (key: 17|10.111.222.1|10.111.222.3|53)
        if (key == DNS_KEY) return

        if (DEBUG) Log.d(LoggerConstants.LOG_TAG_VPN, "getConnectionOwnerUid(): $uid, $key")
        uidCache.put(key, uid)
    }

    private fun makeCacheKey(
        protocol: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress,
        destPort: Int
    ): String {
        return protocol.toString() +
            SEPARATOR +
            local.address.hostAddress +
            SEPARATOR +
            remote.address.hostAddress +
            SEPARATOR +
            destPort
    }
}
