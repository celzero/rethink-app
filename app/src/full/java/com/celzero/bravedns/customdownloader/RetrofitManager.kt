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

import android.util.Log
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import retrofit2.Retrofit

class RetrofitManager {

    companion object {
        enum class OkHttpDnsType {
            DEFAULT,
            CLOUDFLARE,
            GOOGLE,
            SYSTEM_DNS,
            FALLBACK_DNS
        }

        fun getBlocklistBaseBuilder(dnsType: OkHttpDnsType): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.DOWNLOAD_BASE_URL)
                .client(okHttpClient(dnsType))
        }

        fun okHttpClient(dnsType: OkHttpDnsType): OkHttpClient {
            val b = OkHttpClient.Builder()
            b.connectTimeout(1, TimeUnit.MINUTES)
            b.readTimeout(20, TimeUnit.MINUTES)
            b.writeTimeout(5, TimeUnit.MINUTES)
            b.retryOnConnectionFailure(true)
            // If unset, the system-wide default DNS will be used.
            customDns(dnsType, b.build())?.let { b.dns(it) }
            return b.build()
        }

        // As of now, quad9 is used as default dns in okhttp client.
        private fun customDns(dnsType: OkHttpDnsType, bootstrapClient: OkHttpClient): Dns? {
            try {
                when (dnsType) {
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
                Log.e(
                    LoggerConstants.LOG_TAG_DOWNLOAD,
                    "Exception while getting custom dns: ${e.message}",
                    e
                )
                return null
            }
        }

        private fun getByIp(ip: String): InetAddress {
            return try {
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                Log.e(
                    LoggerConstants.LOG_TAG_DOWNLOAD,
                    "Exception while getting ip address: ${e.message}",
                    e
                )
                throw e
            }
        }
    }
}
