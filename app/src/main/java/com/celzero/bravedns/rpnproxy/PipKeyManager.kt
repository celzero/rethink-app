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
package com.celzero.bravedns.rpnproxy

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import com.celzero.bravedns.customdownloader.ITcpProxy
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities.togb
import com.celzero.bravedns.util.Utilities.togs
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.PipMsg
import com.google.gson.Gson
import kotlinx.serialization.SerializationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Calendar

object PipKeyManager : KoinComponent {

    /*
        * This class is used to manage the keys for the RPN proxy.
        * It provides methods to generate, store, and retrieve keys.
        * get the public key for the RPN proxy from https://redir.nile.workers.dev/r/{app_version}
        * This will return a JSON object with the public key.
        * response: {"minvcode":"30","pubkey":"{\"key_ops\":[\"verify\"],\"ext\":true,\"kty\":\"RSA\",\"n\":\"zON5Gyeeg1QaV_CoFImhWF9TykAZo5pJm9NWd5IPTiYtlhb0WMpFm_-IotJn7ZCGszhl4NMxMHV8odyRbBhPg440qucudBkm0T460f2Id3HBtzoJVLI0SvOmSqm5kY41Zdkxcb_fkpKm-D6c_RnMsmEHvP7WI-YlK108PIpp5ZBvoY3oOA3yktGAm3uaWkjSsw6FmNq34AL3oMA-5MFER-uAq0faXMo8_yEOVcI6Rik_e8wxe4GSnPpndODApzbGyhlORJQSCWbnO6Va-1yeGgkOQ3RFICXrsyyngQbVVOSg9UcAuICzQSW-nlUNF99l_NdrHAaxHpexSSnfdFJ4IQ\",\"e\":\"AQAB\",\"alg\":\"PS384\"}","status":"ok"}
        * The public key is in the "pubkey" field of the JSON object.
        * It has a key "pubkey" which contains the public key in JSON Web Key (JWK) format.
        * this need to converted into byte array to pass it to Backend.newPipKeyProvider
        * newPipKeyProvider takes two parameters, the first is the public key in byte array format and the second is msgOrExistingState
        * initially the msgOrExistingState is empty to get the key generator
        * if you blind the keyGenerator, it will return the key state
        * keystate is a struct with many fields, only field used in kotlin is the id field
        * id field returns a hex string of about 64 characters
        * use the 64 characters as accoundId for google pay services
        *
        * store the keystate.v() in a encrypted file in the app's internal storage
        * to again retrieve the key generator, read the file and pass the v() to Backend.newPipKeyProvider
        * This will return the key generator which can be used to get the state of the key again
        * These keys are rotated every 6 months, so the app should check for the key rotation
     */

    private const val TAG = "PipKeyManager"
    private const val KEY_ROTATION_PERIOD_MONTHS = 3

    private const val STATUS_OK = "ok"

    private const val PIP_FOLDER_NAME = "pip"
    private const val PIP_KEY_FILE_NAME = "pip_key.key"
    private const val PIP_MESSAGE_FILE_NAME = "pip_message.message"
    private const val PIP_TOKEN_FILE_NAME = "pip_token.token"

    private const val ERR_NOT_AVAILABLE = "Rethink+ is not available for your device"
    private const val ERR_UPDATE_REQUIRED = "Rethink+ requires an update to the app"
    private const val ERR_UNSUPPORTED_VERSION = "Rethink+ is not supported on this version of the app"
    private const val ERR_UNSUPPORTED_DEVICE = "Rethink+ is not supported on this device"
    private const val ERR_INTERNET_UNAVAILABLE = "No internet connection. Please check your network and try again."

    private const val MAX_RETRY_COUNT = 3

    private val persistentState by inject<PersistentState>()

    private var isRethinkPlusAvailableForDevice: Boolean? = null
    private var recentFailureError = ""

    data class ApiResponse(
        val minvcode: String,
        val pubkey: String, // This will hold the string representation of the inner JSON
        val status: String
    )

