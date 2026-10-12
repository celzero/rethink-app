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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import android.os.Build
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.IBillingServerApi
import com.celzero.bravedns.customdownloader.IBillingServerApiTest
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.customdownloader.SafeResponseConverterFactory
import com.celzero.bravedns.iab.BillingBackendClient.Companion.DB_SESSION_UNCONSTRAINED
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds


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

    private val subscriptionDb by inject<com.celzero.bravedns.database.SubscriptionStatusRepository>()


    companion object {
        /** Error code for a refused identity rebind. */
        internal const val DID_REBIND_REFUSED = -2
        internal const val ACK_MARKER_UNAUTHORIZED = "Unauthorized: 401"
        internal const val ACK_MARKER_CONFLICT = "Conflict: 409"

        /** Minimum interval between identity recovery attempts. */
        internal const val IDENTITY_RECOVERY_BACKOFF_MS = 60 * 1000L

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

    /** Latest DID token received from each backend; never shared across environments. */
    @Volatile private var didTokenProd: String? = null
    @Volatile private var didTokenTest: String? = null

    /** Serializes identity writes and network operations to prevent concurrent CID/DID updates. */
    private val identityMutex = Mutex()

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

    private fun otherEnv(env: SecureIdentityStore.Env): SecureIdentityStore.Env =
        if (env == SecureIdentityStore.Env.TEST) SecureIdentityStore.Env.PROD
        else SecureIdentityStore.Env.TEST

    /** Tracks whether the one-time legacy identity migration has completed. */
    private val identityMigrationDone = AtomicBoolean(false)

    /**
     * Runs the one-time legacy identity migration outside the store lock.
     */
    private suspend fun ensureIdentityMigrated() {
        if (identityMigrationDone.get()) return
        if (!identityStore.hasLegacyIdentity()) {
            identityMigrationDone.set(true)
            return
        }
        try {
            val (legacyCid, legacyDid) = identityStore.readLegacy()
            val migrated = if (legacyCid.isNullOrBlank() || legacyDid.isNullOrBlank()) {
                // Nothing usable in the legacy file — migrateFromLegacy deletes it.
                Logger.w(LOG_IAB, "$TAG ensureIdentityMigrated: legacy identity blank/corrupt; deleting without migration")
                identityStore.migrateFromLegacy(currentEnv())
            } else {
                identityStore.migrateFromLegacy(determineLegacyMigrationEnv(legacyDid))
            }
            if (migrated) {
                identityMigrationDone.set(true)
            } else {
                Logger.w(LOG_IAB, "$TAG ensureIdentityMigrated: env write failed; legacy identity kept, will retry on next identity access")
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG ensureIdentityMigrated: migration failed (${e.message}); will retry on next identity access", e)
        }
    }

    suspend fun storedIdentityDidForCurrentEnv(): String = when (val result = resolveIdentity()) {
        is RefreshIdentityResult.Success -> result.did
        else -> ""
    }

    /** The stored DID for the active environment, **without** any network fallback. */
    suspend fun peekStoredDid(): String {
        ensureIdentityMigrated()
        val env = currentEnv()
        val (_, did) = identityStore.get(env)
        return did.orEmpty()
    }

    /**
     * Mints a new DID for [cid] (blank did header) and replaces the stored pair for [env].
     * A DID is bound to its cid, so an old DID is never sent. Caller MUST hold [identityMutex].
     */
    private suspend fun mintDidLocked(env: SecureIdentityStore.Env, cid: String): DidResult {
        // clear the cached DID session token for this environment.
        if (env == SecureIdentityStore.Env.PROD) didTokenProd = null else didTokenTest = null
        val result = createOrRegisterDid(cid, "", env)
        if (result.isSuccess) {
            val saved = identityStore.save(env, cid, result.deviceId)
            if (saved) {
                Logger.i(LOG_IAB, "$TAG mintDid [${env.label}]: did minted under cid=${cid.take(8)} (didLen=${result.deviceId.length})")
            } else {
                Logger.e(LOG_IAB, "$TAG mintDid [${env.label}]: did minted but PERSIST FAILED for cid=${cid.take(8)}; the DID will be re-minted on next resolve")
            }
        } else {
            Logger.e(LOG_IAB, "$TAG mintDid [${env.label}]: failed for cid=${cid.take(8)} (code=${result.errorCode})")
        }
        return result
    }

    /**
     * Resolves the DID for [cid], using the purchase's environment when known.
     * Reuses the stored identity when it matches; otherwise mints a new DID.
     * Caller must hold [identityMutex].
     */
    private suspend fun adoptCidLocked(cid: String): DidResult {
        val env = storedEnvironmentForCid(cid) ?: currentEnv()
        val (storedCid, storedDid) = identityStore.get(env)
        if (storedCid == cid && !storedDid.isNullOrBlank()) {
            Logger.d(LOG_IAB, "$TAG adoptCid [${env.label}]: did already present (len=${storedDid.length})")
            return DidResult(storedDid)
        }
        Logger.i(LOG_IAB, "$TAG adoptCid [${env.label}]: stored cid=${storedCid?.take(8)} != cid=${cid.take(8)} or did missing; minting new did")
        return mintDidLocked(env, cid)
    }

    /** Resolves the DID bound to [cid]. */
    suspend fun didForCid(cid: String, mintIfMissing: Boolean): DidResult {
        if (cid.isBlank()) {
            Logger.w(LOG_IAB, "$TAG didForCid: blank cid (mint=$mintIfMissing)")
            return DidResult.error(-1)
        }
        ensureIdentityMigrated()
        val env = storedEnvironmentForCid(cid)
        if (env != null) {
            val (storedCid, storedDid) = identityStore.get(env)
            if (storedCid == cid && !storedDid.isNullOrBlank()) return DidResult(storedDid)
        }
        if (!mintIfMissing) {
            Logger.w(LOG_IAB, "$TAG didForCid: no did bound to cid=${cid.take(8)} (read-only)")
            return DidResult.EMPTY
        }
        return identityMutex.withLock {
            val envLocked = storedEnvironmentForCid(cid)
            if (envLocked != null) {
                val (_, didLocked) = identityStore.get(envLocked)
                if (!didLocked.isNullOrBlank()) return@withLock DidResult(didLocked)
            }
            // only an empty store or the existing CID may claim the identity.
            if (envLocked == null) {
                val (prodCid, testCid) = Pair(
                    identityStore.get(SecureIdentityStore.Env.PROD).first,
                    identityStore.get(SecureIdentityStore.Env.TEST).first
                )
                if (!prodCid.isNullOrBlank() || !testCid.isNullOrBlank()) {
                    Logger.e(LOG_IAB, "$TAG didForCid: refusing to rebind stored identity " +
                        "(prod=${prodCid?.take(8)}, test=${testCid?.take(8)}) to foreign cid=${cid.take(8)}")
                    return@withLock DidResult.error(DID_REBIND_REFUSED)
                }
            }
            adoptCidLocked(cid)
        }
    }

    /** Read-only alias for [didForCid]: the stored DID bound to [cid], or blank. */
    suspend fun getDeviceIdForCid(cid: String): String = didForCid(cid, false).deviceId

    /** Returns true when the DID failure is definitive and should be surfaced to the user. */
    private fun isDefinitiveDidFailure(errorCode: Int): Boolean =
        errorCode in 400..499 || errorCode == DID_REBIND_REFUSED

    /**
     * Re-mints the stored DID after the server rejects the stored (cid, did) pair.
     * Only recovers when [accountId] matches the stored CID; failed mints preserve
     * the existing identity.
     */
    suspend fun recoverIdentityAfterAuthFailure(accountId: String): IdentityRecoveryOutcome {
        if (accountId.isBlank()) return IdentityRecoveryOutcome.SKIPPED
        return identityMutex.withLock {
            val env = storedEnvironmentForCid(accountId) ?: currentEnv()
            val (storedCid, storedDid) = identityStore.get(env)
            if (storedCid != accountId || storedDid.isNullOrBlank()) {
                Logger.d(LOG_IAB, "$TAG recoverIdentity: stored identity does not match failing " +
                    "cid=${accountId.take(8)}; keeping it")
                return@withLock IdentityRecoveryOutcome.SKIPPED
            }
            val now = System.currentTimeMillis()
            if (now - persistentState.identityRecoveryAttemptTs < IDENTITY_RECOVERY_BACKOFF_MS) {
                Logger.d(LOG_IAB, "$TAG recoverIdentity: backoff window active for cid=${accountId.take(8)}")
                return@withLock IdentityRecoveryOutcome.BACKOFF
            }
            persistentState.identityRecoveryAttemptTs = now
            Logger.w(LOG_IAB, "$TAG recoverIdentity: server rejected the stored pair for " +
                "cid=${accountId.take(8)}; re-minting the did")
            val result = mintDidLocked(env, accountId)
            when {
                result.isSuccess -> IdentityRecoveryOutcome.RECOVERED
                result.errorCode == 401 -> {
                    Logger.e(LOG_IAB, "$TAG recoverIdentity: cid=${accountId.take(8)} rejected " +
                        "by the server (mint 401); only an identity reset can recover")
                    IdentityRecoveryOutcome.CID_REJECTED
                }
                else -> IdentityRecoveryOutcome.FAILED
            }
        }
    }

    /**
     * Clears all stored identities and claims the store for [entitlementCid].
     * Restores the previous identities if the claim fails.
     */
    suspend fun resetAndClaimIdentity(entitlementCid: String): Boolean {
        return identityMutex.withLock {
            withContext(NonCancellable) {
                val previous = SecureIdentityStore.Env.entries.associateWith { env ->
                    identityStore.get(env)
                }
                identityStore.clearAll()
                didTokenProd = null
                didTokenTest = null
                if (entitlementCid.isBlank()) {
                    Logger.e(LOG_IAB, "$TAG resetAndClaim: blank entitlement cid; restoring previous identity")
                    if (!restoreLocked(previous)) {
                        Logger.e(LOG_IAB, "$TAG resetAndClaim: previous identity NOT restored after blank cid")
                    }
                    return@withContext false
                }
                val claimed = adoptCidLocked(entitlementCid)
                if (claimed.isSuccess) {
                    Logger.i(LOG_IAB, "$TAG resetAndClaim: store re-claimed for cid=${entitlementCid.take(8)}")
                    true
                } else {
                    Logger.e(LOG_IAB, "$TAG resetAndClaim: claim failed (code=${claimed.errorCode}) for " +
                        "cid=${entitlementCid.take(8)}; restoring previous identity")
                    if (!restoreLocked(previous)) {
                        Logger.e(LOG_IAB, "$TAG resetAndClaim: previous identity NOT restored after " +
                            "failed claim (code=${claimed.errorCode}) for cid=${entitlementCid.take(8)}")
                    }
                    false
                }
            }
        }
    }

    /**
     * Restores previously stored identities after a failed reset. Caller must hold [identityMutex].
     * Returns false when any save failed, so callers do not report a successful restoration.
     */
    private suspend fun restoreLocked(previous: Map<SecureIdentityStore.Env, Pair<String?, String?>>): Boolean {
        var restored = true
        previous.forEach { (env, pair) ->
            val (cid, did) = pair
            if (!cid.isNullOrBlank() && !did.isNullOrBlank()) {
                val saved = identityStore.save(env, cid, did)
                if (!saved) {
                    restored = false
                    Logger.e(LOG_IAB, "$TAG restoreLocked: save failed [${env.label}] for cid=${cid.take(8)}; previous identity not restored")
                }
            }
        }
        return restored
    }

    /**
     * Returns the stored CID from either environment, or blank when unavailable.
     */
    suspend fun peekStoredCid(): String {
        val (prodCid, _) = identityStore.get(SecureIdentityStore.Env.PROD)
        val (testCid, _) = identityStore.get(SecureIdentityStore.Env.TEST)
        return prodCid?.takeIf { it.isNotBlank() } ?: testCid.orEmpty()
    }

    /**
     * Ensures the device is registered using the primary purchase identity when available.
     * Falls back to the stored identity when there is no purchase and skips registration
     * when the existing registration is still within [refreshIntervalMs].
     */
    suspend fun ensureDeviceRegistration(
        primaryPurchase: PurchaseDetail?,
        refreshIntervalMs: Long,
        onDeviceNotRegistered: suspend (entitlementCid: String) -> Unit,
        register: suspend (accountId: String, deviceId: String, purchase: PurchaseDetail?) -> Unit
    ) {
        val mname = "ensureDeviceRegistration"
        val purchaseCid = primaryPurchase?.accountId?.takeIf { it.isNotBlank() }

        val accountId: String
        val deviceId: String
        if (purchaseCid != null) {
            val didResult = didForCid(purchaseCid, mintIfMissing = true)
            if (!didResult.isSuccess) {
                if (isDefinitiveDidFailure(didResult.errorCode)) {
                    Logger.e(LOG_IAB, "$TAG $mname: no did bound to primary cid=${purchaseCid.take(8)} " +
                        "(code=${didResult.errorCode}); surfacing device-not-registered, skipping registration")
                    onDeviceNotRegistered(purchaseCid)
                } else {
                    // retry on the next worker pass without notifying the user.
                    Logger.w(LOG_IAB, "$TAG $mname: transient did failure for cid=${purchaseCid.take(8)} " +
                        "(code=${didResult.errorCode}); skipping registration silently")
                }
                return
            }
            accountId = purchaseCid
            deviceId = didResult.deviceId
        } else {
            val identity = resolveIdentity()
            if (identity !is RefreshIdentityResult.Success) {
                Logger.w(LOG_IAB, "$TAG $mname: identity unavailable, skipping registration")
                return
            }
            accountId = identity.cid
            deviceId = identity.did
        }

        val isStale = (System.currentTimeMillis() - persistentState.deviceRegistrationTimestamp) >
            refreshIntervalMs
        if (!isStale) {
            Logger.d(LOG_IAB, "$TAG $mname: device already registered within window, skipping")
            return
        }
        register(accountId, deviceId, primaryPurchase)
    }

    /**
     * Binds the stored identity to the Play purchase [cid], minting a DID if needed.
     */
    suspend fun reconcileDidForCid(cid: String): DidResult = didForCid(cid, true)

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
        ensureIdentityMigrated()
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
     */
    suspend fun createOrRegisterCid(
        existingCid: String = "",
        env: SecureIdentityStore.Env = currentEnv()
    ): CidResult = withContext(Dispatchers.IO) {
        val mname = "createOrRegisterCid"
        try {
            val meta = buildCustomerMeta()
            // Pass null when blank so Retrofit omits the header entirely, letting the server
            // assign a fresh CID.  A non-blank value asks the server to confirm/correct it.
            val cidHeader = existingCid.takeIf { it.isNotBlank() }
            val vcode = persistentState.appVersion.toString()
            Logger.v(LOG_IAB, "$TAG $mname [${env.label}]: calling /d/acc, cidHeader=${if (cidHeader != null) "present(len=${cidHeader.length})" else "absent"}")
            val response = if (env == SecureIdentityStore.Env.TEST) {
                buildTestApi().registerCustomer(cidHeader, "",  vcode, test = "", meta)
            } else {
                buildProductionApi().registerCustomer(cidHeader, "", vcode, meta)
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
     * @param env Backend environment; pass the captured identity environment for paired
     *        CID/DID bootstrap operations.
     */
    suspend fun createOrRegisterDid(
        accountId: String,
        existingDeviceId: String = "",
        env: SecureIdentityStore.Env = currentEnv()
    ): DidResult = withContext(Dispatchers.IO) {
        val mname = "createOrRegisterDid"
        if (accountId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: blank accountId; cannot register device without account")
            return@withContext DidResult.error(-1)
        }
        try {
            val meta = buildCustomerMeta()
            // Pass null for did when blank so Retrofit omits the header, letting the server
            // assign a fresh DID.  accountId is always required and sent as a non-null header.
            val didHeader = existingDeviceId.takeIf { it.isNotBlank() }
            val vcode = persistentState.appVersion.toString()
            Logger.v(LOG_IAB, "$TAG $mname [${env.label}]: calling /d/reg, cidLen=${accountId.length}, didHeader=${if (didHeader != null) "present(len=${didHeader.length})" else "absent"}")
            val response = if (env == SecureIdentityStore.Env.TEST) {
                buildTestApi().registerDevice(accountId, didHeader, vcode, test = "", meta = meta)
            } else {
                buildProductionApi().registerDevice(accountId, didHeader, vcode, meta = meta)
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
     * Resolves the environment the legacy `identity.json` belongs to.
     *
     * ### Env determination strategy
     * 1. **VPN tunnel active**: call [VpnController.getEntitlementDetails] with the legacy
     *    DID. The returned `RpnEntitlement.test()` flag is the authoritative source —
     *    it says which server issued the purchase.
     * 2. **Tunnel unavailable** (null entitlement or exception): fall back to
     *    [PersistentState.appTestMode], which historically controlled which server
     *    ([com.celzero.bravedns.util.Constants.RPN_BASE_URL] vs fallback) was called when
     *    the CID/DID were originally obtained — so it is the best static proxy.
     */
    private suspend fun determineLegacyMigrationEnv(legacyDid: String): SecureIdentityStore.Env {
        return try {
            val entitlement = VpnController.getEntitlementDetails(null, legacyDid)
            if (entitlement != null) {
                val isTest = entitlement.test()
                Logger.i(LOG_IAB, "$TAG legacyMigrationEnv: entitlement resolved, isTest=$isTest " +
                    "(didLen=${legacyDid.length})")
                if (isTest) SecureIdentityStore.Env.TEST else SecureIdentityStore.Env.PROD
            } else {
                val isTest = persistentState.appTestMode
                Logger.i(LOG_IAB, "$TAG legacyMigrationEnv: tunnel unavailable, using appTestMode=$isTest as env " +
                    "(didLen=${legacyDid.length})")
                if (isTest) SecureIdentityStore.Env.TEST else SecureIdentityStore.Env.PROD
            }
        } catch (e: Exception) {
            val isTest = persistentState.appTestMode
            Logger.w(LOG_IAB, "$TAG legacyMigrationEnv: entitlement lookup failed (${e.message}); falling back to appTestMode=$isTest")
            if (isTest) SecureIdentityStore.Env.TEST else SecureIdentityStore.Env.PROD
        }
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
    suspend fun refreshIdentity(): RefreshIdentityResult =
        identityMutex.withLock { refreshIdentityLocked() }

    /**
     * Lock-free implementation of [refreshIdentity]. The caller MUST already hold
     * [identityMutex] (or invoke the public [refreshIdentity] wrapper that acquires it);
     * the split exists so other mutex-holding paths can refresh without re-acquiring
     * the non-reentrant mutex and self-deadlocking.
     */
    private suspend fun refreshIdentityLocked(): RefreshIdentityResult {
        val mname = "refreshIdentity"
        val env = currentEnv()
        val (storedCid, storedDid) = identityStore.get(env)

        var cid = storedCid ?: ""
        var cidWasAbsent = false
        if (cid.isBlank()) {
            val purchaseCid = try {
                subscriptionDb.getCurrentSubscription()?.accountId
            } catch (e: Exception) {
                Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: could not read purchase-bound cid: ${e.message}")
                null
            }
            if (!purchaseCid.isNullOrBlank()) {
                val owner = storedEnvironmentForCid(purchaseCid)
                if (owner == null || owner == env) {
                    cid = purchaseCid
                    Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: binding fresh identity to the " +
                            "purchase-bound cid (len=${cid.length}) instead of minting a new one")
                } else {
                    Logger.w(LOG_IAB, "$TAG $mname [${env.label}]: purchase cid belongs to ${owner.label}; not adopting")
                }
            }
        }
        if (cid.isBlank()) {
            val cidResult = createOrRegisterCid(env = env)
            when {
                cidResult.isSuccess -> {
                    cid = cidResult.accountId
                    cidWasAbsent = true
                    Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: new cid obtained from server (len=${cid.length})")
                }
                cidResult.errorCode == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 on cid step; cannot resolve identity")
                    return RefreshIdentityResult.Unauthorized("")
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

        var did = if (!cidWasAbsent && !storedDid.isNullOrBlank()) storedDid else ""
        if (did.isBlank()) {
            val didResult = mintDidLocked(env, cid)
            when {
                didResult.isSuccess -> {
                    did = didResult.deviceId
                    Logger.i(LOG_IAB, "$TAG $mname [${env.label}]: new did obtained from server (len=${did.length})")
                }
                didResult.errorCode == 401 -> {
                    Logger.e(LOG_IAB, "$TAG $mname [${env.label}]: 401 on did step; cannot resolve identity")
                    return RefreshIdentityResult.Unauthorized(cid)
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
            val handle = resolveApi(accountId)
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.registerDevice(accountId, deviceId, vcode = persistentState.appVersion.toString(), meta = meta)
                is ApiHandle.Test -> handle.api.registerDevice(accountId, deviceId, vcode = persistentState.appVersion.toString(), test = "", meta = meta)
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
        if (accountId.isBlank() || deviceId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname: blank accountId or deviceId; refusing server acknowledgement")
            return@withContext Pair(false, "accountId/deviceId unavailable")
        }
        Logger.d(LOG_IAB, "$TAG $mname [${currentEnv().label}]: accLen=${accountId.length}, type=$productType")

        val pt  = URLEncoder.encode(purchaseToken, "UTF-8")
        val sku = skuForType(productType)

        executeAckWithRetry(mname, accountId) { handle ->
            val vcode = persistentState.appVersion.toString()
            when (handle) {
                is ApiHandle.Production -> handle.api.acknowledgePurchase(accountId, deviceId, sku, pt, vcode)
                is ApiHandle.Test -> handle.api.acknowledgePurchase(accountId, deviceId, sku, pt, vcode, test = "")
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
        accountId: String,
        attempt: suspend (ApiHandle) -> Response<JsonObject?>?
    ): Pair<Boolean, String> {
        // Resolve the test/production split once, stable across all retries.
        val handle = resolveApi(accountId)

        // Back-off delays in ms.  The first attempt is immediate (no leading delay).
        // After attempt N fails transiently, wait ACK_RETRY_DELAYS[N] before attempt N+1.
        // When all delays are exhausted the loop exits, and we return the last failure.
        val delaysMs = ACK_RETRY_DELAYS
        val maxAttempts = delaysMs.size + 1   // 3: one immediate + one per delay

        var lastFailureMsg = "No response from server"

        for (attemptIndex in 0 until maxAttempts) {
            // Wait before every attempt except the first.
            if (attemptIndex > 0) {
                val waitMs = delaysMs[attemptIndex - 1]
                Logger.i(LOG_IAB, "$TAG $caller [${handle.envLabel}]: retry #$attemptIndex, waiting ${waitMs}ms")
                delay(waitMs.milliseconds)
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
                val errorBody = response.errorBody()?.string()
                Logger.d(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1} for url: $url, code: $httpCode, err: $errorBody")

                if (httpCode == 401) {
                    Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: 401 unauthorized, not retrying; $errorBody")
                    return Pair(false, ACK_MARKER_UNAUTHORIZED)
                }

                if (httpCode == 409) {
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: 409 conflict, not retrying; $errorBody")
                    return Pair(false, ACK_MARKER_CONFLICT)
                }

                if (httpCode == 429) {
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: 429 too many req, retrying with timeout $errorBody")
                    continue
                }

                if (httpCode in 400..499) {
                    Logger.e(LOG_IAB, "$TAG $caller [${handle.envLabel}]: permanent client error $httpCode not retrying; $errorBody")
                    return Pair(false, "Client error: $httpCode")
                }

                if (httpCode >= 500) {
                    lastFailureMsg = "Server error: $httpCode"
                    Logger.w(LOG_IAB, "$TAG $caller [${handle.envLabel}]: attempt ${attemptIndex + 1} server error $httpCode, will retry; $errorBody")
                    continue
                }

                val bodyStr = response.body()?.toString()
                val rawForParsing = bodyStr?.takeIf { it.isNotBlank() }
                    ?: errorBody
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
        if (accountId.isBlank() || deviceId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname: empty accountId or deviceId skipping")
            // Treat missing accountId as a transient/infrastructure problem, not a business error.
            return@withContext QueryEntitlementResult.Transient(purchase)
        }
        try {
            val encodedPt = URLEncoder.encode(purchaseToken, "UTF-8")
            val sku = skuForType(purchase.productType)
            val handle = resolveApi(accountId)
            val vcode = persistentState.appVersion.toString()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.queryEntitlement(accountId, deviceId, sku, encodedPt, vcode)
                is ApiHandle.Test -> handle.api.queryEntitlement(accountId, deviceId, sku, encodedPt, vcode, test = "")
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
                    if (result.payload.isSubscriptionExpired || result.payload.isPurchaseCancelled) {
                        // Server definitively confirmed the purchase is no longer valid —
                        // callers must NOT preserve the old purchase or entitlement.
                        Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: purchase definitively expired/cancelled on server " +
                            "(error=${result.payload.error}, state=${result.payload.state}); returning Expired to caller")
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
     * @return [PurchaseMutationResult.Success] on HTTP 2xx,
     *         [PurchaseMutationResult.Unauthorized] on 401,
     *         [PurchaseMutationResult.Conflict] on 409,
     *         [PurchaseMutationResult.Failure] otherwise.
     */
    suspend fun cancelPurchase(
        accountId: String,
        deviceId: String,
        sku: String,
        purchaseToken: String
    ): PurchaseMutationResult = withContext(Dispatchers.IO) {
        val mname = "cancelPurchase"
        try {
            val handle = resolveApi(accountId)
            val vcode = persistentState.appVersion.toString()
            Logger.d(LOG_IAB, "$TAG $mname [${handle.envLabel}]: accLen=${accountId.length}, devLen=${deviceId.length}, sku=$sku")
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.cancelPurchase(accountId, deviceId, sku, purchaseToken, vcode)
                is ApiHandle.Test -> handle.api.cancelSubscription(accountId, deviceId, sku, purchaseToken, vcode, test = "")
            } ?: return@withContext PurchaseMutationResult.Failure("No response")
            val bodyStr = response.body()?.toString()
            val errorBody = response.errorBody()?.string()
            val rawForParsing = bodyStr?.takeIf { it.isNotBlank() } ?: errorBody
            return@withContext when (val result = RpnPurchaseAckServerResponse.from(rawForParsing, response.code())) {
                is RpnPurchaseAckServerResponse.Ok -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: cancelled sku=$sku")
                    PurchaseMutationResult.Success(result.payload.message ?: "Cancelled")
                }
                is RpnPurchaseAckServerResponse.Err -> {
                    when (result.payload.httpCode) {
                        401 -> {
                            Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 401 unauthorized; accLen=${accountId.length}, err=${result.payload.error}")
                            PurchaseMutationResult.Unauthorized()
                        }
                        409 -> PurchaseMutationResult.Conflict()
                        else -> {
                            Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: error code=${result.payload.httpCode}, msg=${result.payload.error}")
                            PurchaseMutationResult.Failure("Server error: ${result.payload.httpCode}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: err ${e.message}", e)
            PurchaseMutationResult.Failure("Exception: ${e.message}")
        }
    }

    /**
     * Calls `POST /g/refund` to revoke/refund a purchase.
     *
     * @return [PurchaseMutationResult.Success] on HTTP 2xx,
     *         [PurchaseMutationResult.Unauthorized] on 401,
     *         [PurchaseMutationResult.Conflict] on 409,
     *         [PurchaseMutationResult.Failure] otherwise.
     */
    suspend fun revokePurchase(
        accountId: String,
        deviceId: String,
        sku: String,
        purchaseToken: String
    ): PurchaseMutationResult = withContext(Dispatchers.IO) {
        val mname = "revokePurchase"
        try {
            val handle = resolveApi(accountId)
            val vcode = persistentState.appVersion.toString()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.revokeSubscription(accountId, deviceId, sku, purchaseToken, vcode)
                is ApiHandle.Test -> handle.api.revokeSubscription(accountId, deviceId, sku, purchaseToken, vcode, test = "")
            } ?: return@withContext PurchaseMutationResult.Failure("No response")
            val bodyStr = response.body()?.toString()
            val errorBody = response.errorBody()?.string()
            val rawForParsing = bodyStr?.takeIf { it.isNotBlank() } ?: errorBody
            return@withContext when (val result = RpnPurchaseAckServerResponse.from(rawForParsing, response.code())) {
                is RpnPurchaseAckServerResponse.Ok -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: revoked sku=$sku")
                    PurchaseMutationResult.Success(result.payload.message ?: "Revoked")
                }
                is RpnPurchaseAckServerResponse.Err -> {
                    when (result.payload.httpCode) {
                        401 -> {
                            Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 401 unauthorized; accLen=${accountId.length}, err=${result.payload.error}")
                            PurchaseMutationResult.Unauthorized()
                        }
                        409 -> PurchaseMutationResult.Conflict()
                        else -> {
                            Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: err code=${result.payload.httpCode}, msg=${result.payload.error}")
                            PurchaseMutationResult.Failure("Server error: ${result.payload.httpCode}, ${result.payload.error}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG $mname: err ${e.message}", e)
            PurchaseMutationResult.Failure("Exception: ${e.message}")
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
            val handle = resolveApi(accountId)
            val vcode = persistentState.appVersion.toString()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.getPurchaseHistory(
                    accountId = accountId,
                    deviceId = deviceId,
                    purchaseToken = purchaseToken,
                    total = total,
                    vcode = vcode
                )
                is ApiHandle.Test -> handle.api.getPurchaseHistory(
                    accountId = accountId,
                    deviceId = deviceId,
                    purchaseToken = purchaseToken,
                    total = total,
                    vcode = vcode,
                    test = "",
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
        if (accountId.isBlank() || deviceId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG $mname: blank accountId or deviceId skipping")
            return@withContext false
        }
        try {
            val encodedToken = URLEncoder.encode(purchaseToken, "UTF-8")
            val handle = resolveApi(accountId)
            val vcode = persistentState.appVersion.toString()
            val response = when (handle) {
                is ApiHandle.Production -> handle.api.consumePurchase(accountId, deviceId, sku, encodedToken, vcode)
                is ApiHandle.Test -> handle.api.consumePurchase(accountId, deviceId, sku, encodedToken, vcode, test = "")
            }
            if (response == null) {
                Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: null response")
                return@withContext false
            }
            val bodyStr = response.body()?.toString()
            val errorBody = response.errorBody()?.string()
            val rawForParsing = bodyStr?.takeIf { it.isNotBlank() } ?: errorBody
            return@withContext when (val result = RpnPurchaseAckServerResponse.from(rawForParsing, response.code())) {
                is RpnPurchaseAckServerResponse.Ok -> {
                    Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: consumed sku=$sku body=$bodyStr")
                    true
                }
                is RpnPurchaseAckServerResponse.Err -> {
                    val code = result.payload.httpCode
                    // Idempotent: "already consumed" 409 is treated as success.
                    if (code == 409 && result.payload.error.contains("already consumed", ignoreCase = true)) {
                        Logger.i(LOG_IAB, "$TAG $mname [${handle.envLabel}]: idempotent already consumed sku=$sku")
                        true
                    } else {
                        when {
                            code == 409 -> Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: 409 conflict sku=$sku body=$bodyStr")
                            code in 400..499 -> Logger.e(LOG_IAB, "$TAG $mname [${handle.envLabel}]: permanent client error $code sku=$sku body=$bodyStr")
                            else -> Logger.w(LOG_IAB, "$TAG $mname [${handle.envLabel}]: transient server error $code sku=$sku")
                        }
                        false
                    }
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
     *   "appVersion": "<int>"
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
     * Resolves the endpoint from the environment whose store owns [accountId],
     * falling back to the app's active environment when no store matches (blank or
     * foreign cid). A stored pair must only be sent to the backend that issued it:
     * the other backend rejects the cid as unknown.
     */
    private suspend fun resolveApi(accountId: String): ApiHandle =
        apiForEnvironment(storedEnvironmentForCid(accountId) ?: currentEnv())

    /**
     * Maps [cid] to the environment whose identity store issued it, or null when
     * no store matches. Lock-free: pure store reads are safe without [identityMutex]
     * because the store is internally synchronized and atomically written.
     */
    private suspend fun storedEnvironmentForCid(cid: String): SecureIdentityStore.Env? {
        if (cid.isBlank()) return null
        val env = currentEnv()
        if (identityStore.get(env).first == cid) return env
        val otherEnv = otherEnv(env)
        return if (identityStore.get(otherEnv).first == cid) otherEnv else null
    }

    private fun apiForEnvironment(env: SecureIdentityStore.Env): ApiHandle =
        if (env == SecureIdentityStore.Env.TEST) {
            Logger.d(LOG_IAB, "$TAG resolveApi: using TEST interface")
            ApiHandle.Test(buildTestApi(), true)
        } else {
            Logger.d(LOG_IAB, "$TAG resolveApi: using PROD interface")
            ApiHandle.Production(buildProductionApi())
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
            .addInterceptor(buildFallbackInterceptor(Constants.RPN_BASE_URL, Constants.RPN_FALLBACK_URL))
            .addInterceptor(buildSessionInterceptor(
                DB_SESSION_HEADER, DB_SESSION_RESPONSE_HEADER,
                { dbSessionBookmark }, { dbSessionBookmark = it },
                { didTokenProd }, { didTokenProd = it }, "prod"
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
     * Mirrors the production primary/fallback split: calls target
     * [Constants.RPN_TEST_BASE_URL] as the primary, and transparently fall back to
     * [Constants.RPN_TEST_FALLBACK_URL] on 5xx or network errors.
     */
    private fun buildTestApi(): IBillingServerApiTest {
        val client = RetrofitManager
            .okHttpClient(persistentState.routeRethinkInRethink)
            .newBuilder()
            .addInterceptor(buildFallbackInterceptor(
                Constants.RPN_TEST_BASE_URL, Constants.RPN_TEST_FALLBACK_URL
            ))
            .addInterceptor(buildSessionInterceptor(
                DB_SESSION_TEST_HEADER, DB_SESSION_TEST_RESPONSE_HEADER,
                { dbSessionTestBookmark }, { dbSessionTestBookmark = it },
                { didTokenTest }, { didTokenTest = it }, "test"
            ))
            .build()
        return Retrofit.Builder()
            .baseUrl(Constants.RPN_TEST_BASE_URL)
            .client(client)
            .addConverterFactory(SafeResponseConverterFactory())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IBillingServerApiTest::class.java)
    }

    /**
     * OkHttp Application Interceptor that transparently retries a failed request
     * against the supplied [fallbackUrl] when the supplied [baseUrl] is unreachable or
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
     * Used for both [IBillingServerApi] (production: [Constants.RPN_BASE_URL] →
     * [Constants.RPN_FALLBACK_URL]) and [IBillingServerApiTest] (test:
     * [Constants.RPN_TEST_BASE_URL] → [Constants.RPN_TEST_FALLBACK_URL]).
     */
    private fun buildFallbackInterceptor(
        baseUrl: String,
        fallbackUrl: String
    ): Interceptor = Interceptor { chain ->
        val request = chain.request()
        try {
            val response = chain.proceed(request)
            if (response.code in 500..599) {
                Logger.w(LOG_IAB, "$TAG fallback: server error ${response.code} on ${request.url}, retrying with fallback URL")
                response.close()
                val fallbackRequest = request.newBuilder()
                    .url(request.url.toString().replace(baseUrl, fallbackUrl))
                    .build()
                chain.proceed(fallbackRequest)
            } else {
                response
            }
        } catch (e: IOException) {
            Logger.w(LOG_IAB, "$TAG fallback: network error (${e.message}) on ${request.url}, retrying with fallback URL")
            val fallbackRequest = request.newBuilder()
                .url(request.url.toString().replace(baseUrl, fallbackUrl))
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
        getDidToken: () -> String?,
        setDidToken: (String) -> Unit,
        tag: String
    ): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val sessionHdr = original.header(reqHeader) ?: return@Interceptor chain.proceed(original)

        val reqBuilder = original.newBuilder()
        if (sessionHdr == DB_SESSION_UNCONSTRAINED) {
            getBookmark()?.let { reqBuilder.header(reqHeader, it) }
        }
        getDidToken()?.let { reqBuilder.header(DID_TOKEN_HEADER, it) }

        val response = chain.proceed(reqBuilder.build())

        response.header(respHeader)?.takeIf { it.isNotBlank() }?.let {
            setBookmark(it)
            if (DEBUG) Logger.d(LOG_IAB, "$TAG interceptor($tag): bookmark updated (len=${it.length})")
        }
        response.header(DID_TOKEN_HEADER)?.takeIf { it.isNotBlank() }?.let {
            setDidToken(it)
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
