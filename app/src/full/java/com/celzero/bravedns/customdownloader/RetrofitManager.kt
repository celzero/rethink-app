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
            SYSTEM_DNS
        }

        fun getBlocklistBaseBuilder(dnsType: OkHttpDnsType): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.DOWNLOAD_BASE_URL)
                .client(okHttpClient(dnsType))
        }

        fun okHttpClient(dnsType: OkHttpDnsType): OkHttpClient {
            val okhttpClientBuilder = OkHttpClient.Builder()
            okhttpClientBuilder.callTimeout(5, TimeUnit.MINUTES)
            okhttpClientBuilder.connectTimeout(5, TimeUnit.MINUTES)
            okhttpClientBuilder.readTimeout(20, TimeUnit.MINUTES)
            okhttpClientBuilder.writeTimeout(20, TimeUnit.MINUTES)
            okhttpClientBuilder.retryOnConnectionFailure(true)
            // If unset, the system-wide default DNS will be used.
            customDns(dnsType)?.let { okhttpClientBuilder.dns(it) }
            return okhttpClientBuilder.build()
        }

        // As of now, quad9 is used as default dns in okhttp client.
        private fun customDns(dnsType: OkHttpDnsType): Dns? {
            when (dnsType) {
                OkHttpDnsType.DEFAULT -> {
                    return DnsOverHttps.Builder()
                        .client(OkHttpClient())
                        .url("https://dns.quad9.net/dns-query".toHttpUrl())
                        .bootstrapDnsHosts(
                            InetAddress.getByName("9.9.9.9"),
                            InetAddress.getByName("149.112.112.112"),
                            InetAddress.getByName("2620:fe::9"),
                            InetAddress.getByName("2620:fe::fe")
                        )
                        .build()
                }
                OkHttpDnsType.CLOUDFLARE -> {
                    return DnsOverHttps.Builder()
                        .client(OkHttpClient())
                        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                        .bootstrapDnsHosts(
                            InetAddress.getByName("1.1.1.1"),
                            InetAddress.getByName("1.0.0.1"),
                            InetAddress.getByName("2606:4700:4700::1111"),
                            InetAddress.getByName("2606:4700:4700::1001")
                        )
                        .build()
                }
                OkHttpDnsType.GOOGLE -> {
                    return DnsOverHttps.Builder()
                        .client(OkHttpClient())
                        .url("https://dns.google/dns-query".toHttpUrl())
                        .bootstrapDnsHosts(
                            InetAddress.getByName("8.8.8.8"),
                            InetAddress.getByName("8.8.4.4"),
                            InetAddress.getByName("2001:4860:4860:0:0:0:0:8888"),
                            InetAddress.getByName("2001:4860:4860:0:0:0:0:8844")
                        )
                        .build()
                }
                OkHttpDnsType.SYSTEM_DNS -> {
                    return null
                }
            }
        }
    }
}
