package com.celzero.bravedns.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.ITcpProxy
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.TcpProxyEndpoint
import com.celzero.bravedns.database.TcpProxyRepository
import com.celzero.bravedns.scheduler.PaymentWorker
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_PROXY
import dnsx.Dnsx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object TcpProxyHelper : KoinComponent {

    private val tcpProxyRespository: TcpProxyRepository by inject()
    private val appConfig: AppConfig by inject()
    private val persistentState: PersistentState by inject()

    private val tcpProxies = mutableSetOf<TcpProxyEndpoint>()

    private var cfIpTrie: dnsx.IpTree = Dnsx.newIpTree()

    private const val DEFAULT_ID = 0
    const val PAYMENT_WORKER_TAG = "payment_worker_tag"

    private const val JSON_MIN_VERSION_CODE = "minvcode"
    private const val JSON_STATUS = "status"
    private const val JSON_PUB_KEY = "pubkey"

    private const val STATUS_OK = "ok"
    private var publicKey: String = ""
    const val TCP_FOLDER_NAME = "tcp"
    const val PIP_KEY_FILE_NAME = "pip.key"

    private val cfIpAddresses =
        listOf(
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "108.162.192.0/18",
            "131.0.72.0/22",
            "141.101.64.0/18",
            "162.158.0.0/15",
            "172.64.0.0/13",
            "173.245.48.0/20",
            "188.114.96.0/20",
            "190.93.240.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17",
            "2400:cb00::/32",
            "2606:4700::/32",
            "2803:f800::/32",
            "2405:b500::/32",
            "2405:8100::/32",
            "2a06:98c0::/29",
            "2c0f:f248::/32"
        )

    enum class PaymentStatus(val value: Int) {
        NOT_PAID(0),
        INITIATED(1),
        PAID(2),
        EXPIRED(3),
        FAILED(4),
        INVALID(5);

        fun isPaid() = this == PAID

        fun isFailed() = this == FAILED

        fun isNotPaid() = this == NOT_PAID
    }

    init {
        io { load() }
    }

    suspend fun load() {
        if (tcpProxies.isNotEmpty()) return

        tcpProxies.clear()
        tcpProxies.addAll(tcpProxyRespository.getTcpProxies())
        loadTrie()
    }

    private fun loadTrie() {
        cfIpTrie = Dnsx.newIpTree()
        cfIpAddresses.forEach { cfIpTrie.set(it, "") }
        if (DEBUG) Log.d(LOG_TAG_PROXY, "loadTrie: loading trie for cloudflare ips")
    }

    fun isCloudflareIp(ip: String): Boolean {
        // do not check for cloudflare ips for now
        // return false
        return try {
            cfIpTrie.hasAny(ip)
        } catch (e: Exception) {
            Log.w(LOG_TAG_PROXY, "isCloudflareIp: exception while checking ip: $ip")
            false
        }
    }

    fun getFolderName(): String {
        // folder name with dd_MM_yyyy format
        val date = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("dd_MM_yyyy", Locale.getDefault())
        return dateFormat.format(date)
    }

    suspend fun publicKeyUsable(): Boolean {
        var works = false
        try {
            val retrofit =
                RetrofitManager.getTcpProxyBaseBuilder()
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(ITcpProxy::class.java)

            val response = retrofitInterface.getPublicKey(persistentState.appVersion.toString())
            if (DEBUG)
                Log.d(
                    LOG_TAG_PROXY,
                    "new tcp config: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}"
                )

            if (response?.isSuccessful == true) {
                val jsonObject = JSONObject(response.body().toString())
                works = jsonObject.optString(JSON_STATUS, "") == STATUS_OK
                val minVersionCode = jsonObject.optString(JSON_MIN_VERSION_CODE, "")
                publicKey = jsonObject.optString(JSON_PUB_KEY, "")
                Log.i(
                    LOG_TAG_PROXY,
                    "tcp response for ${response.raw().request.url}, works? $works, minVersionCode: $minVersionCode, publicKey: $publicKey"
                )
            } else {
                Log.w(LOG_TAG_PROXY, "unsuccessful response for ${response?.raw()?.request?.url}")
            }
        } catch (e: Exception) {
            Log.e(
                LOG_TAG_PROXY,
                "publicKeyUsable: exception while checking public key: ${e.message}",
                e
            )
        }
        return works
    }

    suspend fun isPaymentInitiated(): Boolean {
        val tcpProxy = tcpProxies.find { it.id == DEFAULT_ID }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "isPaymentInitiated: tcpProxy not found")
            return false
        }
        return tcpProxy.paymentStatus == PaymentStatus.INITIATED.value
    }

    suspend fun isPaymentExpired(): Boolean {
        val tcpProxy = tcpProxies.find { it.id == DEFAULT_ID }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "isPaymentExpired: tcpProxy not found")
            return false
        }
        return tcpProxy.paymentStatus == PaymentStatus.EXPIRED.value
    }

    fun getTcpProxyPaymentStatus(): PaymentStatus {
        val tcpProxy = tcpProxies.find { it.id == DEFAULT_ID }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "getTcpProxyPaymentStatus: tcpProxy not found")
            return PaymentStatus.NOT_PAID
        }
        return PaymentStatus.values().find { it.value == tcpProxy.paymentStatus }
            ?: PaymentStatus.NOT_PAID
    }

    suspend fun getPublicKey(): String {
        if (publicKey.isEmpty()) {
            publicKeyUsable()
        } else {
            Log.i(LOG_TAG_PROXY, "getPublicKey: returning cached public key")
        }
        return publicKey
    }

    suspend fun updatePaymentStatus(paymentStatus: PaymentStatus) {
        val tcpProxy = tcpProxies.find { it.id == DEFAULT_ID }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "updatePaymentStatus: tcpProxy not found")
            return
        }
        tcpProxy.paymentStatus = paymentStatus.value
        tcpProxyRespository.update(tcpProxy)
    }

    suspend fun updateToken(token: String) {
        val tcpProxy = tcpProxies.find { it.id == DEFAULT_ID }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "updateToken: tcpProxy not found")
            return
        }
        tcpProxy.token = token
        tcpProxyRespository.update(tcpProxy)
    }

    suspend fun updateUrl(url: String) {
        val tcpProxy = tcpProxies.find { it.id == DEFAULT_ID }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "updateUrl: tcpProxy not found")
            return
        }
        tcpProxy.url = url
        tcpProxyRespository.update(tcpProxy)
    }

    fun initiatePaymentVerification(context: Context) {
        val appContext = context.applicationContext
        if (DEBUG)
            Log.d(LOG_TAG_PROXY, "initiatePaymentVerification: initiating payment verification")

        // if worker is already running, don't start another one
        val workInfos = WorkManager.getInstance(appContext).getWorkInfosByTag(PAYMENT_WORKER_TAG)
        if (workInfos.get().any { it.state == WorkInfo.State.RUNNING }) {
            Log.i(LOG_TAG_PROXY, "initiatePaymentVerification: worker already running")
            return
        }

        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())

        val paymentWorker =
            OneTimeWorkRequestBuilder<PaymentWorker>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(PAYMENT_WORKER_TAG)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()

        Log.i(LOG_TAG_PROXY, "initiatePaymentVerification: enqueuing payment worker")
        WorkManager.getInstance(appContext).beginWith(paymentWorker).enqueue()
    }

    suspend fun enable(id: Int = DEFAULT_ID) {
        val tcpProxy = tcpProxies.find { it.id == id }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "enable: tcpProxy not found for id: $id")
            return
        }

        tcpProxy.isActive = true
        tcpProxyRespository.update(tcpProxy)
        appConfig.addProxy(AppConfig.ProxyType.TCP, AppConfig.ProxyProvider.TCP)
    }

    suspend fun disable(id: Int = DEFAULT_ID) {
        val tcpProxy = tcpProxies.find { it.id == id }
        if (tcpProxy == null) {
            Log.w(LOG_TAG_PROXY, "disable: tcpProxy not found for id: $id")
            return
        }

        tcpProxy.isActive = false
        tcpProxyRespository.update(tcpProxy)
        appConfig.removeProxy(AppConfig.ProxyType.TCP, AppConfig.ProxyProvider.TCP)
    }

    fun getActiveTcpProxy(): TcpProxyEndpoint? {
        return tcpProxies.find { it.isActive }
    }

    fun isTcpProxyEnabled(): Boolean {
        return tcpProxies.any { it.isActive }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
