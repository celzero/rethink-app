/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.scheduler

import Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.rpnproxy.RpnProxyManager

class RpnProxiesUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RpnUpdateWorker(rpn)"
    }

    override suspend fun doWork(): Result {
        var type = -1 // default value
        try {
            val now = System.currentTimeMillis()
            type = inputData.getInt("type", -1)
            if (type == -1) {
                Logger.e(LOG_TAG_SCHEDULER, "$TAG invalid proxy type received")
                return Result.failure()
            }
            val rpnType = RpnProxyManager.RpnType.fromId(type)
            val res = updateExpiryStatus(rpnType)
            Logger.i(LOG_TAG_SCHEDULER, "$TAG update worker completed for $type at $now, res? $res")
            return if (res) Result.success() else retryIfNeeded()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_SCHEDULER, "$TAG err on update, type($type), ${e.message}")
            return retryIfNeeded()
        }
    }

    private fun retryIfNeeded(): Result {
        val maxRetryCount = 3
        if (runAttemptCount >= maxRetryCount) {
            Logger.w(LOG_TAG_SCHEDULER, "max retry count reached for $TAG, count: $runAttemptCount")
            return Result.failure()
        }
        Logger.i(LOG_TAG_SCHEDULER, "retrying $TAG, attempt: $runAttemptCount")
        return Result.retry()
    }

    private suspend fun updateExpiryStatus(type: RpnProxyManager.RpnType): Boolean {
        Logger.d(LOG_TAG_SCHEDULER, "$type has expired and is being processed")
        var res = RpnProxyManager.refreshRpnCreds(type)
        Logger.i(LOG_TAG_SCHEDULER, "refresh cred for $type, success? $res")

        if (!res) {
            Logger.e(LOG_TAG_SCHEDULER, "$TAG refresh-cred failed for $type, trying fresh register")
            // register is a fresh registration with null creds, happens when refresh fails
            res = RpnProxyManager.registerNewProxy(type)
        }
        Logger.d(LOG_TAG_SCHEDULER, "new creds updated for $type, success? $res")
        return res
    }
}
