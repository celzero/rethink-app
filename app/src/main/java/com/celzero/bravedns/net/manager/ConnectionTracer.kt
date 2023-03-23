package com.celzero.bravedns.net.manager

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.text.TextUtils
import android.util.Log
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
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
        private const val PER_USER_RANGE = 100000
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
            uid = cm.getConnectionOwnerUid(protocol, local, remote)
            // Returns the app id for a given uid, stripping out the user id from it.
            // TODO: revisit this logic for multi-user support
            // http://androidxref.com/9.0.0_r3/xref/frameworks/base/core/java/android/os/UserHandle.java#224
            // uid %= PER_USER_RANGE

            if (DEBUG) Log.d(LoggerConstants.LOG_TAG_VPN, "UID from getConnectionOwnerUid(): $uid")
            if (uid != Constants.INVALID_UID) {
                uidCache.put(key, uid)
                return uid
            }
        } catch (secEx: SecurityException) {
            Log.e(LoggerConstants.LOG_TAG_VPN, "NETWORK_STACK permission - " + secEx.message, secEx)
        }
        // If the uid is not in connectivity manager, then return the uid from cache.
        uid = uidCache.getIfPresent(key) ?: Constants.INVALID_UID
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