    suspend fun getKeyState(context: Context): KeyState? {
        // Load the key state from encrypted storage
        var storedKeyState = loadKeyState(context)

        Logger.d(LOG_TAG_PROXY, "$TAG is key state loaded? ${storedKeyState != null}")
        // ideally the key state should be present in the storage after the first run
        if (storedKeyState == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG no key state found in storage")
            publicKeyUsable(context, 0, true).let { usable ->
                if (!usable.first) {
                    Logger.w(LOG_TAG_PROXY, "$TAG public key is not usable")
                    return null
                }
            }
            // public key is usable, generate a new key state and will store in encrypted storage
            // we can assume that the public key by reading from storage
            // TODO: should we get the response from the publicKeyUsable method instead of
            // reading from storage again?
            Logger.i(LOG_TAG_PROXY, "$TAG generating new key state")
            storedKeyState = loadKeyState(context)
            if (storedKeyState == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG failed to load key state after generation")
                return null
            }
        }

        Logger.i(LOG_TAG_PROXY, "$TAG loaded key state: ${storedKeyState.accountId}")
        return storedKeyState
    }

    suspend fun getToken(context: Context): String {
        var tokenString = fetchPipToken(context)
        if (tokenString.isNullOrEmpty() || needsRotation(persistentState.pipKeyRotationTime)) {
            Logger.i(LOG_TAG_PROXY, "$TAG token needs rotation, last rotation time: ${persistentState.pipKeyRotationTime}")
            val token = Backend.token()
            // if the token needs to be rotated, we can call rotate() method
            // this will return a new PipMsg with the rotated token
            val pipMsg = Backend.newPipMsgWith(token).rotate()
            // update the token string with the new token from the PipMsg
            tokenString = pipMsg.opaque().s
            saveToken(context, tokenString)
            Logger.i(LOG_TAG_PROXY, "$TAG pip-token saved successfully")
        }

        return tokenString
    }

    // see if the rethink plus can be activated, the url will let us know if the status is ok
    // if plus needs to be suspended, then make use of this method
    suspend fun isRethinkPlusActive(context: Context): Pair<Boolean, String> {
        val lastRotationTime = getLastKeyRotationTime()

        // Check if the key needs to be rotated
        if (needsRotation(lastRotationTime)) {
            Logger.i(LOG_TAG_PROXY, "$TAG key rotation needed, last rotation time: $lastRotationTime")
            // If the key needs to be rotated, we can call publicKeyUsable to refresh the key
            return publicKeyUsable(context, shouldPersistResult = true)
        }

        val works = isRethinkPlusAvailableForDevice
        // only check the availability if we don't have a cached value or if the error is cached
        if (works != null && recentFailureError.isEmpty()) {
            // If we already know the availability, return it
            Logger.i(LOG_TAG_PROXY, "$TAG returning cached availability: $works")
            return Pair(works, if (!works) recentFailureError else "")
        }

        Logger.i(LOG_TAG_PROXY, "$TAG key rotation not needed, last rotation time: $lastRotationTime")
        return publicKeyUsable(context, shouldPersistResult = false)
    }


