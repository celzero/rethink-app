package com.celzero.bravedns.util

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.RemoteException
import android.telephony.TelephonyManager


@TargetApi(Build.VERSION_CODES.M)
class NetworkStatsHelper {
    var networkStatsManager: NetworkStatsManager
    var packageUid = 0

    constructor(networkStatsManager: NetworkStatsManager) {
        this.networkStatsManager = networkStatsManager
    }

    constructor(networkStatsManager: NetworkStatsManager, packageUid: Int) {
        this.networkStatsManager = networkStatsManager
        this.packageUid = packageUid
    }

    fun getAllRxBytesMobile(context: Context): Long {
        val bucket: NetworkStats.Bucket
        bucket = try {
            networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                getSubscriberId(context, ConnectivityManager.TYPE_MOBILE),
                0,
                System.currentTimeMillis()
            )
        } catch (e: RemoteException) {
            return -1
        }
        return bucket.rxBytes
    }

    fun getAllTxBytesMobile(context: Context): Long {
        val bucket: NetworkStats.Bucket
        bucket = try {
            networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                getSubscriberId(context, ConnectivityManager.TYPE_MOBILE),
                0,
                System.currentTimeMillis()
            )
        } catch (e: RemoteException) {
            return -1
        }
        return bucket.txBytes
    }

    val allRxBytesWifi: Long
        get() {
            val bucket: NetworkStats.Bucket
            bucket = try {
                networkStatsManager.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    "",
                    0,
                    System.currentTimeMillis()
                )
            } catch (e: RemoteException) {
                return -1
            }
            return bucket.rxBytes
        }

    val allTxBytesWifi: Long
        get() {
            val bucket: NetworkStats.Bucket
            bucket = try {
                networkStatsManager.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    "",
                    0,
                    System.currentTimeMillis()
                )
            } catch (e: RemoteException) {
                return -1
            }
            return bucket.txBytes
        }

    fun getPackageRxBytesMobile(context: Context): Long {
        var networkStats: NetworkStats? = null
        networkStats = try {
            networkStatsManager.queryDetailsForUid(
                ConnectivityManager.TYPE_MOBILE,
                getSubscriberId(context, ConnectivityManager.TYPE_MOBILE),
                0,
                System.currentTimeMillis(),
                packageUid
            )
        } catch (e: RemoteException) {
            return -1
        }
        var rxBytes = 0L
        val bucket = NetworkStats.Bucket()
        while (networkStats.hasNextBucket()) {
            networkStats.getNextBucket(bucket)
            rxBytes += bucket.rxBytes
        }
        networkStats.close()
        return rxBytes
    }

    fun getPackageTxBytesMobile(context: Context): Long {
        var networkStats: NetworkStats? = null
        networkStats = try {
            networkStatsManager.queryDetailsForUid(
                ConnectivityManager.TYPE_MOBILE,
                getSubscriberId(context, ConnectivityManager.TYPE_MOBILE),
                0,
                System.currentTimeMillis(),
                packageUid
            )
        } catch (e: RemoteException) {
            return -1
        }
        var txBytes = 0L
        val bucket = NetworkStats.Bucket()
        while (networkStats.hasNextBucket()) {
            networkStats.getNextBucket(bucket)
            txBytes += bucket.txBytes
        }
        networkStats.close()
        return txBytes
    }

    val packageRxBytesWifi: Long
        get() {
            var networkStats: NetworkStats? = null
            networkStats = try {
                networkStatsManager.queryDetailsForUid(
                    ConnectivityManager.TYPE_WIFI,
                    "",
                    0,
                    System.currentTimeMillis(),
                    packageUid
                )
            } catch (e: RemoteException) {
                return -1
            }
            var rxBytes = 0L
            val bucket = NetworkStats.Bucket()
            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                rxBytes += bucket.rxBytes
            }
            networkStats.close()
            return rxBytes
        }

    val packageTxBytesWifi: Long
        get() {
            var networkStats: NetworkStats? = null
            networkStats = try {
                networkStatsManager.queryDetailsForUid(
                    ConnectivityManager.TYPE_WIFI,
                    "",
                    0,
                    System.currentTimeMillis(),
                    packageUid
                )
            } catch (e: RemoteException) {
                return -1
            }
            var txBytes = 0L
            val bucket = NetworkStats.Bucket()
            while (networkStats.hasNextBucket()) {
                networkStats.getNextBucket(bucket)
                txBytes += bucket.txBytes
            }
            networkStats.close()
            return txBytes
        }




    private fun getSubscriberId(context: Context, networkType: Int): String {
        if (ConnectivityManager.TYPE_MOBILE == networkType) {
            val tm =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            return tm.subscriberId
        }
        return ""
    }
}