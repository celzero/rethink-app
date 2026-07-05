/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.util.Utilities.togb
import com.celzero.bravedns.util.Utilities.togs
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.firestack.backend.Backend
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID

class CheckoutViewModel(val app: Application, private val persistentState: PersistentState) : AndroidViewModel(app) {

    private val _paymentStatus = MutableStateFlow(TcpProxyHelper.getTcpProxyPaymentStatus())
    val paymentStatus = _paymentStatus.asStateFlow()

    init {
        // create and handle keys if not paid
        if (TcpProxyHelper.getTcpProxyPaymentStatus().isNotPaid()) {
            handleKeys()
        }
        
        // Generate random tokens/uuids for logs/tracking as done in Activity
        // UUID.randomUUID().toString().let { uuid -> Napier.d("UUID: $uuid") }
        // generateRandomHexToken(TOKEN_LENGTH).let { token -> Napier.d("Token: $token") }
        // These were just logged in Activity, maybe not needed? Keeping them for parity if they had side effects.
        // They didn't seem to store anything? 
        // "generateRandomHexToken(TOKEN_LENGTH).let { token -> Napier.d("Token: $token") }"
    }

    private fun handleKeys() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = TcpProxyHelper.getPublicKey()
                Napier.d("Public Key: $key")
                // if there is a key state, the msgOrExistingState (keyState.msg/keyState.v()) should not be empty
                val keyGenerator = Backend.newPipKeyProvider(key.togb(), "".togs())
                val keyState = keyGenerator.blind()
                // id: use 64 chars as account id
                val id = keyState.msg.opaque()?.s ?: ""
                val accountId = id.substring(0, 64)
                // rest of the keyState values will never be used in kotlin

                // keyState.v() should be retrieved from the file system
                Backend.newPipKeyStateFrom(keyState.v()) // retrieve the key state alone

                Napier.d("Blind: $keyState")
                val path =
                    File(
                        app.filesDir.canonicalPath +
                            File.separator +
                            TcpProxyHelper.TCP_FOLDER_NAME +
                            File.separator +
                            TcpProxyHelper.PIP_KEY_FILE_NAME
                    )
                EncryptedFileManager.writeTcpConfig(app, keyState.v().tos() ?: "", TcpProxyHelper.PIP_KEY_FILE_NAME)
                val content = EncryptedFileManager.read(app, path)
                Napier.d("Content: $content")
            } catch (e: Exception) {
                Napier.e("err in handleKeys: ${e.message}", e)
            }
        }
    }

    val paymentWorkInfo = WorkManager.getInstance(app).getWorkInfosByTagLiveData(TcpProxyHelper.PAYMENT_WORKER_TAG)

    fun updatePaymentStatusFromWorkInfo(workInfoList: List<WorkInfo>) {
        val workInfo = workInfoList.firstOrNull() ?: return
        if (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING) {
             _paymentStatus.value = TcpProxyHelper.getTcpProxyPaymentStatus()
        } else if (workInfo.state == WorkInfo.State.SUCCEEDED) {
             _paymentStatus.value = TcpProxyHelper.getTcpProxyPaymentStatus()
             WorkManager.getInstance(app).pruneWork()
        } else if (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED) {
             _paymentStatus.value = TcpProxyHelper.getTcpProxyPaymentStatus()
             WorkManager.getInstance(app).pruneWork()
             WorkManager.getInstance(app).cancelAllWorkByTag(TcpProxyHelper.PAYMENT_WORKER_TAG)
        }
    }

    fun startPayment() {
        TcpProxyHelper.initiatePaymentVerification(app)
        _paymentStatus.value = TcpProxyHelper.getTcpProxyPaymentStatus()
    }
}
