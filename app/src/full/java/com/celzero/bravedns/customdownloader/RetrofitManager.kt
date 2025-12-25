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
import com.celzero.bravedns.util.Constants
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import retrofit2.Retrofit
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class RetrofitManager {

    companion object {
        enum class OkHttpDnsType {
            DEFAULT,
            CLOUDFLARE,
            GOOGLE,
            SYSTEM_DNS,
            FALLBACK_DNS
        }

        fun getBlocklistBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.DOWNLOAD_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun getTcpProxyBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.TCP_PROXY_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun getIpInfoBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.IP_INFO_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun okHttpClient(isRinRActive: Boolean): OkHttpClient {
            val b = OkHttpClient.Builder()
            b.connectTimeout(1, TimeUnit.MINUTES)
            b.readTimeout(20, TimeUnit.MINUTES)
            b.writeTimeout(5, TimeUnit.MINUTES)
            b.retryOnConnectionFailure(true)
            // If unset, the system-wide default DNS will be used.
            // no need to add custom dns if rinr is not active, as the connections will be routed
            // through the default dns
            if (isRinRActive) {
                customDns(b.build())?.let { b.dns(it) }
            }
            return b.build()
        }

        // As of now, quad9 is used as default dns in okhttp client.
        private fun customDns(bootstrapClient: OkHttpClient): Dns? {
            enumValues<OkHttpDnsType>().forEach { it ->
                try {
                    when (it) {
                        OkHttpDnsType.DEFAULT -> {
                            return DnsOverHttps.Builder()
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
                        }
                        OkHttpDnsType.CLOUDFLARE -> {
                            return DnsOverHttps.Builder()
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
                        }
                        OkHttpDnsType.GOOGLE -> {
                            return DnsOverHttps.Builder()
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
                        }
                        OkHttpDnsType.SYSTEM_DNS -> {
                            return Dns.SYSTEM
                        }
                        OkHttpDnsType.FALLBACK_DNS -> {
                            // todo: return retrieved system dns
                            return null
                        }
                    }
                } catch (e: Exception) {
                    Logger.crash(Logger.LOG_TAG_DOWNLOAD, "err; custom dns: ${e.message}", e)
                }
            }
            return null
        }

        private fun getByIp(ip: String): InetAddress {
            return try {
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_DOWNLOAD, "err while getting ip address: ${e.message}", e)
                throw e
            }
        }
    }
}
