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
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class NetworkConnectionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {

        val request: Request = chain.request()
        var response = chain.proceed(request)
        var retryCount = 0

        while (!response.isSuccessful && retryCount < 3) {
            retryCount++

            // retry the request
            response = chain.proceed(request)
        }

        return response
    }
}