    /**
     * Checks if the public key is usable by making a network request.
     * Retries if necessary, up to a maximum number of attempts.
     * @param retryCount The current retry count.
     * @param shouldPersistResult Whether to persist the result of the public key check.
     * @return True if the public key is usable, false otherwise.
     */
    private suspend fun publicKeyUsable(context: Context, retryCount: Int = 0, shouldPersistResult: Boolean): Pair<Boolean, String> {
        var works = false
        try {
            val retrofit =
                RetrofitManager.getTcpProxyBaseBuilder(persistentState.routeRethinkInRethink)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            /**
             * for response: see [ITcpProxy.getPublicKey]
             */
            val retrofitInterface = retrofit.create(ITcpProxy::class.java)

            val response = retrofitInterface.getPublicKey(persistentState.appVersion.toString())

            if (response?.isSuccessful == false) {
                Logger.w(
                    LOG_TAG_PROXY,
                    "$TAG publicKeyUsable: response is not successful, code: ${response.code()}, message: ${response.message()}"
                )
                return Pair(false, ERR_NOT_AVAILABLE)
            }

            val responseBody = response?.body()

            if (responseBody == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG publicKeyUsable: response body is null")
                return Pair(false, ERR_NOT_AVAILABLE)
            }

            Logger.v(LOG_TAG_PROXY, "$TAG publicKeyUsable: response body size: ${responseBody.size()}")
            // parse the response body to ApiResponse
            val apiResponse = Gson().fromJson(responseBody, ApiResponse::class.java)
            works = apiResponse.status == STATUS_OK
            val minVersionCode = apiResponse.minvcode.toIntOrNull()
            val publicKey = apiResponse.pubkey

            if (minVersionCode == null || minVersionCode > persistentState.appVersion) {
                Logger.w(
                    LOG_TAG_PROXY,
                    "$TAG publicKeyUsable: minvcode $minVersionCode is less than app version ${persistentState.appVersion}"
                )
                isRethinkPlusAvailableForDevice = false
                recentFailureError = ERR_UNSUPPORTED_VERSION
                return Pair(false, recentFailureError)
            }

            if (shouldPersistResult) {
                val pubKeyBytes = getPubKeyAsJsonBytes(apiResponse.pubkey)
                if (pubKeyBytes == null || pubKeyBytes.isEmpty()) {
                    Logger.w(LOG_TAG_PROXY, "$TAG failed to parse public key from response")
                    recentFailureError = ERR_NOT_AVAILABLE
                    isRethinkPlusAvailableForDevice = false
                    return Pair(false, ERR_NOT_AVAILABLE)
                }
                val message = fetchPipMsg(context)?.asGostr()?.tos() ?: ""
                val keyState = generateAndSaveKeyState(context, pubKeyBytes, message)
                if (keyState == null) {
                    Logger.w(LOG_TAG_PROXY, "$TAG failed to generate key state from public key")
                    recentFailureError = ERR_NOT_AVAILABLE
                    isRethinkPlusAvailableForDevice = false
                    return Pair(false, recentFailureError)
                }
                saveLastKeyRotationTime()
            }

            Logger.i(LOG_TAG_PROXY, "$TAG publicKeyUsable: minvcode: $minVersionCode, pub-key: ${publicKey.length}, status: ${apiResponse.status}")
            recentFailureError = ""
            isRethinkPlusAvailableForDevice = works
            return Pair(works, "")
        } catch (e: UnknownHostException) {
            Logger.w(LOG_TAG_PROXY, "$TAG err; no internet connection: ${e.message}")
            recentFailureError = ERR_INTERNET_UNAVAILABLE
            isRethinkPlusAvailableForDevice = false
            return Pair(false, ERR_INTERNET_UNAVAILABLE)
        } catch (e: ConnectException) {
            Logger.w(LOG_TAG_PROXY, "$TAG err; onnection failed: ${e.message}")
            recentFailureError = ERR_INTERNET_UNAVAILABLE
            isRethinkPlusAvailableForDevice = false
            return Pair(false, ERR_INTERNET_UNAVAILABLE)
        } catch (e: SocketTimeoutException) {
            Logger.w(LOG_TAG_PROXY, "$TAG err; onnection timed out: ${e.message}")
            recentFailureError = ERR_INTERNET_UNAVAILABLE
            isRethinkPlusAvailableForDevice = false
            return Pair(false, ERR_INTERNET_UNAVAILABLE)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err; public key usable: ${e.message}")
        }

        return if (isRetryRequired(retryCount) && !works) {
            Logger.i(LOG_TAG_PROXY, "$TAG retrying publicKeyUsable for $retryCount")
            publicKeyUsable(context, retryCount + 1, shouldPersistResult)
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG retry count exceeded for publicKeyUsable")
            recentFailureError = ERR_NOT_AVAILABLE
            isRethinkPlusAvailableForDevice = false
            return Pair(false, ERR_NOT_AVAILABLE)
        }
    }

    private fun isRetryRequired(retryCount: Int): Boolean {
        return retryCount < MAX_RETRY_COUNT
    }

