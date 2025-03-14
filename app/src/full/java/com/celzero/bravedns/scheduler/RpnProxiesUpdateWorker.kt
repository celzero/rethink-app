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

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val type = inputData.getInt("type", -1)
        if (type == -1) {
            Logger.e(LOG_TAG_SCHEDULER, "invalid type received for RpnProxiesUpdateWorker")
            return Result.failure()
        }
        val rpnType = RpnProxyManager.RpnType.fromId(type)
        Logger.i(LOG_TAG_SCHEDULER, "rpn proxies update worker executed for $type at $now")
        val res = updateExpiryStatus(rpnType)
        return if (res) Result.success() else Result.failure()
    }

    private suspend fun updateExpiryStatus(type: RpnProxyManager.RpnType): Boolean {
        Logger.d(LOG_TAG_SCHEDULER, "$type has expired and is being processed")
        val bytes = RpnProxyManager.refreshRpnCreds(type)
        Logger.i(LOG_TAG_SCHEDULER, "refresh cred for $type, success? ${bytes != null}")
        val res = when (type) {
            RpnProxyManager.RpnType.WARP -> {
                RpnProxyManager.updateWarpConfig(bytes)
            }
            RpnProxyManager.RpnType.AMZ -> {
                RpnProxyManager.updateAmzConfig(bytes)
            }
            RpnProxyManager.RpnType.PROTON -> {
                RpnProxyManager.updateProtonConfig(bytes)
            }
            else -> {
                // Do nothing
                false
            }
        }
        Logger.d(LOG_TAG_SCHEDULER, "new creds updated for $type, success? $res")
        return res
    }
}
