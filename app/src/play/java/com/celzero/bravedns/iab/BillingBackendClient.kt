/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.os.Build
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.IBillingServerApi
import com.celzero.bravedns.customdownloader.IBillingServerApiTest
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.customdownloader.SafeResponseConverterFactory
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant


/**
 * Pure data/service layer for all Rethink backend billing API calls.
 *
 * This class owns every HTTP interaction with the `/d/acc`, `/d/reg`,
 * `/g/ack`, `/g/stop`, `/g/refund`, and `/g/con` endpoints.
 *
 * ### Responsibilities
 * - Build and dispatch all [IBillingServerApi] requests.
 * - Read accountId / deviceId from [SecureIdentityStore] (encrypted file).
 * - Send CID as `x-rethink-app-cid` header and DID as `x-rethink-app-did` header on every request.
 * - Construct meta JSON objects for registration calls.
 */
class BillingBackendClient(
    private val identityStore: SecureIdentityStore
) : KoinComponent {

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "BillingBackendClient"

        /**
         * Back-off delays (ms) applied **between** consecutive acknowledgePurchase attempts.
         *
         * Attempt schedule:
         *   1st: immediate
         *   2nd: after 4000 ms
         *   3rd: after 11000 ms
         */
        private val ACK_RETRY_DELAYS = longArrayOf(4_000L, 11_000L)

        // Session headers for database routing (sent in requests).
        private const val DB_SESSION_HEADER = "x-rethink-db-rpn-session"
        private const val DB_SESSION_TEST_HEADER = "x-rethink-db-rpn-test-session"

        // Session headers returned by the server (note: server omits the "rethink-" prefix).
        private const val DB_SESSION_RESPONSE_HEADER = "x-db-rpn-session"
        private const val DB_SESSION_TEST_RESPONSE_HEADER = "x-db-rpn-test-session"

        // DID token echoed back on every request after the first server response.
        private const val DID_TOKEN_HEADER = "x-rethink-app-did-token"

        // Sentinel on read endpoints; replaced with the cached bookmark if one is available.
        private const val DB_SESSION_UNCONSTRAINED = "first-unconstrained"
    }

    /** Latest session bookmark received from the production server. */
    @Volatile private var dbSessionBookmark: String? = null

    /** Latest session bookmark received from the test server. */
    @Volatile private var dbSessionTestBookmark: String? = null

    /** Latest DID token received from the server; echoed on every subsequent request. */
    @Volatile private var didToken: String? = null

    /**
     * Returns the [SecureIdentityStore.Env] that corresponds to the current app mode.
     *
     * [SecureIdentityStore.Env.TEST] is returned when [PersistentState.appTestMode] is `true`
     * (matching the test billing URL used in [createOrRegisterCid] / [createOrRegisterDid]).
     * [SecureIdentityStore.Env.PROD] is returned otherwise.
     *
     * **All** identity store reads and writes in this class must use this helper so that
     * test identities are never accidentally served for production requests, and vice-versa.
     */
    private fun currentEnv(): SecureIdentityStore.Env =
        if (persistentState.appTestMode) SecureIdentityStore.Env.TEST
        else SecureIdentityStore.Env.PROD

    /**
     * Returns a server-driven **account** identifier (CID).
     *
     * Resolution order:
     * 1. [SecureIdentityStore] (AES-256-GCM encrypted file, env-scoped via [currentEnv])
     * 2. [refreshIdentity] - calls `POST /d/acc` and `POST /d/reg` to obtain and persist
     *    both CID and DID atomically; the CID is returned on success.
     *
     * Returns an empty string if all sources fail. Callers must treat a blank return
     * as "IDs not yet available" and abort or retry rather than proceeding with empty IDs.
     */
    suspend fun getAccountId(): String {
        val mname = this::getAccountId.name
        val env = currentEnv()
        val (storedAcc, _) = identityStore.get(env)
        if (!storedAcc.isNullOrBlank()) {
            Logger.v(LOG_IAB, "$TAG $mname [${env.label}]: loaded from SecureIdentityStore (len=${storedAcc.length})")
            return storedAcc
        }
        return when (val result = refreshIdentity()) {
            is RefreshIdentityResult.Success -> {
                Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: received from server (len=${result.cid.length})")
                result.cid
            }
            is RefreshIdentityResult.Unauthorized -> {
                Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 unauthorized; cid unavailable")
                ""
            }
            is RefreshIdentityResult.Conflict -> {
                Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 409 conflict; cid unavailable")
                ""
            }
            is RefreshIdentityResult.Failure -> {
                Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: cid unavailable (transient)")
                ""
            }
        }
    }

    /**
     * Returns a server-driven **device** identifier (DID).
     *
     * Resolution order:
     * 1. CID reconciliation: if [recvCid] is provided and differs from the stored CID,
     *    a fresh DID is obtained via [createOrRegisterDid] for the recvCid.
     * 2. [SecureIdentityStore]: returned directly when no reconciliation is needed.
     * 3. [refreshIdentity]: obtains and persists both CID and DID from the server.
     *
     * Returns an empty string if all sources fail. Callers must treat a blank return
     * as "IDs not yet available" and abort or retry rather than proceeding with empty IDs.
     */
    suspend fun getDeviceId(recvCid: String = ""): String {
        val mname = this::getDeviceId.name
        val env = currentEnv()
        val (storedCid, storedDid) = identityStore.get(env)

        if (recvCid.isNotEmpty() && storedCid != recvCid) {
            Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: recvCid differs from storedCid; re-registering device under new cid, recvCid=${recvCid.take(8)}, storedCid=${storedCid?.take(8) ?: "null"}, storedDid=${storedDid?.length ?: "null"}")
            val didResult = createOrRegisterDid(recvCid, "")
            if (didResult.isSuccess) {
                identityStore.save(env, recvCid, didResult.deviceId)
                Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: re-registered device (didLen=${didResult.deviceId.length})")
                return didResult.deviceId
            }
            // Log specific error code so callers using createOrRegisterDid() directly can surface it.
            when (didResult.errorCode) {
                401  -> Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 unauthorized on re-registration for recvCid=${recvCid.take(8)}; falling through to store")
                409  -> Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 409 conflict on re-registration for recvCid=${recvCid.take(8)}; falling through to store")
                else -> Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: re-registration failed (code=${didResult.errorCode}); falling through to store")
            }
        }

        if (!storedDid.isNullOrBlank()) {
            Logger.v(LOG_IAB, "$TAG $mname [${env.label}]: loaded from SecureIdentityStore (len=${storedDid.length})")
            return storedDid
        }

        // No usable DID in store; perform a full identity refresh so both CID and DID
        // are resolved and persisted atomically.
        return when (val result = refreshIdentity()) {
            is RefreshIdentityResult.Success -> {
                Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: received from server (len=${result.did.length})")
                result.did
            }
            is RefreshIdentityResult.Unauthorized -> {
                Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 unauthorized; did unavailable")
                ""
            }
            is RefreshIdentityResult.Conflict -> {
                Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 409 conflict; did unavailable")
                ""
            }
            is RefreshIdentityResult.Failure -> {
                Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: did unavailable (transient)")
                ""
            }
        }
    }

    /**
     * Returns the current (accountId, deviceId) pair.
     *
     * Resolution order:
     * 1. [SecureIdentityStore] (encrypted file)- primary source; returned directly when
     *    both IDs are present.
     * 2. [refreshIdentity] - obtains fresh CID via `POST /d/acc` and fresh DID via
     *    `POST /d/reg`; persists both atomically on success.
     *
     * Returns `Pair("", "")` when both sources fail. Callers must check for blank IDs
     * and abort rather than proceeding with an empty identity.
     */
    suspend fun resolveIdentity(): RefreshIdentityResult {
        val mname = this::resolveIdentity.name
        val env = currentEnv()
        // Encrypted file: both IDs present → no server call needed.
        val (storedAcc, storedDev) = identityStore.get(env)
        if (!storedAcc.isNullOrBlank() && !storedDev.isNullOrBlank()) {
            Logger.d(LOG_IAB, "$TAG $mname [${env.label}]: loaded from SecureIdentityStore (cidLen=${storedAcc.length}, didLen=${storedDev.length})")
            return RefreshIdentityResult.Success(storedAcc, storedDev)
        }
        // Fetch CID then DID from the server and persist both.
        val result = refreshIdentity()
        when (result) {
            is RefreshIdentityResult.Success -> Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: obtained from server and persisted (cidLen=${result.cid.length}, didLen=${result.did.length})")
            is RefreshIdentityResult.Unauthorized -> Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 unauthorized; identity unavailable")
            is RefreshIdentityResult.Conflict -> Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 409 conflict; identity unavailable")
            is RefreshIdentityResult.Failure -> Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: identity unavailable (transient)")
        }
        return result
    }

    /**
     * Fetches or registers a **CID** (account ID) from the server via `POST /d/acc`.
     *
     * The server creates a new account when [existingCid] is blank, or confirms /
     * corrects an existing one when it is non-blank. The returned ID is **not**
     * persisted by this method; the caller (or [refreshIdentity]) is responsible
     * for saving it together with the DID.
     *
     * ### Error codes in [CidResult.errorCode]
     * - 401 → server refused to authorize this account
     * - 409 → conflict; account already exists in an incompatible state
     * - other → HTTP error from server
     *
     * @param existingCid Previously known account ID, or blank for new registrations.
     */
    suspend fun createOrRegisterCid(existingCid: String = ""): CidResult = withContext(Dispatchers.IO) {
        val mname = "createOrRegisterCid"
        val env = currentEnv()
        try {
            val meta = buildCustomerMeta()
            // Pass null when blank so Retrofit omits the header entirely, letting the server
            // assign a fresh CID.  A non-blank value asks the server to confirm/correct it.
            val cidHeader = existingCid.takeIf { it.isNotBlank() }
            Logger.v(LOG_IAB, "$TAG $mname [${env.label}]: calling /d/acc, cidHeader=${if (cidHeader != null) "present(len=${cidHeader.length})" else "absent"}")
            val response = if (persistentState.appTestMode) {
                buildTestApi().registerCustomer(cidHeader, "", test = "", meta)
            } else {
                buildProductionApi().registerCustomer(cidHeader, "", meta)
            }
            return@withContext when {
                response == null -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: null response")
                    CidResult.EMPTY
                }
                response.isSuccessful -> {
                    val cid = response.body()?.get("cid")?.asString?.trim() ?: ""
                    if (cid.isBlank()) {
                        Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: server returned blank cid, body=${response.body()}, msg=${response.message()}")
                        CidResult.EMPTY
                    } else {
                        Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: received cid (len=${cid.length})")
                        CidResult(cid)
                    }
                }
                response.code() == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 unauthorized, err=${response.errorBody()?.string()}")
                    CidResult.error(401)
                }
                response.code() == 409 -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: 409 conflict, err=${response.errorBody()?.string()}")
                    CidResult.error(409)
                }
                else -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: failed code=${response.code()}, err=${response.errorBody()?.string()}")
                    CidResult.error(response.code())
                }
            }
        } catch (e: UnknownHostException) {
            Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: no internet; ${e.message}")
            CidResult.EMPTY
        } catch (e: ConnectException) {
            Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: connection failed; ${e.message}")
            CidResult.EMPTY
        } catch (e: SocketTimeoutException) {
            Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: timeout; ${e.message}")
            CidResult.EMPTY
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: unexpected error; ${e.message}", e)
            CidResult.EMPTY
        }
    }

    /**
     * Fetches or registers a **DID** (device ID) for the given [accountId] via `POST /d/reg`.
     *
     * The server creates a new device entry when [existingDeviceId] is blank, or confirms /
     * corrects an existing one when it is non-blank.  The returned ID is **not** persisted
     * by this method; the caller (or [refreshIdentity]) is responsible for saving it
     * together with the CID.
     *
     * [accountId] is required, a device cannot exist without an account.
     *
     * ### Error codes in [DidResult.errorCode]
     * - 401 → server refused to authorize this device
     * - 409 → conflict; device already registered in an incompatible state
     * - other → HTTP error from server
     *
     * @param accountId The account (CID) this device belongs to.  Must be non-blank.
     * @param existingDeviceId Previously known device ID, or blank for new device registrations.
     */
    suspend fun createOrRegisterDid(
        accountId: String,
        existingDeviceId: String = ""
    ): DidResult = withContext(Dispatchers.IO) {
        val mname = "createOrRegisterDid"
        val env = currentEnv()
        if (accountId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: blank accountId; cannot register device without account")
            return@withContext DidResult.error(-1)
        }
        try {
            val meta = buildCustomerMeta()
            // Pass null for did when blank so Retrofit omits the header, letting the server
            // assign a fresh DID.  accountId is always required and sent as a non-null header.
            val didHeader = existingDeviceId.takeIf { it.isNotBlank() }
            Logger.v(LOG_IAB, "$TAG $mname [${env.label}]: calling /d/reg, cidLen=${accountId.length}, didHeader=${if (didHeader != null) "present(len=${didHeader.length})" else "absent"}")
            val response = if (persistentState.appTestMode) {
                buildTestApi().registerDevice(accountId, didHeader, test = "", meta = meta)
            } else {
                buildProductionApi().registerDevice(accountId, didHeader, meta = meta)
            }
            return@withContext when {
                response == null -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: null response")
                    DidResult.EMPTY
                }
                response.isSuccessful -> {
                    val did = response.body()?.get("did")?.asString?.trim() ?: ""
                    if (did.isBlank()) {
                        Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: server returned blank did, body=${response.body()}, msg=${response.message()}")
                        DidResult.EMPTY
                    } else {
                        Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: received did (len=${did.length})")
                        DidResult(did)
                    }
                }
                response.code() == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 unauthorized, err=${response.errorBody()?.string()}")
                    DidResult.error(401)
                }
                response.code() == 409 -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: 409 conflict, err=${response.errorBody()?.string()}")
                    DidResult.error(409)
                }
                else -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: failed code=${response.code()}, err=${response.errorBody()?.string()}")
                    DidResult.error(response.code())
                }
            }
        } catch (e: UnknownHostException) {
            Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: no internet; ${e.message}")
            DidResult.EMPTY
        } catch (e: ConnectException) {
            Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: connection failed; ${e.message}")
            DidResult.EMPTY
        } catch (e: SocketTimeoutException) {
            Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: timeout; ${e.message}")
            DidResult.EMPTY
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: unexpected error; ${e.message}", e)
            DidResult.EMPTY
        }
    }

    /**
     * One-time migration for users upgrading from a build that stored the identity in the
     * legacy unscoped `identity.json` file.
     *
     * ### Why this is needed
     * Previous builds used a single `pip/identity.json` file for both test and production
     * identities.  The new env-scoped design uses `identity_prod.json` / `identity_test.json`.
     * Without migration those users would lose their CID/DID and trigger a new server
     * registration on every restart, unnecessarily creating duplicate accounts.
     *
     * ### Env determination strategy
     * 1. **VPN tunnel active**: call [VpnController.getEntitlementDetails] with the legacy
     *    DID. The returned `RpnEntitlement.test()` flag is the authoritative source —
     *    it says which server issued the purchase.
     * 2. **Tunnel unavailable** (null entitlement or exception): fall back to
     *    [PersistentState.appTestMode], which historically controlled which server
     *    ([com.celzero.bravedns.util.Constants.RPN_BASE_URL] vs fallback) was called when
     *    the CID/DID were originally obtained — so it is the best static proxy.
     *
     * This function is **idempotent**: once the legacy file is gone,
     * [SecureIdentityStore.hasLegacyIdentity] returns false immediately and subsequent
     * calls are no-ops.
     */
    private suspend fun migrateIdentityIfNeeded() = withContext(Dispatchers.IO) {
        if (!identityStore.hasLegacyIdentity()) return@withContext

        val (legacyCid, legacyDid) = identityStore.readLegacy()
        if (legacyCid.isNullOrBlank() || legacyDid.isNullOrBlank()) {
            // Nothing usable in the legacy file — let migrateFromLegacy clean it up.
            Logger.w(LOG_IAB, "$TAG migrateIdentityIfNeeded: legacy identity blank/corrupt; deleting without migration")
            identityStore.migrateFromLegacy(currentEnv())
            return@withContext
        }

        // Determine the correct env from the live entitlement (authoritative) or appTestMode (fallback).
        val env = try {
            val entitlement = VpnController.getEntitlementDetails(null, legacyDid)
            if (entitlement != null) {
                val isTest = entitlement.test()
                Logger.i(LOG_IAB, "$TAG migrateIdentityIfNeeded: entitlement resolved, isTest=$isTest " +
                    "(cidLen=${legacyCid.length}, didLen=${legacyDid.length})")
                if (isTest) SecureIdentityStore.Env.TEST else SecureIdentityStore.Env.PROD
            } else {
                val isTest = persistentState.appTestMode
                Logger.i(LOG_IAB, "$TAG migrateIdentityIfNeeded: tunnel unavailable, using appTestMode=$isTest as env " +
                    "(cidLen=${legacyCid.length}, didLen=${legacyDid.length})")
                if (isTest) SecureIdentityStore.Env.TEST else SecureIdentityStore.Env.PROD
            }
        } catch (e: Exception) {
            val isTest = persistentState.appTestMode
            Logger.w(LOG_IAB, "$TAG migrateIdentityIfNeeded: entitlement lookup failed (${e.message}); falling back to appTestMode=$isTest")
            if (isTest) SecureIdentityStore.Env.TEST else SecureIdentityStore.Env.PROD
        }

        identityStore.migrateFromLegacy(env)
    }

    /**
     * Ensures both CID and DID are present and up-to-date (heartbeat / identity sync).
     *
     * This is the **single entry point** for any code that needs a fully-resolved identity
     * pair. Call it when the store is known to be empty or stale, or periodically to
     * confirm the server still recognizes both IDs.
     *
     * ### Resolution logic
     * **CID**
     * 1. Read from [SecureIdentityStore]: reused if non-blank.
     * 2. Call [createOrRegisterCid] (`POST /d/acc`): assigned by the server.
     *
     * **DID**
     * 1. Read from [SecureIdentityStore], reused *only* when the stored CID matches
     *    the resolved CID (avoids a stale DID being used under a new CID).
     * 2. Call [createOrRegisterDid] (`POST /d/reg`), assigned by the server.
     *
     * **Persistence** both IDs are written atomically to [SecureIdentityStore] via
     * [SecureIdentityStore.save] only when at least one of them changed or was missing.
     *
     * @return [RefreshIdentityResult.Success] with both IDs on success.
     *   [RefreshIdentityResult.Unauthorized] when the server returns HTTP 401 on either step.
     *   [RefreshIdentityResult.Conflict] when the server returns HTTP 409 on either step.
     *   [RefreshIdentityResult.Failure] for all other failures (transient, network, etc.).
     *   Callers must treat non-[RefreshIdentityResult.Success] as "identity not ready".
     */
    suspend fun refreshIdentity(): RefreshIdentityResult {
        val mname = "refreshIdentity"
        // One-time migration: move legacy identity.json into the correct env-scoped file
        // before any read, so the migrated data is found by identityStore.get(env) below.
        migrateIdentityIfNeeded()
        val env = currentEnv()
        val (storedCid, storedDid) = identityStore.get(env)

        var cid = storedCid ?: ""
        var cidWasAbsent = false
        if (cid.isBlank()) {
            val cidResult = createOrRegisterCid()
            when {
                cidResult.isSuccess -> {
                    cid = cidResult.accountId
                    cidWasAbsent = true
                    Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: new cid obtained from server (len=${cid.length})")
                }
                cidResult.errorCode == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 on cid step; cannot resolve identity")
                    return RefreshIdentityResult.Unauthorized
                }
                cidResult.errorCode == 409 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 409 on cid step; cannot resolve identity")
                    return RefreshIdentityResult.Conflict
                }
                else -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: failed to resolve cid (code=${cidResult.errorCode})")
                    return RefreshIdentityResult.Failure
                }
            }
        } else {
            Logger.d(LOG_IAB, "$TAG $mname [${env.label}]: cid loaded from store (len=${cid.length})")
        }

        // Reuse the stored DID only when the CID hasn't changed; a new CID means
        // the old DID belong to a different account and must not be re-used.
        var did = if (!cidWasAbsent && !storedDid.isNullOrBlank()) storedDid else ""
        if (did.isBlank()) {
            val didResult = createOrRegisterDid(cid, storedDid ?: "")
            when {
                didResult.isSuccess -> {
                    did = didResult.deviceId
                    Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: new did obtained from server (len=${did.length})")
                }
                didResult.errorCode == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 on did step; cannot resolve identity")
                    return RefreshIdentityResult.Unauthorized
                }
                didResult.errorCode == 409 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 409 on did step; cannot resolve identity")
                    return RefreshIdentityResult.Conflict
                }
                else -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: failed to resolve did (code=${didResult.errorCode})")
                    return RefreshIdentityResult.Failure
                }
            }
        } else {
            Logger.d(LOG_IAB, "$TAG $mname [${env.label}]: did loaded from store (len=${did.length})")
        }

        if (storedCid != cid || storedDid != did) {
            identityStore.save(env, cid, did)
            Logger.i(LOG_IAB, "$TAG $mname: identity persisted [${env.label}] (cidLen=${cid.length}, didLen=${did.length})")
        }

        return RefreshIdentityResult.Success(cid, did)
    }

    /**
     * Calls `POST /g/device` to register [accountId] + [deviceId] with the server.
     *
     * @param m Optional JSON body (appVersion, productId, planId, orderId).
     * @return [RegisterDeviceResult.Success] on HTTP 2xx.
     *   [RegisterDeviceResult.Unauthorized] on HTTP 401 (device not authorized).
     *   [RegisterDeviceResult.Conflict] on HTTP 409 (device already registered).
     *   [RegisterDeviceResult.Failure] on any other non-2xx or exception.
     */
    suspend fun registerDevice(
        accountId: String,
        deviceId: String,
        m: JsonObject? = null
    ): RegisterDeviceResult = withContext(Dispatchers.IO) {
        val mname = "registerDevice"
        if (accountId.isBlank() || deviceId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname: blank accountId or deviceId skipping")
            return@withContext RegisterDeviceResult.Failure(-1, "blank accountId or deviceId")
        }
        val meta = m ?: buildCustomerMeta()
        try {
            val handle = resolveApi()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.registerDevice(accountId, deviceId, meta = meta)
                is ApiHandle.Test -> handle.api.registerDevice(accountId, deviceId, test = "", meta = meta)
            }
            when {
                response == null -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: null response")
                    RegisterDeviceResult.Failure(0, "null response")
                }
                response.isSuccessful -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: success " +
                        "(accLen=${accountId.length}, devLen=${deviceId.length}, res=${response.message()}, body=${response.body()})")
                    persistentState.deviceRegistrationTimestamp = System.currentTimeMillis()
                    RegisterDeviceResult.Success
                }
                response.code() == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 401 unauthorized; accLen=${accountId.length}, devLen=${deviceId.length}, err=${response.errorBody()?.string()}")
                    RegisterDeviceResult.Unauthorized(accountId, deviceId)
                }
                response.code() == 409 -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 409 (device already registered), err=${response.errorBody()?.string()}")
                    RegisterDeviceResult.Conflict
                }
                else -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: failed code=${response.code()}, err=${response.errorBody()?.string()}")
                    RegisterDeviceResult.Failure(response.code(), response.message())
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: exception; ${e.message}", e)
            RegisterDeviceResult.Failure(-1, e.message)
        }
    }

    /**
     * Convenience overload that uses the device registration meta format
     * (deviceName + appVersion) mirrors the format previously in
     * [InAppBillingHandler] before server-call extraction.
     */
    suspend fun registerDeviceWithDeviceMeta(
        accountId: String,
        deviceId:  String
    ): RegisterDeviceResult = registerDevice(accountId, deviceId, buildDeviceMeta())

    /**
     * Calls `POST /g/ack` to acknowledge a purchase server-side.
     *
     * Retries on transient failures (network errors, 5xx responses) using a fixed
     * back-off schedule before giving up:
     *
     * Hard failures: 409 Conflict, 4xx client errors, or a successfully parsed
     * server response (Ok or Err): are returned immediately without retrying.
     *
     * @return Pair(success, developerPayload or error message).
     */
    suspend fun acknowledgePurchase(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productType: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mname = "acknowledgePurchase"
        Logger.d(LOG_IAB, "$TAG $mname [${currentEnv().label}]: accLen=${accountId.length}, type=$productType")

        val pt  = URLEncoder.encode(purchaseToken, "UTF-8")
        val sku = skuForType(productType)

        executeAckWithRetry(mname) { handle ->
            when (handle) {
                is ApiHandle.Production -> handle.api.acknowledgePurchase(accountId, deviceId, sku, pt)
                is ApiHandle.Test -> handle.api.acknowledgePurchase(accountId, deviceId, sku, pt, test = "")
            }
        }
    }

    /**
     * Core retry engine for [acknowledgePurchase].
     *
     * Executes [attempt] up to 3 times with delays of 4000 ms and 11000 ms between
     * consecutive tries. This is to mitigate the max request reached / timeout issues.
     *
     * ### Retry policy
     * - **Retry**: [UnknownHostException], [ConnectException], [SocketTimeoutException],
     *   any other [Exception] (defensive catch-all), or an HTTP 5xx response.
     * - **No retry** (return immediately):
     *   - HTTP 409: conflict; must be handled by the caller.
     *   - HTTP 4xx: permanent client error; retrying will not help.
     *   - [RpnPurchaseAckServerResponse.Ok]: success.
     *   - [RpnPurchaseAckServerResponse.Err]: server-side business error (e.g. invalid token);
     *     retrying will not change the outcome.
     *
     * @param caller   Name of the calling method, used only in log messages.
     * @param attempt  Lambda that performs one HTTP call given a resolved [ApiHandle].
     */
    private suspend fun executeAckWithRetry(
        caller: String,
        attempt: suspend (ApiHandle) -> Response<JsonObject?>?
    ): Pair<Boolean, String> {
        // Resolve the test/production split once, stable across all retries.
        val handle = resolveApi()

        // Back-off delays in ms.  The first attempt is immediate (no leading delay).
        // After attempt N fails transiently, wait ACK_RETRY_DELAYS[N] before attempt N+1.
        // When all delays are exhausted the loop exits and we return the last failure.
        val delaysMs = ACK_RETRY_DELAYS
        val maxAttempts = delaysMs.size + 1   // 3: one immediate + one per delay

        var lastFailureMsg = "No response from server"

        for (attemptIndex in 0 until maxAttempts) {
            // Wait before every attempt except the first.
            if (attemptIndex > 0) {
                val waitMs = delaysMs[attemptIndex - 1]
                Logger.i(LOG_IAB, "$TAG $caller [${handle.envLabel}]: retry #$attemptIndex, waiting ${waitMs}ms")
                delay(waitMs)
            }

            try {
                val response = attempt(handle)

                if (response == null) {
                    lastFailureMsg = "No response from server"
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1} null response, will retry")
                    continue   // transient retry
                }

                val httpCode = response.code()
                val url = response.raw().request.url.toString()
                Logger.d(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1} for url: $url, code: $httpCode, err: ${response.errorBody()?.string()}")

                if (httpCode == 401) {
                    Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: 401 unauthorized, not retrying; ${response.errorBody()?.string()}")
                    return Pair(false, "Unauthorized: 401")
                }

                if (httpCode == 409) {
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: 409 conflict, not retrying; ${response.errorBody()?.string()}")
                    return Pair(false, "Conflict: 409")
                }

                if (httpCode == 429) {
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: 429 too many req, retrying with timeout ${response.errorBody()?.string()}")
                    continue
                }

                if (httpCode in 400..499) {
                    Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: permanent client error $httpCode not retrying; ${response.errorBody()?.string()}")
                    return Pair(false, "Client error: $httpCode")
                }

                if (httpCode >= 500) {
                    lastFailureMsg = "Server error: $httpCode"
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1} server error $httpCode, will retry; ${response.errorBody()?.string()}")
                    continue
                }

                val bodyStr = response.body()?.toString()
                val rawForParsing = bodyStr?.takeIf { it.isNotBlank() }
                    ?: response.errorBody()?.string()
                if (DEBUG) Logger.d(LOG_IAB, "$TAG $caller [${handle.envLabel}]: body=$rawForParsing")

                return when (val result = RpnPurchaseAckServerResponse.from(rawForParsing, httpCode)) {
                    is RpnPurchaseAckServerResponse.Ok -> {
                        Logger.d(LOG_IAB, "$TAG $caller [${handle.envLabel}]: ack ok, hasEntitlement=${result.payload.hasEntitlement}")
                        Pair(true, result.payload.developerPayload.orEmpty())
                    }
                    is RpnPurchaseAckServerResponse.Err -> {
                        // Business-level error (e.g. invalid token): do not retry.
                        Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: server business error: ${result.payload}")
                        Pair(false, "Server error: ${result.payload.error}")
                    }
                }
            } catch (e: UnknownHostException) {
                lastFailureMsg = "No internet: ${e.message}"
                Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1}; no internet (${e.message}), will retry")
            } catch (e: ConnectException) {
                lastFailureMsg = "Connection failed: ${e.message}"
                Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1}; connect error (${e.message}), will retry")
            } catch (e: SocketTimeoutException) {
                lastFailureMsg = "Timeout: ${e.message}"
                Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1}; timeout (${e.message}), will retry")
            } catch (e: Exception) {
                lastFailureMsg = "Error: ${e.message}"
                Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1}; unexpected (${e.message}), will retry", e)
            }
        }

        Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: all $maxAttempts attempts exhausted; $lastFailureMsg")
        return Pair(false, lastFailureMsg)
    }

    /**
     * Calls `POST /g/ack` to query entitlement for an existing purchase.
     *
     * Returns a [QueryEntitlementResult]:
     * - [QueryEntitlementResult.Success] with the [PurchaseDetail] updated from the server.
     * - [QueryEntitlementResult.Unauthorized] on HTTP 401 (caller should surface the error UI).
     * - [QueryEntitlementResult.Conflict] on HTTP 409 (caller should surface the conflict UI).
     * - [QueryEntitlementResult.Failure] when the server responded with a business-level error
     *   (e.g. [RpnPurchaseAckServerResponse.Err]: purchase revoked/refunded per server records).
     *   [purchase] is the *original* [PurchaseDetail] unchanged; the local billing expiry is
     *   still the authoritative gate for INAPP purchases.
     * - [QueryEntitlementResult.Transient] on network error, timeout, or null response.
     *   The server was not reached; [purchase] is unchanged. Callers must fail-safe and
     *   preserve the token — do NOT expire a locally-valid purchase on a transient failure.
     */
    suspend fun queryEntitlement(
        accountId: String,
        deviceId: String,
        purchase:  PurchaseDetail,
        purchaseToken: String
    ): QueryEntitlementResult = withContext(Dispatchers.IO) {
        val mname = "queryEntitlement"
        if (accountId.isEmpty()) {
            Logger.e(LOG_IAB, "$TAG $mname: empty accountId skipping")
            // Treat missing accountId as a transient/infrastructure problem, not a business error.
            return@withContext QueryEntitlementResult.Transient(purchase)
        }
        try {
            val encodedPt = URLEncoder.encode(purchaseToken, "UTF-8")
            val sku = skuForType(purchase.productType)
            val handle = resolveApi()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.queryEntitlement(accountId, deviceId, sku, encodedPt)
                is ApiHandle.Test -> handle.api.queryEntitlement(accountId, deviceId, sku, encodedPt, test = "")
            }
            if (response == null) {
                // Null response = server unreachable / transport error → transient.
                Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: null response from server for entitlement query")
                return@withContext QueryEntitlementResult.Transient(purchase)
            }
            val httpCode = response.code()
            // Read the body exactly once — OkHttp's ResponseBody is a one-shot stream.
            // Any subsequent call to errorBody().string() on the same response returns null/empty.
            val bodyStr = response.body()?.toString() ?: response.errorBody()?.string()
            Logger.d(LOG_IAB, "$TAG $mname [${handle.envLabel}]: code=$httpCode, body? $bodyStr")
            if (httpCode == 401) {
                Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 401 unauthorized; accLen=${accountId.length}, devLen=${deviceId.length}")
                return@withContext QueryEntitlementResult.Unauthorized(accountId, deviceId)
            }
            if (httpCode == 409) {
                Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 409 conflict")
                return@withContext QueryEntitlementResult.Conflict
            }
            when (val result = RpnPurchaseAckServerResponse.from(bodyStr, httpCode)) {
                is RpnPurchaseAckServerResponse.Ok  -> {
                    Logger.d(LOG_IAB, "$TAG $mname [${handle.envLabel}]: ok, hasEntitlement=${result.payload.hasEntitlement}")
                    QueryEntitlementResult.Success(
                        purchase.copy(
                            payload = result.payload.developerPayload.orEmpty(),
                            expiryTime = parseIsoToEpochMillis(result.payload.expiry),
                            windowDays = result.payload.windowDays ?: 0
                        )
                    )
                }
                is RpnPurchaseAckServerResponse.Err -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: server business error, ${result.payload}")
                    if (result.payload.isSubscriptionExpired) {
                        // Server definitively confirmed subscription is expired —
                        // callers must NOT preserve the old purchase or entitlement.
                        Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: subscription definitively expired on server " +
                            "(state=${result.payload.state}); returning Expired to caller")
                        QueryEntitlementResult.Expired(purchase)
                    } else {
                        // Other business errors (revoked, linked purchase, etc.) — preserve the local
                        // purchase as a fail-safe; the local billing expiry remains authoritative.
                        // Surface linkedPurchaseId so the caller can attempt to reactivate the
                        // superseded purchase.
                        QueryEntitlementResult.Failure(
                            purchase = purchase,
                            linkedPurchaseId = result.payload.linkedPurchaseId,
                        )
                    }
                }
            }
        } catch (e: UnknownHostException) {
            Logger.w(LOG_IAB, "$TAG $mname: no internet (${e.message}); transient failure")
            QueryEntitlementResult.Transient(purchase)
        } catch (e: ConnectException) {
            Logger.w(LOG_IAB, "$TAG $mname: connection failed (${e.message}); transient failure")
            QueryEntitlementResult.Transient(purchase)
        } catch (e: SocketTimeoutException) {
            Logger.w(LOG_IAB, "$TAG $mname: timeout (${e.message}); transient failure")
            QueryEntitlementResult.Transient(purchase)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: unexpected error (${e.message}); transient failure", e)
            QueryEntitlementResult.Transient(purchase)
        }
    }

    /**
     * Calls `POST /g/stop` to cancel a subscription or one-time purchase.
     *
     * @return Pair(success, message). On 409 returns (false, "Conflict: 409").
     */
    suspend fun cancelPurchase(
        accountId: String,
        deviceId: String,
        sku: String,
        purchaseToken: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mname = "cancelPurchase"
        try {
            val handle = resolveApi()
            Logger.d(LOG_IAB, "$TAG $mname [${handle.envLabel}]: accLen=${accountId.length}, devLen=${deviceId.length}, sku=$sku")
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.cancelPurchase(accountId, deviceId, sku, purchaseToken)
                is ApiHandle.Test -> handle.api.cancelSubscription(accountId, deviceId, sku, purchaseToken, test = "")
            } ?: return@withContext Pair(false, "No response")
            when {
                response.code() == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 401 unauthorized; accLen=${accountId.length}, err=${response.errorBody()?.string()}")
                    Pair(false, "Unauthorized: 401")
                }
                response.code() == 409 -> Pair(false, "Conflict: 409")
                response.isSuccessful  -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: cancelled sku=$sku")
                    Pair(true, "Cancelled")
                }
                else -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: error code=${response.code()}, msg=${response.message()}, err=${response.errorBody()?.string()}")
                    Pair(false, "Server error: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: err ${e.message}", e)
            Pair(false, "Exception: ${e.message}")
        }
    }

    /**
     * Calls `POST /g/refund` to revoke/refund a purchase.
     *
     * @return Pair(success, message). On 409 returns (false, "Conflict: 409").
     */
    suspend fun revokePurchase(
        accountId: String,
        deviceId: String,
        sku: String,
        purchaseToken: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mname = "revokePurchase"
        try {
            val handle = resolveApi()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.revokeSubscription(accountId, deviceId, sku, purchaseToken)
                is ApiHandle.Test -> handle.api.revokeSubscription(accountId, deviceId, sku, purchaseToken, test = "")
            } ?: return@withContext Pair(false, "No response")
            when {
                response.code() == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 401 unauthorized; accLen=${accountId.length}, err=${response.errorBody()?.string()}")
                    Pair(false, "Unauthorized: 401")
                }
                response.code() == 409 -> Pair(false, "Conflict: 409")
                response.isSuccessful  -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: revoked sku=$sku, raw: ${response.raw().body}")
                    Pair(true, "Revoked")
                }
                else -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: err code=${response.code()}, " +
                        "url=${response.raw().request.url}")
                    Pair(false, "server err: ${response.code()}, ${response.errorBody()?.string()}")
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: err ${e.message}", e)
            Pair(false, "err: ${e.message}")
        }
    }

    /**
     * Fetches raw purchase/order history from `GET /g/tx` for the given credentials.
     *
     * The appropriate test/production API is selected via [resolveApi].
     * For the test path [IBillingServerApiTest.getPurchaseHistory] is used (no `deviceId`
     * header; `accountId` is sent as a query param instead).
     * For the production path [IBillingServerApi.getPurchaseHistory] is used (headers).
     *
     * HTTP handling and exception wrapping are done here so that callers focus solely
     * on parsing [FetchOrdersRawResult.Success.body].
     *
     * @return [FetchOrdersRawResult.Success] with the raw body on HTTP 2xx.
     *         [FetchOrdersRawResult.Error] on HTTP error or any exception.
     *         [FetchOrdersRawResult.NoCredentials] when [accountId] or [purchaseToken] is blank.
     */
    suspend fun fetchPurchaseHistory(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        total: Int,
    ): FetchOrdersRawResult = withContext(Dispatchers.IO) {
        val mname = "fetchPurchaseHistory"
        if (accountId.isBlank() || purchaseToken.isBlank()) {
            Logger.w(LOG_IAB, "$TAG $mname: accountId or purchaseToken blank")
            return@withContext FetchOrdersRawResult.NoCredentials
        }
        try {
            val handle = resolveApi()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.getPurchaseHistory(
                    accountId = accountId,
                    deviceId = deviceId,
                    purchaseToken = purchaseToken,
                    total = total,
                )
                is ApiHandle.Test -> handle.api.getPurchaseHistory(
                    accountId = accountId,
                    deviceId = deviceId,
                    purchaseToken = purchaseToken,
                    test = "",
                    total = total,
                )
            }
            when {
                response == null -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: null response")
                    FetchOrdersRawResult.Error("Empty response from server")
                }
                !response.isSuccessful -> {
                    val code = response.code()
                    Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: HTTP $code, ${response.errorBody()?.string()}")
                    FetchOrdersRawResult.Error("Server returned HTTP $code")
                }
                else -> {
                    val body = response.body()
                    if (body == null) {
                        Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: null body")
                        FetchOrdersRawResult.Error("No data returned from server")
                    } else {
                        if (DEBUG) Logger.vv(LOG_IAB, "$TAG $mname [${handle.envLabel}]: body=$body")
                        FetchOrdersRawResult.Success(body)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: exception: ${e.message}", e)
            FetchOrdersRawResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Calls `POST /g/consume` to consume an expired one-time purchase server-side.
     *
     * Idempotent: "already consumed" 409 responses are treated as success.
     *
     * @return true on 2xx or idempotent 409; false on any other failure.
     */
    suspend fun consumePurchase(
        accountId: String,
        deviceId: String,
        sku: String,
        purchaseToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        val mname = "consumePurchase"
        if (accountId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname: blank accountId skipping")
            return@withContext false
        }
        try {
            val encodedToken = URLEncoder.encode(purchaseToken, "UTF-8")
            val handle = resolveApi()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.consumePurchase(accountId, deviceId, sku, encodedToken)
                is ApiHandle.Test       -> handle.api.consumePurchase(accountId, deviceId, sku, encodedToken, test = "")
            }
            if (response == null) {
                Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: null response")
                return@withContext false
            }
            val bodyStr  = response.body()?.toString() ?: response.errorBody()?.string()
            val httpCode = response.code()
            when {
                response.isSuccessful -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: consumed sku=$sku body=$bodyStr")
                    true
                }
                httpCode == 409 && bodyStr?.contains("already consumed", ignoreCase = true) == true -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: idempotent already consumed sku=$sku")
                    true
                }
                httpCode == 409 -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 409 conflict sku=$sku body=$bodyStr")
                    false
                }
                httpCode in 400..499 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: permanent client error $httpCode sku=$sku body=$bodyStr")
                    false
                }
                else -> {
                    Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: transient server error $httpCode sku=$sku")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: err ${e.message}", e)
            false
        }
    }

    /**
     * Builds the device-registration meta JSON (deviceName + appVersion).
     */
    fun buildDeviceMeta(prodId: String = ""): JsonObject {
        val meta = JsonObject()
        meta.addProperty("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
        meta.addProperty("appVersion", persistentState.appVersion)
        meta.addProperty("purchasedProdId", prodId)
        return meta
    }

    /**
     * Builds the meta JSON body for `POST /d/acc` and `POST /d/reg`.
     *
     * CID and DID are sent as HTTP headers (`x-rethink-app-cid` / `x-rethink-app-did`)
     * by [createOrRegisterCid] and [createOrRegisterDid]; this body carries supplementary
     * device correlation data only:
     *
     * ```json
     * {
     *   "model":      "Manufacturer Model",
     *   "appVersion": <int>
     * }
     * ```
     */
    fun buildCustomerMeta(): JsonObject {
        return JsonObject().apply {
            addProperty("model", Build.MANUFACTURER + " " + Build.MODEL)
            addProperty("appVersion", persistentState.appVersion)
        }
    }

    /**
     * Discriminated union returned by [resolveApi].
     *
     * [Production] carries the standard [IBillingServerApi] instance; the `test`
     * query param must be passed as `null` for every call to ensure it is omitted
     * from the URL entirely.
     *
     * [Test] carries the [IBillingServerApiTest] instance plus the non-null test
     * string that must be forwarded as the `test` query param.
     */
    private sealed class ApiHandle {
        /** Human-readable label for log messages: "prod" or "test". */
        abstract val envLabel: String
        /** Use for production entitlements. Pass `test = null` to every endpoint. */
        data class Production(val api: IBillingServerApi) : ApiHandle() {
            override val envLabel = "prod"
        }
        /** Use for test entitlements. Pass `test = testValue` to every endpoint. */
        data class Test(val api: IBillingServerApiTest, val testValue: Boolean) : ApiHandle() {
            override val envLabel = "test"
        }
    }

    /**
     * Resolves which Retrofit interface to use for the current request.
     *
     * Calls [RpnProxyManager.getIsTestEntitlement] **once** and returns:
     * - [ApiHandle.Test]       when the entitlement is a test one (non-null value).
     * - [ApiHandle.Production] when the entitlement is production (null value) or when
     *   the lookup throws (fail-safe: always prefer production on error).
     *
     * The Retrofit instances are created fresh per-call, Retrofit interface creation
     * is lightweight (no network I/O) and this avoids threading concerns around caching
     * a potentially stale test/production split.
     */
    private suspend fun resolveApi(): ApiHandle {
        val testValue = try {
            RpnProxyManager.getIsTestEntitlement()
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG resolveApi: getIsTestEntitlement, ${e.message}")
            // happens when there is server calls before entitlement
            val test = persistentState.appTestMode
            Logger.w(LOG_IAB, "$TAG resolveApi: using TEST interface (testValue=$test)")
            test
        }
        return if (testValue) {
            Logger.d(LOG_IAB, "$TAG resolveApi: using TEST interface")
            ApiHandle.Test(buildTestApi(), true)
        } else {
            Logger.d(LOG_IAB, "$TAG resolveApi: using PROD interface")
            ApiHandle.Production(buildProductionApi())
        }
    }

    /** Builds a Retrofit instance for the production billing API. */
    private fun buildProductionApi(): IBillingServerApi {
        val client = RetrofitManager
            .okHttpClient(persistentState.routeRethinkInRethink)
            .newBuilder()
            // Outermost: on 5xx or network IOException, transparently retries against
            // RPN_FALLBACK_URL before the response surfaces to Retrofit. Must be added
            // before the session interceptor so that the session interceptor still runs
            // (and correctly attaches headers) for the fallback request as well.
            .addInterceptor(buildFallbackInterceptor())
            .addInterceptor(buildSessionInterceptor(
                DB_SESSION_HEADER, DB_SESSION_RESPONSE_HEADER,
                { dbSessionBookmark }, { dbSessionBookmark = it }, "prod"
            ))
            .build()
        return Retrofit.Builder()
            .baseUrl(Constants.RPN_BASE_URL)
            .client(client)
            .addConverterFactory(SafeResponseConverterFactory())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IBillingServerApi::class.java)
    }

    /**
     * Builds a Retrofit instance for the test billing API.
     *
     * Test calls always target [Constants.RPN_FALLBACK_URL] directly — no primary/fallback
     * split is needed for the test path.
     */
    private fun buildTestApi(): IBillingServerApiTest {
        val client = RetrofitManager
            .okHttpClient(persistentState.routeRethinkInRethink)
            .newBuilder()
            .addInterceptor(buildSessionInterceptor(
                DB_SESSION_TEST_HEADER, DB_SESSION_TEST_RESPONSE_HEADER,
                { dbSessionTestBookmark }, { dbSessionTestBookmark = it }, "test"
            ))
            .build()
        return Retrofit.Builder()
            .baseUrl(Constants.RPN_FALLBACK_URL)
            .client(client)
            .addConverterFactory(SafeResponseConverterFactory())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IBillingServerApiTest::class.java)
    }

    /**
     * OkHttp Application Interceptor that transparently retries a failed production request
     * against [Constants.RPN_FALLBACK_URL] when [Constants.RPN_BASE_URL] is unreachable or
     * returns a server error.
     *
     * ### Fallback triggers
     * - **5xx** HTTP responses (server-side errors): the primary response is closed and the
     *   request is re-issued against the fallback URL.
     * - **[IOException]** (covers [java.net.UnknownHostException], [java.net.ConnectException],
     *   [java.net.SocketTimeoutException], and all other network-level failures): the request
     *   is re-issued against the fallback URL.
     *
     * ### Non-fallback cases
     * - **4xx** client errors are returned as-is; the request cannot succeed on a different
     *   server (the error is in the request itself, not the server).
     * - **2xx / 3xx** responses are returned as-is.
     *
     * ### Interceptor ordering
     * This interceptor is added as the **outermost** application interceptor (before the
     * session interceptor). When the fallback request is issued via `chain.proceed()`, it
     * passes through the session interceptor again, so session headers (bookmarks, DID token)
     * are attached correctly to the fallback request too.
     *
     * Only used for [IBillingServerApi] (production). [IBillingServerApiTest] already
     * targets [Constants.RPN_FALLBACK_URL] directly and does not need this interceptor.
     */
    private fun buildFallbackInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            val response = chain.proceed(request)
            if (response.code in 500..599) {
                Logger.w(LOG_IAB, "$TAG fallback: server error ${response.code} on ${request.url}, retrying with fallback URL")
                response.close()
                val fallbackRequest = request.newBuilder()
                    .url(request.url.toString().replace(Constants.RPN_BASE_URL, Constants.RPN_FALLBACK_URL))
                    .build()
                chain.proceed(fallbackRequest)
            } else {
                response
            }
        } catch (e: IOException) {
            Logger.w(LOG_IAB, "$TAG fallback: network error (${e.message}) on ${request.url}, retrying with fallback URL")
            val fallbackRequest = request.newBuilder()
                .url(request.url.toString().replace(Constants.RPN_BASE_URL, Constants.RPN_FALLBACK_URL))
                .build()
            chain.proceed(fallbackRequest)
        }
    }

    /**
     * OkHttp interceptor that manages session bookmarks and the DID token header.
     *
     * Requests without the session header are passed through unmodified.
     * On read endpoints (session header == [DB_SESSION_UNCONSTRAINED]), the cached
     * bookmark is substituted if one is available. The DID token, if present, is
     * added to every session-bearing request. Bookmark and DID token are updated
     * from each server response.
     */
    private fun buildSessionInterceptor(
        reqHeader: String,
        respHeader: String,
        getBookmark: () -> String?,
        setBookmark: (String) -> Unit,
        tag: String
    ): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val sessionHdr = original.header(reqHeader)

        if (sessionHdr == null) {
            return@Interceptor chain.proceed(original)
        }

        val reqBuilder = original.newBuilder()
        if (sessionHdr == DB_SESSION_UNCONSTRAINED) {
            getBookmark()?.let { reqBuilder.header(reqHeader, it) }
        }
        didToken?.let { reqBuilder.header(DID_TOKEN_HEADER, it) }

        val response = chain.proceed(reqBuilder.build())

        response.header(respHeader)?.takeIf { it.isNotBlank() }?.let {
            setBookmark(it)
            if (DEBUG) Logger.d(LOG_IAB, "$TAG interceptor($tag): bookmark updated (len=${it.length})")
        }
        response.header(DID_TOKEN_HEADER)?.takeIf { it.isNotBlank() }?.let {
            didToken = it
            if (DEBUG) Logger.d(LOG_IAB, "$TAG interceptor($tag): didToken updated (len=${it.length})")
        }
        response
    }

    private fun skuForType(productType: String): String =
        if (productType == ProductType.SUBS) InAppBillingHandler.STD_PRODUCT_ID
        else InAppBillingHandler.ONE_TIME_PRODUCT_ID

    private fun parseIsoToEpochMillis(timeIso: String?): Long {
        if (timeIso.isNullOrEmpty()) return 0L
        return try {
            Instant.parse(timeIso).toEpochMilli()
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG parseIsoToEpochMillis: parse failed for '$timeIso': ${e.message}")
            0L
        }
    }
}