    private fun getPubKeyAsJsonBytes(pubKeyString: String): ByteArray? {
        try {
            Logger.v(LOG_TAG_PROXY, "$TAG public key string: $pubKeyString")
            if (pubKeyString.isNotEmpty()) {
                // convert the pubKey JSON string to a byte array using UTF-8 encoding
                Logger.i(LOG_TAG_PROXY, "$TAG converting public key string to byte array")
                return pubKeyString.toByteArray(StandardCharsets.UTF_8)
            } else {
                Logger.i(LOG_TAG_PROXY, "$TAG public key is empty in the response")
                return null
            }
        } catch (e: SerializationException) {
            Logger.i(LOG_TAG_PROXY, "$TAG failed to parse public key JSON: ${e.message}")
            return null
        } catch (e: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG err while parsing public key: ${e.message}")
            return null
        }
    }


    /**
     * Generates a new key state using the public key
     */
    private fun generateAndSaveKeyState(context: Context, publicKeyJwk: ByteArray, message: String): KeyState? {
        try {
            Logger.d(LOG_TAG_PROXY, "$TAG public key size: ${publicKeyJwk.size}")
            val keyGenerator = Backend.newPipKeyProvider(publicKeyJwk.togb(), message.togs())
            val keyState = keyGenerator.blind()

            keyState.msg.asGostr().tos()


            val id = keyState.msg.opaque().s ?: ""
            if (id.length < 64) {
                Logger.i(LOG_TAG_PROXY, "$TAG invalid key state ID length: ${id.length}")
                return null
            }

            val accountId = id.substring(0, 64)

            val keyStateValue = keyState.v().tos()
            if (keyStateValue.isNullOrEmpty()) {
                Logger.i(LOG_TAG_PROXY, "$TAG key state value is null or empty")
                return null
            }
            saveKeyState(context, keyStateValue)

            // keystate.msg will return empty/null in case of error, no exception is thrown
            // to retrieve the message, use Backend.asPipMsg(keyState.msg.asGostr())
            // this will return the message in PipMsg format
            saveMessage(context, keyState.msg.asGostr().tos())
            return KeyState(accountId)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err generating key state: ${e.message}", e)
        }
        return null
    }

    /**
     * Saves the key state to encrypted storage
     */
    private fun saveKeyState(context: Context, keyStateValue: String): Boolean {
        return try {
            val file = getPipKeyFilePath(context, PIP_KEY_FILE_NAME)
            // ensure the parent directory exists
            file.parentFile?.mkdirs()
            val res = EncryptedFileManager.write(context, keyStateValue, file)
            Logger.d(LOG_TAG_PROXY, "$TAG save key state, res? $res, file: ${file.absolutePath}")
            res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err saving key state: ${e.message}", e)
            false
        }
    }

