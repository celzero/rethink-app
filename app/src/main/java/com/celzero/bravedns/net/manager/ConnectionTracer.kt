package com.celzero.bravedns.net.manager

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.TextUtils
import android.util.Log
import com.celzero.bravedns.util.AndroidUidConfig.Companion.isUidAppRange
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class ConnectionTracer(ctx: Context) {

    companion object {
        private const val CACHE_BUILDER_WRITE_EXPIRE_SEC: Long = 300
        private const val CACHE_BUILDER_MAX_SIZE: Long = 1000
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
        // android.googlesource.com/platform/development/+/da84168fb/ndk/platforms/android-21/include/linux/in.h
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) {
            return Constants.MISSING_UID
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
            return Constants.MISSING_UID
        } catch (ignored: SecurityException) {
            return Constants.MISSING_UID
        }
        var uid = Constants.INVALID_UID
        val key = makeCacheKey(protocol, local, remote, destPort)
        try {
            uid = cm.getConnectionOwnerUid(protocol, local, remote)
            // Cache only uid's in app range
            if (isUidAppRange(uid)) {
                uidCache.put(key, uid)
            } else {
                // no-op
            }
            return uid
        } catch (secEx: SecurityException) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "NETWORK_STACK permission - " + secEx.message, secEx)
        }
        // If the uid is not in connectivity manager, then return the uid from cache.
        try {
            return uidCache.getIfPresent(key)!!
        } catch (ignored: Exception) {}

        return uid
    }

    private fun makeCacheKey(
        protocol: Int,
        local: InetSocketAddress,
        remote: InetSocketAddress,
        destPort: Int
    ): String {
        return protocol.toString() +
            local.address.hostAddress +
            remote.address.hostAddress +
            destPort
    }
}
