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

import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.service.PersistentState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Identity tests for the device re-registration 401 loop:
 * - R1: when the purchase cid (recvCid) differs from the stored cid, the stored pair is
 *   discarded and a fresh DID is minted under recvCid (single /d/reg, blank did header).
 * - R2: when the mint fails, reconcileDidForCid returns a failed result with a blank did
 *   (never the stale stored DID bound to the old cid) and the store is untouched.
 * - R3: reconcileDidForCid mints under the new cid and persists the new pair.
 * - R4: refreshIdentity never sends the stale stored DID when the cid was freshly
 *   minted (blank did header on the first /d/reg).
 *
 * HTTP is faked by mocking [RetrofitManager.okHttpClient] to return a client whose
 * interceptor records request headers and answers with a canned script.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BillingBackendClientTest : KoinTest {

    private data class CapturedRequest(
        val path: String,
        val cidHeader: String?,
        val didHeader: String?,
        val testParameterPresent: Boolean
    )

    private val capturedRequests = mutableListOf<CapturedRequest>()

    /** Script: invoked for every request; returns the canned okhttp Response. */
    private var script: (Request) -> Response = { _ ->
        error("no script configured")
    }

    private val mockPersistentState: PersistentState = mockk(relaxed = true)
    private val mockIdentityStore: SecureIdentityStore = mockk(relaxed = true)

    /** In-memory backing for the mocked SecureIdentityStore. */
    private var storedCid: String? = null
    private var storedDid: String? = null
    private var testStoredCid: String? = null
    private var testStoredDid: String? = null
    private var testMode = false

    private lateinit var client: BillingBackendClient

    @Before
    fun setUp() {
        try { stopKoin() } catch (_: Exception) {}

        capturedRequests.clear()
        storedCid = null
        storedDid = null
        testStoredCid = null
        testStoredDid = null
        testMode = false

        every { mockPersistentState.appTestMode } answers { testMode }
        every { mockPersistentState.routeRethinkInRethink } returns false
        every { mockPersistentState.appVersion } returns 69

        coEvery { mockIdentityStore.get(any()) } coAnswers {
            when (firstArg<SecureIdentityStore.Env>()) {
                SecureIdentityStore.Env.PROD -> Pair(storedCid, storedDid)
                SecureIdentityStore.Env.TEST -> Pair(testStoredCid, testStoredDid)
            }
        }
        coEvery { mockIdentityStore.save(any(), any(), any()) } coAnswers {
            val env: SecureIdentityStore.Env = firstArg()
            val cid: String = secondArg()
            val did: String = thirdArg()
            if (cid.isNotBlank() && did.isNotBlank()) {
                if (env == SecureIdentityStore.Env.TEST) {
                    testStoredCid = cid
                    testStoredDid = did
                } else {
                    storedCid = cid
                    storedDid = did
                }
                true
            } else {
                false
            }
        }
        every { mockIdentityStore.hasLegacyIdentity() } returns false

        val recordingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                capturedRequests.add(
                    CapturedRequest(
                        path = request.url.encodedPath,
                        cidHeader = request.header("x-rethink-app-cid"),
                        didHeader = request.header("x-rethink-app-did"),
                        testParameterPresent = request.url.queryParameterNames.contains("test")
                    )
                )
                script(request)
            })
            .build()

        mockkObject(RetrofitManager)
        every { RetrofitManager.okHttpClient(any()) } returns recordingClient

        startKoin {
            modules(module {
                single { mockPersistentState }
                single { mockIdentityStore }
            })
        }

        client = BillingBackendClient(mockIdentityStore)
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun okResponse(request: Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun unauthorizedResponse(request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("""{"error":"did verification failed"}""".toResponseBody("application/json".toMediaType()))
            .build()

    /**
     * R1: recvCid differs from stored cid; the stored did is never sent, a single
     * blank-did /d/reg mints the new did, and the store is replaced.
     */
    @Test
    fun `R1 reconcileDidForCid refuses to rebind the stored identity to a foreign cid`() = runTest {
        storedCid = "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val recvCid = "cid-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val result = client.reconcileDidForCid(recvCid)

        check(!result.isSuccess && result.deviceId.isEmpty()) {
            "expected a refused rebind (blank did), got errorCode=${result.errorCode}"
        }
        check(result.errorCode == BillingBackendClient.DID_REBIND_REFUSED) {
            "refusal must carry DID_REBIND_REFUSED, got errorCode=${result.errorCode}"
        }
        check(storedCid == "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") { "store cid must be untouched" }
        check(storedDid == "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") { "store did must be untouched" }
        check(capturedRequests.isEmpty()) { "no network call may be made for a refused rebind: ${capturedRequests.map { it.path }}" }
    }

    /**
     * R2: a failed mint for the STORED cid (did missing) must not leak a stale did;
     * the result carries a blank did and the store is untouched.
     */
    @Test
    fun `R2 reconcileDidForCid mint 401 returns blank not stale did`() = runTest {
        storedCid = "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = null

        script = { request -> unauthorizedResponse(request) }

        val result = client.reconcileDidForCid("cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        check(!result.isSuccess && result.deviceId.isEmpty()) {
            "expected blank did on mint failure, got len=${result.deviceId.length} (stale did leak)"
        }
        check(result.errorCode == 401) { "expected 401 errorCode, got ${result.errorCode}" }
        check(storedCid == "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") { "store must be untouched" }
        check(storedDid == null) { "store must be untouched" }
        check(capturedRequests.size == 1) { "mint must be attempted once, got ${capturedRequests.size}" }
    }

    /** R3: reconcileDidForCid under a mismatched cid is refused in BOTH environments. */
    @Test
    fun `R3 reconcileDidForCid mismatch is refused without touching either store`() = runTest {
        testMode = true
        storedCid = "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        testStoredCid = "cid-test-old-rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr"
        testStoredDid = "did-test-old-ssssssssssssssssssssssssssssssssssss"

        val targetCid = "cid-new-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val result = client.reconcileDidForCid(targetCid)

        check(!result.isSuccess && result.errorCode == BillingBackendClient.DID_REBIND_REFUSED) {
            "expected refusal, got errorCode=${result.errorCode}"
        }
        check(storedCid == "cid-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" &&
            storedDid == "did-old-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") { "production store must be untouched" }
        check(testStoredCid == "cid-test-old-rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr" &&
            testStoredDid == "did-test-old-ssssssssssssssssssssssssssssssssssss") { "test store must be untouched" }
        check(capturedRequests.isEmpty()) { "no network call may be made for a refused rebind" }
    }

    /**
     * R4: refreshIdentity with a blank stored cid must NOT send the stale stored
     * did on the first /d/reg (blank did header = legitimate first mint).
     */
    @Test
    fun `R4 refreshIdentity with blank cid does not send stale did on first reg`() = runTest {
        storedCid = null
        storedDid = "did-orphan-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/acc" -> okResponse(request, """{"cid":"cid-fresh-cccccccccccccccccccccccccccccccccccc"}""")
                "/d/reg" -> okResponse(request, """{"did":"did-fresh-dddddddddddddddddddddddddddddddddddd"}""")
                else     -> unauthorizedResponse(request)
            }
        }

        val result = client.refreshIdentity()

        check(result is RefreshIdentityResult.Success) { "expected success, got $result" }
        val regRequests = capturedRequests.filter { it.path == "/d/reg" }
        check(regRequests.isNotEmpty()) { "/d/reg must have been called" }
        check(regRequests.none { it.didHeader != null }) {
            "first /d/reg under a fresh cid must omit the did header (sent: ${regRequests.map { it.didHeader }})"
        }
    }

    /**
     * B1: with an empty store and a preferredCid (the purchase's authoritative cid),
     * the purchase-cid adoption path must mint the DID under that cid on /d/reg
     * (blank did header, no new account from /d/acc) and persist the pair.
     */
    @Test
    fun `B1 reconcileDidForCid adopts preferredCid and mints did under it`() = runTest {
        storedCid = null
        storedDid = null

        val preferredCid = "cid-purchase-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> okResponse(request, """{"did":"did-adopted-ffffffffffffffffffffffffffffffffff"}""")
                else     -> unauthorizedResponse(request)
            }
        }

        val result = client.reconcileDidForCid(preferredCid)

        check(result.isSuccess && result.deviceId == "did-adopted-ffffffffffffffffffffffffffffffffff") {
            "expected adoption to mint a did under the preferred cid, got $result"
        }
        check(capturedRequests.none { it.path == "/d/acc" }) {
            "cid adoption must not create a new account: ${capturedRequests.map { it.path }}"
        }
        val regRequests = capturedRequests.filter { it.path == "/d/reg" }
        check(regRequests.isNotEmpty()) { "/d/reg must have been called" }
        check(regRequests.all { it.cidHeader == preferredCid }) {
            "/d/reg must carry the preferred cid header (sent: ${regRequests.map { it.cidHeader }})"
        }
        check(regRequests.none { it.didHeader != null }) {
            "first /d/reg under the adopted cid must omit the did header"
        }
        check(storedCid == preferredCid && storedDid == result.deviceId) {
            "adopted pair must be persisted (stored=($storedCid, ${storedDid?.take(8)}))"
        }
    }

    @Test
    fun `B1 reconcileDidForCid does not adopt a rejected preferred cid in test mode`() = runTest {
        testMode = true
        storedCid = null
        storedDid = null
        val preferredCid = "cid-purchase-llllllllllllllllllllllllllllllllllll"

        script = { request -> unauthorizedResponse(request) }

        val result = client.reconcileDidForCid(preferredCid)

        check(!result.isSuccess && result.deviceId.isEmpty()) { "expected rejection, got $result" }
        check(capturedRequests.map { it.path } == listOf("/d/reg")) {
            "only the adoption attempt may reach the server: ${capturedRequests.map { it.path }}"
        }
        check(capturedRequests.single().cidHeader == preferredCid) {
            "rejection must be reported against the preferred cid (sent: ${capturedRequests.single().cidHeader})"
        }
        check(storedCid == null && storedDid == null && testStoredCid == null && testStoredDid == null) {
            "unconfirmed identity must not be stored in either environment"
        }
    }

    @Test
    fun `B1 refreshIdentity ignores production purchase cid in test mode`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val testCid = "cid-test-cccccccccccccccccccccccccccccccccccc"
        val testDid = "did-test-dddddddddddddddddddddddddddddddddddd"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/acc" -> {
                    check(request.header("x-rethink-app-cid") == null) {
                        "test backend must not receive the production CID"
                    }
                    okResponse(request, """{"cid":"$testCid"}""")
                }
                "/d/reg" -> {
                    check(request.header("x-rethink-app-cid") == testCid) {
                        "DID must be registered under the test CID"
                    }
                    okResponse(request, """{"did":"$testDid"}""")
                }
                else -> unauthorizedResponse(request)
            }
        }

        val result = client.refreshIdentity()

        check(result == RefreshIdentityResult.Success(testCid, testDid)) {
            "expected a test-environment identity, got $result"
        }
        check(storedCid == "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") {
            "test identity resolution must not overwrite production CID"
        }
        check(storedDid == "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") {
            "test identity resolution must not overwrite production DID"
        }
        check(testStoredCid == testCid && testStoredDid == testDid) {
            "test identity must be saved separately"
        }
    }

    @Test
    fun `identity methods resolve a cid from its stored environment`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val deviceId = client.getDeviceIdForCid(storedCid!!)
        val didResult = client.reconcileDidForCid(storedCid!!)

        check(deviceId == "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") {
            "production CID must use its production DID"
        }
        check(didResult.isSuccess && didResult.deviceId == deviceId) {
            "reconciliation must read the DID from the matching environment"
        }
        check(capturedRequests.isEmpty()) { "cross-environment CIDs must not be sent to /d/reg" }
        check(testStoredCid == null && testStoredDid == null) {
            "refusing a production CID must not write the test identity store"
        }
    }

    @Test
    fun `server calls use the backend that issued the cid`() = runTest {
        // A stored pair is only valid on the backend that issued it; the other backend
        // rejects the cid as unknown. Routing follows the issuing store's environment,
        // falling back to the app's active environment for unknown cids.
        testMode = false
        testStoredCid = "cid-test-cccccccccccccccccccccccccccccccccccc"
        testStoredDid = "did-test-dddddddddddddddddddddddddddddddddddd"
        script = { request -> okResponse(request, """{}""") }

        check(client.registerDevice(testStoredCid!!, testStoredDid!!).isSuccess) {
            "a test-store cid must go through the test backend even with testMode off"
        }

        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        check(client.registerDevice(storedCid!!, storedDid!!).isSuccess) {
            "a prod-store cid must go through the production backend even with testMode on"
        }

        val unknownCid = "cid-unknown-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        check(client.registerDevice(unknownCid, "did-unknown-yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy").isSuccess) {
            "an unknown cid falls back to the active environment's backend"
        }

        check(capturedRequests.map { it.testParameterPresent } == listOf(true, false, true)) {
            "backend routing must follow the issuing environment, then appTestMode: " +
                capturedRequests.map { it.testParameterPresent }
        }
    }

    /** An initial blank-DID mint is attempted once without a re-bind retry. */
    @Test
    fun `A1 refreshIdentity does not retry a rejected initial did mint`() = runTest {
        storedCid = "cid-stored-jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj"
        storedDid = null

        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> unauthorizedResponse(request)
                else     -> okResponse(request, """{"cid":"$storedCid"}""")
            }
        }

        val first = client.refreshIdentity()
        check(first is RefreshIdentityResult.Unauthorized) { "expected Unauthorized, got $first" }
        check(first.accountId == storedCid) {
            "the rejected cid must ride along so 401 handling can run recovery"
        }
        check(storedCid == "cid-stored-jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj" && storedDid == null) {
            "store must be untouched on failure"
        }
        val firstPassRegs = capturedRequests.filter { it.path == "/d/reg" }
        check(firstPassRegs.size == 1) {
            "initial DID mint must be attempted once, got ${firstPassRegs.size}"
        }
        check(firstPassRegs.all { it.didHeader == null }) { "mint attempts must omit the did header" }

        capturedRequests.clear()
        val second = client.refreshIdentity()
        check(second is RefreshIdentityResult.Unauthorized) { "expected Unauthorized, got $second" }
        val secondPassRegs = capturedRequests.filter { it.path == "/d/reg" }
        check(secondPassRegs.size == 1) {
            "a repeated initial mint must still make only one /d/reg call"
        }
    }

    /** A refused rebind in one environment must not touch the other environment's identity. */
    @Test
    fun `rebind refusal in test mode leaves production identity untouched`() = runTest {
        testMode = true
        storedCid = "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        storedDid = "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        testStoredCid = "cid-test-old-rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr"
        testStoredDid = "did-test-old-ssssssssssssssssssssssssssssssssssss"

        val newCid = "cid-test-new-tttttttttttttttttttttttttttttttttttt"
        val mintResult = client.reconcileDidForCid(newCid)
        check(!mintResult.isSuccess && mintResult.errorCode == BillingBackendClient.DID_REBIND_REFUSED) {
            "expected refusal, got errorCode=${mintResult.errorCode}"
        }
        check(testStoredCid == "cid-test-old-rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr" &&
            testStoredDid == "did-test-old-ssssssssssssssssssssssssssssssssssss") { "test store must be untouched" }
        check(storedCid == "cid-prod-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" &&
            storedDid == "did-prod-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb") { "production store must be untouched" }
        check(capturedRequests.isEmpty()) { "no network call may be made for a refused rebind" }
    }

    /** RC1: a server-rejected stored pair is re-minted under the same cid, exactly once. */
    @Test
    fun `RC1 recoverIdentityAfterAuthFailure re-mints the stored did`() = runTest {
        storedCid = "cid-stored-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk"
        storedDid = "did-stale-llllllllllllllllllllllllllllllllllllllll"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> okResponse(request, """{"did":"did-fresh-mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm"}""")
                else     -> unauthorizedResponse(request)
            }
        }

        val recovered = client.recoverIdentityAfterAuthFailure(storedCid!!)

        check(recovered == IdentityRecoveryOutcome.RECOVERED) { "expected recovery to mint a fresh did, got $recovered" }
        val regs = capturedRequests.filter { it.path == "/d/reg" }
        check(regs.size == 1) { "exactly one /d/reg mint, got ${regs.size}" }
        check(regs.single().cidHeader == storedCid) { "mint must carry the stored cid" }
        check(regs.single().didHeader == null) { "mint must omit the stale did header" }
        check(storedDid == "did-fresh-mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm") { "fresh pair must be persisted" }
    }

    /** RC2: a failing foreign cid never wipes or re-mints the stored identity. */
    @Test
    fun `RC2 recoverIdentityAfterAuthFailure keeps the store for a foreign cid`() = runTest {
        storedCid = "cid-stored-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk"
        storedDid = "did-stored-llllllllllllllllllllllllllllllllllllllll"
        script = { request -> okResponse(request, """{"did":"did-never-pppppppppppppppppppppppppppppppp"}""") }

        val recovered = client.recoverIdentityAfterAuthFailure("cid-foreign-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")

        check(recovered == IdentityRecoveryOutcome.SKIPPED) { "a foreign cid must not trigger recovery, got $recovered" }
        check(capturedRequests.isEmpty()) { "no network call may be made for a foreign cid" }
        check(storedDid == "did-stored-llllllllllllllllllllllllllllllllllllllll") { "store must be untouched" }
    }

    /** RC3: the backoff window collapses the 401 burst; a later retry always recovers. */
    @Test
    fun `RC3 recoverIdentityAfterAuthFailure respects the backoff window`() = runTest {
        storedCid = "cid-stored-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk"
        storedDid = "did-stale-llllllllllllllllllllllllllllllllllllllll"
        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> okResponse(request, """{"did":"did-fresh-mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm"}""")
                else     -> unauthorizedResponse(request)
            }
        }

        // Inside the window: the burst of 401s from one failure is collapsed.
        every { mockPersistentState.identityRecoveryAttemptTs } returns System.currentTimeMillis()
        val suppressed = client.recoverIdentityAfterAuthFailure(storedCid!!)
        check(suppressed == IdentityRecoveryOutcome.BACKOFF) { "backoff window must suppress recovery, got $suppressed" }
        check(capturedRequests.isEmpty()) { "backoff must prevent any /d/reg call" }

        // After the window: the user's next retry always gets a fresh recovery attempt.
        every { mockPersistentState.identityRecoveryAttemptTs } returns
            System.currentTimeMillis() - BillingBackendClient.IDENTITY_RECOVERY_BACKOFF_MS - 1
        val recovered = client.recoverIdentityAfterAuthFailure(storedCid!!)
        check(recovered == IdentityRecoveryOutcome.RECOVERED) { "a retry past the window must recover, got $recovered" }
        check(capturedRequests.map { it.path } == listOf("/d/reg")) {
            "exactly one mint after the window: ${capturedRequests.map { it.path }}"
        }
        check(storedDid == "did-fresh-mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm") { "fresh pair must be persisted" }
    }

    /** RC4: a failed mint keeps the old pair, so the store never degrades to empty. */
    @Test
    fun `RC4 recoverIdentityAfterAuthFailure keeps the old pair when the mint fails`() = runTest {
        storedCid = "cid-stored-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk"
        storedDid = "did-old-llllllllllllllllllllllllllllllllllllllll"
        script = { request -> unauthorizedResponse(request) }

        val recovered = client.recoverIdentityAfterAuthFailure(storedCid!!)

        check(recovered == IdentityRecoveryOutcome.CID_REJECTED) {
            "a mint 401 means the cid itself was rejected, got $recovered"
        }
        check(capturedRequests.map { it.path } == listOf("/d/reg")) {
            "only one mint attempt may reach the server: ${capturedRequests.map { it.path }}"
        }
        check(storedCid == "cid-stored-kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk" &&
            storedDid == "did-old-llllllllllllllllllllllllllllllllllllllll") {
            "old pair must survive a failed mint so resolution never restarts from scratch"
        }
    }

    /**
     * RESET: clearing the store must immediately re-claim it for the entitlement cid.
     * An empty store left behind would be claimed by the next arbitrary resolveIdentity
     * caller with a fresh anonymous cid, re-blocking the entitlement cid forever.
     */
    @Test
    fun `RESET resetAndClaimIdentity re-claims the cleared store for the entitlement cid`() = runTest {
        storedCid = "cid-foreign-uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu"
        storedDid = "did-foreign-vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv"
        val entitlementCid = "cid-entitlement-wwwwwwwwwwwwwwwwwwwwwwwwwwwwww"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> okResponse(request, """{"did":"did-entitlement-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}""")
                else     -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val claimed = client.resetAndClaimIdentity(entitlementCid)

        check(claimed) { "reset must succeed when the claim mint succeeds" }
        check(storedCid == entitlementCid &&
            storedDid == "did-entitlement-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") {
            "store must hold the entitlement pair after reset, got ($storedCid, $storedDid)"
        }
        val regs = capturedRequests.filter { it.path == "/d/reg" }
        check(regs.size == 1) { "exactly one /d/reg claim mint, got ${regs.size}" }
        check(regs.single().cidHeader == entitlementCid) { "claim must mint under the entitlement cid" }
        check(regs.single().didHeader == null) { "claim must not echo the foreign did" }
    }

    /** RESET: a failed claim restores the previous pair instead of leaving the store empty. */
    @Test
    fun `RESET resetAndClaimIdentity restores the previous identity when the claim fails`() = runTest {
        storedCid = "cid-foreign-uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu"
        storedDid = "did-foreign-vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv"

        script = { request ->
            when (request.url.encodedPath) {
                "/d/reg" -> unauthorizedResponse(request)
                else     -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val claimed = client.resetAndClaimIdentity("cid-entitlement-wwwwwwwwwwwwwwwwwwwwwwwwwwwwww")

        check(!claimed) { "reset must fail when the claim mint fails" }
        check(storedCid == "cid-foreign-uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu" &&
            storedDid == "did-foreign-vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv") {
            "a failed claim must restore the prior pair, got ($storedCid, $storedDid)"
        }
    }
}