    private fun saveToken(context: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG token is null or empty, not saving")
            return false
        }
        return try {
            val file = getPipKeyFilePath(context, PIP_TOKEN_FILE_NAME)
            // ensure the parent directory exists
            file.parentFile?.mkdirs()
            val res = EncryptedFileManager.write(context, token, file)
            Logger.d(LOG_TAG_PROXY, "$TAG save token, res? $res, file: ${file.absolutePath}")
            persistentState.pipKeyRotationTime = System.currentTimeMillis() // update the last rotation time
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err saving token: ${e.message}", e)
            false
        }
    }

    private fun saveMessage(context: Context, message: String?): Boolean {
        if (message.isNullOrEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG message is null or empty, not saving")
            return false
        }
        return try {
            val file = getPipKeyFilePath(context, PIP_MESSAGE_FILE_NAME)
            // ensure the parent directory exists
            file.parentFile?.mkdirs()
            val res =EncryptedFileManager.write(context, message, file)
            Logger.d(LOG_TAG_PROXY, "$TAG save message, res? $res, file: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err saving message: ${e.message}", e)
            false
        }
    }

    /**
     * Loads the key state from encrypted storage
     */
    private fun loadKeyState(context: Context): KeyState? {
        return try {
            val path = getPipKeyFilePath(context, PIP_KEY_FILE_NAME)
            if (!path.exists()) {
                return null
            }
            val k = EncryptedFileManager.read(context, path)
            if (k.isEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG key state file is empty or does not exist")
                return null
            }
            fetchKeyState(context, k)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err loading key state: ${e.message}", e)
            null
        }
    }

    /**
     * Gets the path to the PIP key file
     */
    private fun getPipKeyFilePath(context: Context, which: String): File {
        val parentDir = File(context.filesDir, PIP_FOLDER_NAME)
        // No need to call mkdirs() here as it's handled in saveKeyState/saveMessage
        // but it wouldn't hurt to ensure it for read operations if they could happen before any write.
        // However, for this specific error, the write path is the problem.
        return File(parentDir, which)
    }

    /**
     * Recreates key state from stored value
     */
    private fun fetchKeyState(context: Context, storedKeyState: String): KeyState? {
        return try {
            val keyState = Backend.newPipKeyStateFrom(storedKeyState.togs())
            if (keyState == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG failed to create key state from stored value")
                return null
            }
            val pipMsg = keyState.msg
            var id = pipMsg.opaque().s
            if (id.isNullOrEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG key state ID is null or empty, fetching message")
                id = fetchPipMsg(context)?.opaque()?.s
            }
            if (id.isNullOrEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG key state ID is still null or empty after fetching message")
                return null
            }
            // Ensure the ID is at least 64 characters long
            if (id.length < 64) {
                Logger.w(LOG_TAG_PROXY, "$TAG invalid key state ID length: ${id.length}")
                return null
            }
            val accountId = id.substring(0, 64)
            Logger.d(
                LOG_TAG_PROXY,
                "$TAG loaded key state for accountId: $accountId"
            )
            KeyState(accountId)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err recreating key state: ${e.message}", e)
            null
        }
    }

    private fun fetchPipMsgFromStorage(context: Context): String? {
        return try {
            val path = getPipKeyFilePath(context, PIP_MESSAGE_FILE_NAME)
            if (!path.exists()) {
                Logger.w(LOG_TAG_PROXY, "$TAG message file does not exist")
                return null
            }
            EncryptedFileManager.read(context, path)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err loading message: ${e.message}", e)
            null
        }
    }

    private fun fetchPipMsg(context: Context): PipMsg? {
        val message = fetchPipMsgFromStorage(context)
        if (message == null || message.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG no message found in storage")
            return null
        }
        return Backend.asPipMsg(message.togs())
    }

    private fun fetchPipToken(context: Context): String? {
        return try {
            val path = getPipKeyFilePath(context, PIP_TOKEN_FILE_NAME)
            if (!path.exists()) {
                Logger.w(LOG_TAG_PROXY, "$TAG token file does not exist")
                return null
            }
            EncryptedFileManager.read(context, path)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG err loading token: ${e.message}", e)
            null
        }
    }

    /**
     * Determines if key rotation is needed based on last rotation time
     */
    private fun needsRotation(lastRotationTime: Long): Boolean {
        if (lastRotationTime == 0L) {
            return true // Never rotated before
        }

        val currentDate = Calendar.getInstance()
        val lastRotationDate = Calendar.getInstance().apply {
            timeInMillis = lastRotationTime
        }
        Logger.d(LOG_TAG_PROXY, "$TAG last rotation time: ${lastRotationDate.time}, current time: ${currentDate.time}")

        // Calculate difference in milliseconds
        val diffInMillis = currentDate.timeInMillis - lastRotationDate.timeInMillis
        // Convert months to milliseconds (approximate)
        val monthsInMillis = KEY_ROTATION_PERIOD_MONTHS * 30L * 24 * 60 * 60 * 1000L // 30 days per month
        return diffInMillis >= monthsInMillis
    }

    /**
     * Gets the timestamp of the last key rotation
     */
    private fun getLastKeyRotationTime(): Long {
        return persistentState.pipKeyRotationTime
    }

    /**
     * Saves the current time as the last key rotation time
     */
    private fun saveLastKeyRotationTime() {
        persistentState.pipKeyRotationTime = System.currentTimeMillis()
    }

    /**
     * Data class to hold key state information; for now only accountId is used add other fields as
     * needed
     */
    data class KeyState(
        val accountId: String
    )
}
