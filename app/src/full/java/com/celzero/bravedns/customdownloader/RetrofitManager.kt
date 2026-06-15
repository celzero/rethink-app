/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.customdownloader

import Logger
import Logger.LOG_OKHTTP
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Daemons
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import retrofit2.Retrofit
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.enums.enumEntries

class RetrofitManager {

    init {
        // enable the OkHttp's logging only in debug mode for testing
        if (DEBUG) OkHttpDebugLogging.enableHttp2()
        if (DEBUG) OkHttpDebugLogging.enableTaskRunner()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MINUTES = 1L
        private const val READ_TIMEOUT_MINUTES = 20L
        private const val WRITE_TIMEOUT_MINUTES = 5L

        enum class OkHttpDnsType {
            DEFAULT,
            CLOUDFLARE,
            GOOGLE,
            SYSTEM_DNS,
            FALLBACK_DNS
        }

        // Single-thread dispatcher dedicated to fire-and-forget log I/O so that
        // log writes never block OkHttp's network threads.
        private val logScope = CoroutineScope(Daemons.make("RayIdLogger"))

        /**
         * Ray-ID interceptor: captures Cloudflare's cf-ray header and any "ray" field in the body,
         * logging them
         */
        val rayIdInterceptor = Interceptor { chain ->
            val request = chain.request()
            val reqTime = System.currentTimeMillis()

            // Log request line asynchronously
            logScope.launch {
                val ts = Utilities.convertLongToTime(reqTime, Constants.TIME_FORMAT_4)
                val msg = "$ts --> ${request.method} ${request.url}"
                Logger.d(LOG_OKHTTP, msg)
                Logger.wireLog(msg)
            }

            val response = chain.proceed(request)
            val respTime = System.currentTimeMillis()

            try {
                val cfRay = response.header("cf-ray")
                val responseBody = response.body

                if (responseBody != null) {
                    // Only peek at a small prefix (max 8KB) to avoid OOM on
                    // large downloads like the blocklist trie. peek() does not
                    // consume the underlying source, so the caller can still
                    // read the full body.
                    val rayId: String? = try {
                        val bytesToRead = responseBody.contentLength()
                            .takeIf { it in 1..8192 }
                            ?: 8192L
                        val bodyPrefix = responseBody.source()
                            .peek()
                            .readUtf8(bytesToRead)
                        Regex("\"ray\"\\s*:\\s*\"([^\"]+)\"")
                            .find(bodyPrefix)?.groupValues?.get(1)
                    } catch (_: Exception) {
                        null
                    }

                    logScope.launch {
                        val ts = Utilities.convertLongToTime(respTime, Constants.TIME_FORMAT_4)
                        val prefix = "$ts <-- ${response.code} ${request.method} ${request.url}"
                        if (cfRay != null) {
                            val msg = "$prefix | cf-ray: $cfRay"
                            Logger.d(LOG_OKHTTP, msg)
                            Logger.wireLog(msg)
                        }
                        if (rayId != null) {
                            val msg = "$prefix | ray: $rayId"
                            Logger.d(LOG_OKHTTP, msg)
                            Logger.wireLog(msg)
                        }
                        if (cfRay == null && rayId == null) {
                            val msg = "$prefix | no-ray"
                            Logger.d(LOG_OKHTTP, msg)
                            Logger.wireLog(msg)
                        }
                    }
                } else {
                    logScope.launch {
                        val ts = Utilities.convertLongToTime(respTime, Constants.TIME_FORMAT_4)
                        val prefix = "$ts <-- ${response.code} ${request.method} ${request.url}"
                        val msg = if (cfRay != null) "$prefix | cf-ray: $cfRay" else "$prefix | no-body no-ray"
                        Logger.d(LOG_OKHTTP, msg)
                        Logger.wireLog(msg)
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_OKHTTP, "err extracting ray-id from response: ${e.message}", e)
            }
            response
        }

        fun getBlocklistBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.DOWNLOAD_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun getRpnBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.RPN_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun getIpInfoBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.IP_INFO_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun okHttpClient(isRinRActive: Boolean): OkHttpClient {
            val b = OkHttpClient.Builder()
            b.connectTimeout(CONNECT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            b.readTimeout(READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            b.writeTimeout(WRITE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            b.retryOnConnectionFailure(true)
            // Always active: captures cf-ray header and body ray-id; never logs
            // request headers (cid / did / sessionToken stay out of logs).
            b.addInterceptor(rayIdInterceptor)
            // If unset, the system-wide default DNS will be used.
            // no need to add custom dns if rinr is not active, as the connections will be routed
            // through the default dns
            if (isRinRActive) {
                customDns(b.build())?.let { b.dns(it) }
            }
            return b.build()
        }

        // Attempts DNS providers in priority order: Quad9 → Cloudflare → Google → System → Fallback.
        // If a provider fails to construct or resolve, the next is tried transparently at runtime.
        private fun customDns(bootstrapClient: OkHttpClient): Dns? {
            val providers = mutableListOf<Dns>()
            enumEntries<OkHttpDnsType>().forEach {
                try {
                    val dns = when (it) {
                        OkHttpDnsType.DEFAULT -> DnsOverHttps.Builder()
                            .client(bootstrapClient)
                            .url("https://dns.quad9.net/dns-query".toHttpUrl())
                            .bootstrapDnsHosts(
                                getByIp("9.9.9.9"),
                                getByIp("149.112.112.112"),
                                getByIp("2620:fe::9"),
                                getByIp("2620:fe::fe")
                            )
                            .includeIPv6(true)
                            .build()
                        OkHttpDnsType.CLOUDFLARE -> DnsOverHttps.Builder()
                            .client(bootstrapClient)
                            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                            .bootstrapDnsHosts(
                                getByIp("1.1.1.1"),
                                getByIp("1.0.0.1"),
                                getByIp("2606:4700:4700::1111"),
                                getByIp("2606:4700:4700::1001")
                            )
                            .includeIPv6(true)
                            .build()
                        OkHttpDnsType.GOOGLE -> DnsOverHttps.Builder()
                            .client(bootstrapClient)
                            .url("https://dns.google/dns-query".toHttpUrl())
                            .bootstrapDnsHosts(
                                getByIp("8.8.8.8"),
                                getByIp("8.8.4.4"),
                                getByIp("2001:4860:4860:0:0:0:0:8888"),
                                getByIp("2001:4860:4860:0:0:0:0:8844")
                            )
                            .includeIPv6(true)
                            .build()
                        OkHttpDnsType.SYSTEM_DNS -> Dns.SYSTEM
                        OkHttpDnsType.FALLBACK_DNS -> null
                    }
                    dns?.let { providers.add(it) }
                } catch (e: Exception) {
                    Logger.crash(Logger.LOG_TAG_DOWNLOAD, "err; custom dns: ${e.message}", e)
                }
            }
            return if (providers.isEmpty()) null else FallbackDns(providers)
        }

        private fun getByIp(ip: String): InetAddress {
            return try {
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_DOWNLOAD, "err while getting ip address: ${e.message}", e)
                throw e
            }
        }

        private class FallbackDns(private val providers: List<Dns>) : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                for (provider in providers) {
                    try {
                        val result = provider.lookup(hostname)
                        if (result.isNotEmpty()) {
                            return result
                        }
                    } catch (_: Exception) {
                    }
                }
                throw UnknownHostException(
                    "All DNS providers failed for $hostname"
                )
            }
        }
    }
}
