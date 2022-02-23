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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object ConnectionCheckHelper {

    // download request status
    enum class DownloadStatus {
        FAILED, PAUSED, RUNNING, SUCCESSFUL
    }

    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        val activeNetworkInfo = connectivityManager?.activeNetwork
        return activeNetworkInfo != null && connectivityManager.getNetworkCapabilities(
            activeNetworkInfo)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    var downloadIds: MutableMap<Long, DownloadStatus> = hashMapOf()
}
