package com.celzero.bravedns.tunnel

import android.app.KeyguardManager
import android.net.ConnectivityManager
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.ConnectionMonitor
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.LogActivityAggregator
import com.celzero.bravedns.service.LogActivityEvent
import com.celzero.bravedns.service.LogActivitySource
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.NetLogTracker
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.ProxyManager.isAnyUserSetProxy
import com.celzero.bravedns.service.TunFirewallManager
import com.celzero.bravedns.service.TunFlowManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.bottomsheet.BlockFreeDnsModeBottomSheet
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.KnownPorts
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.intra.Mark
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CoroutineScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration

object TunDnsManager: KoinComponent {
    private const val TAG = "TunDnsManager"

    // branch labels attached to [DnsDecision]; surfaced by the connection simulator ui
    internal const val BRANCH_CAPTIVE_PORTAL = "captive-portal"
    internal const val BRANCH_RETHINK_UID = "rethink-uid"
    internal const val BRANCH_FALLBACK = "unknown-mode-fallback"
    internal const val BRANCH_APP_TRUSTED = "app-trusted"
    internal const val BRANCH_APP_BLOCKED = "app-blocked"
    internal const val BRANCH_GLOBAL_TRUSTED = "global-trusted"
    internal const val BRANCH_GLOBAL_BLOCKED = "global-blocked"
    internal const val BRANCH_DEFAULT = "default"
    internal const val BRANCH_FORCE_BYPASS = "force-bypass"
    internal const val BRANCH_PROTECTED_UID = "protected-uid"
    internal const val BRANCH_NO_UID_BYPASS = "no-uid-bypass"
    internal const val BRANCH_NO_UID_TRUSTED = "no-uid-trusted"
    internal const val BRANCH_NO_UID_GLOBAL_BLOCK = "no-uid-global-block"
    internal const val BRANCH_NO_UID_UNKNOWN_BLOCK = "no-uid-unknown-block"
    internal const val BRANCH_NO_UID_DEFAULT = "no-uid-default"
    internal const val BRANCH_TEMP_ALLOWED = "temp-allowed"
    internal const val BRANCH_CONN_BLOCKED = "conn-blocked"
    internal const val BRANCH_BYPASS_DNS = "bypass-dns-firewall"
    internal const val BRANCH_APP_TRUSTED_DF = "app-trusted-df"
    internal const val BRANCH_APP_BLOCKED_DF = "app-blocked-df"
    internal const val BRANCH_GLOBAL_TRUSTED_DF = "global-trusted-df"
    internal const val BRANCH_DEFAULT_DF = "default-df"
    internal const val BRANCH_QTYPE_BLOCK = "qtype-block"

    internal const val BRANCH_UA_INVALID_UID = "upstream-invalid-uid"
    internal const val BRANCH_UA_CAPTIVE_PORTAL = "upstream-captive-portal"
    internal const val BRANCH_UA_PORT_RULE = "upstream-port-rule"
    internal const val BRANCH_UA_EMPTY_IPCSV = "upstream-empty-ipcsv"
    internal const val BRANCH_UA_DNS_MODE = "upstream-dns-mode"
    internal const val BRANCH_UA_FIXED_TRANSPORT = "upstream-fixed-transport"
    internal const val BRANCH_UA_UNKNOWN_APP = "upstream-unknown-app"
    internal const val BRANCH_UA_FIREWALL_BLOCKED = "upstream-firewall-blocked"
    internal const val BRANCH_UA_ISOLATE_TRUSTED = "upstream-isolate-trusted"
    internal const val BRANCH_UA_BYPASS = "upstream-bypass"
    internal const val BRANCH_UA_NO_ACTION = "upstream-no-action"

    enum class UpstreamAction { KEEP_PREVIOUS, BLOCK, BYPASS }

    // structured result of the upstream-answer stage: keep the previously returned dns
    // options, block them, or swap in bypass transports. the live onUpstreamAnswer path
    // converts this to DNSOpts; the connection simulator consumes it as-is
    data class UpstreamDecision(
        val action: UpstreamAction,
        val branch: String,
        val rule: FirewallRuleset? = null,
        // bypass transports (BYPASS only), raw (no cache prefix, no proxy ids)
        val tid: Pair<String, String> = Pair("", ""),
        val pidcsv: String = "",
        // BYPASS: reuse the received transports as-is (no bypass transport needed)
        val sameTid: Boolean = false
    )

    data class DnsParams(val origin: String, val uidStr: String?, val fqdn: String, val qtype: Long, val isLockdown: Boolean, val ssid: String?, val isIfaceCellular: Boolean, val isCaptivePortal: Boolean = false)

    // structured result of the dns-stage decision: transport id(s) and the local-blocklist
    // bypass flag, tagged with the branch that produced it. the live onQuery path converts
    // this to DNSOpts via [toNsOpts]; the connection simulator consumes it as-is
    data class DnsDecision(
        val uid: Int,
        val tid: Pair<String, String>,
        val domain: String,
        val noblock: Boolean,
        val isIfaceCellular: Boolean,
        val ssid: String?,
        val branch: String,
        val qtypeBlocked: Boolean = false
    )

    data class UpstreamAnswerParams(
        val scope: CoroutineScope,
        val id: String,
        val smm: DNSSummary,
        val rcvdDnsOpts: DNSOpts,
        val ipcsv: String,
        val isLockdown: Boolean,
        val isDeviceLocked: Boolean,
        val underlyingNetworks: ConnectionMonitor.UnderlyingNetworks?,
        val keyguardManager: KeyguardManager?,
        val connectivityManager: ConnectivityManager,
        val accessibilityServiceFunctional: Boolean,
        val isSpecialApp: suspend (Int) -> Boolean,
        val isConnectionMetered: (String) -> Boolean,
        val determineProxyDetails: suspend (ConnTrackerMetaData, Boolean, Boolean) -> Mark,
        val onDeviceLocked: (() -> Unit)?,
        val onAccessibilityFailure: (() -> Unit)?,
        val isCaptivePortal: Boolean
    )

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val netLogTracker by inject<NetLogTracker>()
    private val activityAggregator by inject<LogActivityAggregator>()

    private val rethinkUid = android.os.Process.myUid()

    // marks whether the uid is included in the dns requests. universal firewall rules are enforced
    // only when this flag is true, ensuring unknown app DNS requests are blocked and avoiding
    // issues when Android omits uid in dns requests
    private var isUidPresentInAnyDnsRequest: Boolean = false

    // used to store the dns-filter decision (firewall rule id) taken during the
    // upstream-answer evaluation, keyed by the dns query's flow id (fid). consumed when
    // the dns log row is persisted (onResponse -> processDnsLog) so the dns log ui can
    // show the exact block/allow reason; unconsumed entries expire on their own
    private val trackedDnsFilterReasons: Cache<String, String> =
        CacheBuilder.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(300))
            .build()

    // gomobile's DNSOpts() constructor invokes native seq code which cannot run under JVM
    // (Robolectric) tests; the constructor itself NPEs and cannot be intercepted by a shadow or
    // mockkConstructor. Indirection lets tests supply a relaxed mock while production uses the
    // real constructor.
    @Volatile
    private var dnsOptsFactory: () -> DNSOpts = ::DNSOpts

    fun setDnsOptsFactoryForTest(factory: () -> DNSOpts) {
        dnsOptsFactory = factory
    }

    private fun logv(msg: String) {
        Logger.v(LOG_TAG_VPN, "$TAG $msg")
    }

    private fun logd(msg: String) {
        Logger.d(LOG_TAG_VPN, "$TAG $msg")
    }

    private fun log(msg: String) {
        Logger.i(LOG_TAG_VPN, "$TAG $msg")
    }

    private fun logw(msg: String) {
        Logger.w(LOG_TAG_VPN, "$TAG $msg")
    }

    private fun loge(msg: String, e: Exception? = null) {
        Logger.e(LOG_TAG_VPN, "$TAG $msg", e)
    }

    private fun getUid(uidStr: String): Int {
        // TODO: if uid is received, then make sure Rethink uid always returns Default as transport
        var uid: Int = INVALID_UID
        try {
            uid = when (uidStr) {
                rethinkUid.toString() -> {
                    rethinkUid
                }

                else -> {
                    uidStr.toInt()
                }
            }
        } catch (_: NumberFormatException) {
            logw("onQuery: invalid uid: $uidStr, using default $uid")
        }
        return uid
    }

    suspend fun handleOnQuery(d: DnsParams): DNSOpts {
        return decideQuery(d).toNsOpts()
    }

    // dry-run entry: resolves the dns-stage decision for the given params without
    // constructing DNSOpts; shared by the live onQuery path and the connection simulator
    internal suspend fun decideQuery(d: DnsParams): DnsDecision {
        // uid: $uid
        logd("onQuery: rcvd params: $d")

        val uid = getUid(d.uidStr.orEmpty())
        val rinr = persistentState.routeRethinkInRethink
        val appMode = appConfig.getBraveMode()

        // when the active network is behind a captive portal, route every query to the system
        // dns and bypass local blocklists so the portal's sign-in flow can resolve; the mode
        // ends once the network is validated (captive-portal capability cleared), at which
        // point queries resume on the user-configured transport. firewall and qtype rules
        // continue to apply (see checkUserAllowedDnsQtypes below)
        if (d.isCaptivePortal) {
            logw("onQuery: captive portal active, routing ${d.fqdn} to system dns")
            val r = makeDecision(uid, Pair(Backend.System, ""), d.fqdn, true, d.isIfaceCellular, d.ssid, BRANCH_CAPTIVE_PORTAL)
            return checkUserAllowedDnsQtypes(r, d.qtype)
        }

        logd("onQuery: appConfig.getBraveMode for ${d.fqdn}")
        if (appMode.isDnsMode()) {
            val r = getTransportIdForDnsMode(uid, d.fqdn, rinr, d.isLockdown, d.isIfaceCellular, d.ssid)
            logd("onQuery: getTransportIdForDnsMode for ${d.fqdn}, decision: $r")
            return checkUserAllowedDnsQtypes(r, d.qtype)
        }

        if (appMode.isDnsFirewallMode()) {
            val r = getTransportIdForDnsFirewallMode(uid, d.fqdn, d.origin, rinr = rinr, isLockdown = d.isLockdown, isIfaceCellular = d.isIfaceCellular, ssid = d.ssid)
            logd("onQuery: getTransportIdForDnsFirewallMode for ${d.fqdn}, decision: $r")
            return checkUserAllowedDnsQtypes(r, d.qtype)
        }

        val smartDnsEnabled = appConfig.isSmartDnsEnabled()
        logd("onQuery: appConfig.isSmartDnsEnabled for ${d.fqdn}")
        val tid = if (smartDnsEnabled) {
            Backend.Plus
        } else {
            Backend.Preferred
        }
        val r = makeDecision(uid, Pair(tid, ""), d.fqdn, false, d.isIfaceCellular, d.ssid, BRANCH_FALLBACK) // should not reach here
        logd("onQuery: makeDecision (fallback) for ${d.fqdn}, decision: $r")
        loge("onQuery: unknown mode ${appMode}, ${d.fqdn}, returning $r")
        return checkUserAllowedDnsQtypes(r, d.qtype)
    }

    // function to decide the dns-stage decision on Dns only mode
    private suspend fun getTransportIdForDnsMode(uid: Int, fqdn: String, rinr: Boolean, isLockdown: Boolean, isIfaceCellular: Boolean, ssid: String?): DnsDecision {
        val tid = determineDnsTransportIdForDnsMode(isLockdown)
        logd("onQuery: determineDnsTransportIdForDnsMode for $fqdn")

        if (uid == rethinkUid && !rinr) {
            val r = makeDecision(uid, tid, fqdn, false, isIfaceCellular, ssid, BRANCH_RETHINK_UID)
            logd("onQuery: makeDecision(rethink) for $fqdn, return $r")
            return r
        }

        if (uid != INVALID_UID) {
            when (DomainRulesManager.getDomainRule(fqdn, uid)) {
                DomainRulesManager.Status.TRUST -> {
                    logd("onQuery: DomainRulesManager.getDomainRule($fqdn, uid=$uid) is trusted")
                    val r = makeDecision(uid, getTransportIdToBypass(tid, isLockdown), fqdn, true, isIfaceCellular, ssid, BRANCH_APP_TRUSTED)
                    logd("onQuery: makeDecision(app-trusted) for $fqdn, return $r")
                    return r
                }

                DomainRulesManager.Status.BLOCK -> {
                    logd("onQuery: DomainRulesManager.getDomainRule($fqdn, uid=$uid) is blocked")
                    val r = makeDecision(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid, BRANCH_APP_BLOCKED)
                    logd("onQuery: makeDecision(app-blocked) for $fqdn, return $r")
                    return r
                }

                else -> {
                    logd("onQuery: DomainRulesManager.getDomainRule($fqdn, uid=$uid) is none")
                }
            }
        }

        // check for global domain rules
        when (DomainRulesManager.getDomainRule(fqdn, UID_EVERYBODY)) {
            DomainRulesManager.Status.TRUST -> {
                logd("onQuery: DomainRulesManager.getDomainRule($fqdn, UID_EVERYBODY) is trusted")
                val r = makeDecision(uid, getTransportIdToBypass(tid, isLockdown), fqdn, true, isIfaceCellular, ssid, BRANCH_GLOBAL_TRUSTED)
                logd("onQuery: makeDecision(global-trusted) for $fqdn, return $r")
                return r
            }
            DomainRulesManager.Status.BLOCK -> {
                logd("onQuery: DomainRulesManager.getDomainRule($fqdn, UID_EVERYBODY) is blocked")
                val r = makeDecision(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid, BRANCH_GLOBAL_BLOCKED)
                logd("onQuery: makeDecision(global-blocked) for $fqdn, return $r")
                return r
            }
            else -> {
                logd("onQuery: DomainRulesManager.getDomainRule($fqdn, UID_EVERYBODY) is unknown")
            }
        }

        val r = makeDecision(uid, tid, fqdn, false, isIfaceCellular, ssid, BRANCH_DEFAULT)
        logd("onQuery: makeDecision for $fqdn, return $r")
        return r
    }

    // function to decide which transport id to return on DnsFirewall mode
    // Note: Now the trusted rules will be using the same transport id similar to the normal
    // queries, the decision to resolve the query will be based on the answer returned from the
    // upstream dns server. If the query is resolved by the upstream dns server, then there will be
    // no dns leak for the queries, but if it is blocked by upstream server then the unblock can be
    // done for that particular query in onUpstreamAnswer based on the fqdn and uid, so that we can
    // avoid the possibility of dns leak for the trusted queries / app which is set to bypass
    // dns + firewall rule
    private suspend fun getTransportIdForDnsFirewallMode(uid: Int, fqdn: String, origin: String?, rinr: Boolean, isLockdown: Boolean, isIfaceCellular: Boolean, ssid: String?): DnsDecision {
        val tid = determineDnsTransportIdForDFMode(uid, fqdn, origin, isLockdown, isIfaceCellular, ssid)
        val forceBypassLocalBlocklists = VpnController.isAppPaused() && isLockdown

        if (uid == rethinkUid && !rinr) {
            val opts = makeDecision(uid, tid, fqdn, true, isIfaceCellular, ssid, BRANCH_RETHINK_UID)
            logd("onQuery: makeDecision(rethink-df) for $fqdn")
            return opts
        }
        if (forceBypassLocalBlocklists) {
            val opts = makeDecision(uid, tid, fqdn, true, isIfaceCellular, ssid, BRANCH_FORCE_BYPASS)
            logd("onQuery: makeDecision(force-bypass) for $fqdn")
            return opts
        }

        // skip applying rules for system components (DNS and ANDROID) on Android 11 and below,
        // as we cannot determine the actual app from which the request originated when split dns
        // is disabled. However, when split dns is enabled, advanced dns filtering will also be
        // enabled on Android 11 and below, allowing Rethink to determine the actual requesting app.
        // In that case, we can apply the app-specific rules.
        val isProtectedUid = uid == AndroidUidConfig.ANDROID.uid || uid == AndroidUidConfig.DNS.uid || uid == rethinkUid
        if (isAtleastR() && isProtectedUid && !persistentState.splitDns) {
            val opts = makeDecision(uid, tid, fqdn, false, isIfaceCellular, ssid, BRANCH_PROTECTED_UID)
            logd("onQuery: makeDecision(protected-uid) for $fqdn")
            return opts
        }

        if (uid == INVALID_UID) {
            val anyAppBypass = FirewallManager.isAnyAppBypassesDns()
            logd("onQuery: FirewallManager.isAnyAppBypassesDns for $fqdn")
            if (anyAppBypass) {
                val opts = makeDecision(uid, transportIdsAlg(tid), fqdn, true, isIfaceCellular, ssid, BRANCH_NO_UID_BYPASS)
                logd("onQuery: makeDecision(no-uid-bypass) for $fqdn")
                return opts
            }
            val domainTrusted = DomainRulesManager.isDomainTrusted(fqdn)
            logd("onQuery: DomainRulesManager.isDomainTrusted for $fqdn")
            if (domainTrusted) {
                val opts = makeDecision(uid, transportIdsAlg(tid), fqdn, true, isIfaceCellular, ssid, BRANCH_NO_UID_TRUSTED)
                logd("onQuery: makeDecision(no-uid-trusted) for $fqdn")
                return opts
            }
            val globalBlock = DomainRulesManager.getAggregatedDomainRule(fqdn, UID_EVERYBODY).first == DomainRulesManager.Status.BLOCK
            logd("onQuery: getDomainRule(UID_EVERYBODY) for $fqdn")
            if (globalBlock) {
                val opts = makeDecision(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid, BRANCH_NO_UID_GLOBAL_BLOCK)
                logd("onQuery: makeDecision(no-uid-global-block) for $fqdn")
                return opts
            }
            if (isUidPresentInAnyDnsRequest && persistentState.getBlockUnknownConnections()) {
                val opts = makeDecision(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid, BRANCH_NO_UID_UNKNOWN_BLOCK)
                logd("onQuery: makeDecision(no-uid-unknown-block) for $fqdn")
                return opts
            }
            val opts = makeDecision(uid, tid, fqdn, false, isIfaceCellular, ssid, BRANCH_NO_UID_DEFAULT)
            logd("onQuery: makeDecision(no-uid-default) for $fqdn")
            return opts
        } else {
            if (!isUidPresentInAnyDnsRequest && uid != AndroidUidConfig.DNS.uid) {
                isUidPresentInAnyDnsRequest = true
                logv("$TAG; onQuery: uid present in dns request")
            }
            val isTempAllowed = FirewallManager.isTempAllowed(uid)
            logd("onQuery: FirewallManager.isTempAllowed for $fqdn")
            if (isTempAllowed) {
                val opts = makeDecision(uid, tid, fqdn, true, isIfaceCellular, ssid, BRANCH_TEMP_ALLOWED)
                logd("onQuery: makeDecision(temp-allowed) for $fqdn")
                return opts
            }
            val connectionStatus = FirewallManager.connectionStatus(uid)
            logd("onQuery: FirewallManager.connectionStatus for $fqdn")
            if (connectionStatus.blocked()) {
                val opts = makeDecision(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid, BRANCH_CONN_BLOCKED)
                logd("onQuery: makeDecision(conn-blocked) for $fqdn")
                return opts
            }

            val appStatus = FirewallManager.appStatus(uid)
            logd("onQuery: FirewallManager.appStatus for $fqdn")
            if (appStatus.bypassDnsFirewall()) {
                val opts = makeDecision(uid, tid, fqdn, true, isIfaceCellular, ssid, BRANCH_BYPASS_DNS)
                logd("onQuery: makeDecision(bypass-dns) for $fqdn")
                return opts
            }

            val appDomainRule = DomainRulesManager.getAggregatedDomainRule(fqdn, uid).first
            logd("onQuery: getDomainRule($fqdn, uid=$uid) for $fqdn")
            when (appDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    val opts = makeDecision(uid, tid, fqdn, true, isIfaceCellular, ssid, BRANCH_APP_TRUSTED_DF)
                    logd("onQuery: makeDecision(app-trusted-df) for $fqdn")
                    return opts
                }
                DomainRulesManager.Status.BLOCK -> {
                    val opts = makeDecision(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid, BRANCH_APP_BLOCKED_DF)
                    logd("onQuery: makeDecision(app-blocked-df) for $fqdn")
                    return opts
                }
                DomainRulesManager.Status.NONE -> {}
            }

            // global trusted domains need to send noBlock as true
            val skipGlobalRules = false
            if (!skipGlobalRules) {
                val globalDomainRule = DomainRulesManager.getAggregatedDomainRule(fqdn, UID_EVERYBODY).first
                logd("onQuery: getDomainRule($fqdn, UID_EVERYBODY) for $fqdn")
                when (globalDomainRule) {
                    DomainRulesManager.Status.TRUST -> {
                        val opts = makeDecision(uid, tid, fqdn, true, isIfaceCellular, ssid, BRANCH_GLOBAL_TRUSTED_DF)
                        logd("onQuery: makeDecision(global-trusted-df) for $fqdn")
                        return opts
                    }

                    DomainRulesManager.Status.BLOCK -> {} // taken care in onUpstreamAnswer

                    DomainRulesManager.Status.NONE -> {}
                }
            }

            val opts = makeDecision(uid, tid, fqdn, false, isIfaceCellular, ssid, BRANCH_DEFAULT_DF)
            logd("onQuery: makeDecision(default-df) for $fqdn")
            return opts
        }
    }

    private fun determineDnsTransportIdForDnsMode(isLockdown: Boolean): Pair<String, String> {
        val tid = if (appConfig.isSystemDns() || (VpnController.isAppPaused() && isLockdown)) {
            // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
            // as if the apps are excluded from vpn
            Backend.System
        } else if (appConfig.isSmartDnsEnabled()) {
            // if smart dns is enabled, use plus transport id
            Backend.Plus
        } else {
            Backend.Preferred
        }
        return Pair(tid, "")
    }

    private suspend fun determineDnsTransportIdForDFMode(uid: Int, domain: String, origin: String?, isLockdown: Boolean, isIfaceCellular: Boolean, ssid: String?): Pair<String, String> {
        val isOriginInternal = origin.equals(Backend.OriginInternal)
        val globalProxyLockdown = persistentState.wgGlobalLockdown
        if (uid == rethinkUid && isOriginInternal) {
            logd("(onQuery)rethink's uid using default")
            return Pair(Backend.Default, "")
        }

        val defaultTid =
            if (appConfig.isSystemDns() || (VpnController.isAppPaused() && isLockdown)) {
                // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
                // as if the apps are excluded from vpn
                Backend.System
            } else if (appConfig.isSmartDnsEnabled()) {
                // if smart dns is enabled, use plus transport id
                Backend.Plus
            } else {
                Backend.Preferred
            }

        val fallbackTid = if (globalProxyLockdown) {
            Backend.BlockAll
        } else {
            defaultTid
        }

        if ((uid == INVALID_UID || uid == AndroidUidConfig.ANDROID.uid || uid == AndroidUidConfig.DNS.uid) && !isOriginInternal) {
            val tid = if (FirewallManager.isAppExcludedFromProxy(uid)) {
                fallbackTid
            } else {
                val oneWgId = WireguardManager.getOneWireGuardProxyId()
                if (oneWgId != null) {
                    ID_WG_BASE + oneWgId
                } else {
                    defaultTid
                }
            }
            return if (persistentState.splitDns) {
                // in case of split dns, append Fixed to the tid when there is no uid
                // this synthesizes A/AAAA from a single fixed IP
                logd("(onQuery)split dns for uid: $uid, returning $tid,Fixed")
                Pair(tid, Backend.Fixed)
            } else {
                logd("(onQuery)no split dns for uid: $uid, returning $tid")
                Pair(tid, "")
            }
        } else {
            if (FirewallManager.isAppExcludedFromProxy(uid)) {
                logd("(onQuery)app excluded from proxy, returning $fallbackTid, global-proxy-lockdown? $globalProxyLockdown")
                return Pair(fallbackTid, "")
            }
            val oneWgId = WireguardManager.getOneWireGuardProxyId()
            if (oneWgId != null) {
                logd("(onQuery)one wg found, returning ${ID_WG_BASE + oneWgId}")
                return Pair(ID_WG_BASE + oneWgId, "")
            }
            if (!persistentState.splitDns) {
                logd("(onQuery)no split dns, using $defaultTid")
                return Pair(defaultTid, "")
            }
            val usesCellularNw = isIfaceCellular
            // only when there is an uid, we need to calculate wireguard ids
            // gives all the possible wgs for the app regardless of usesMobileNetwork
            val ssid = ssid ?: ""
            val rpnIds = if (RpnProxyManager.isRpnActive()) RpnProxyManager.getAllPossibleConfigIdsForApp(uid, ip = "", port = 0, domain, usesCellularNw, ssid) else emptyList()
            val defTid = if (rpnIds.isNotEmpty()) "" else defaultTid
            val wgIds = WireguardManager.getAllPossibleConfigIdsForApp(uid, ip = "", port = 0, domain, usesCellularNw, ssid, defTid)
            val updatedRpnIds = rpnIds.map { if (it == Backend.Block) Backend.BlockAll else it }.distinct()
            val updatedWgIds = wgIds.map { if (it == Backend.Block) Backend.BlockAll else it }.distinct()

            logv("(onQuery)wg ids($updatedWgIds), rpn id($updatedRpnIds) found for uid: $uid")
            val combinedIds = updatedRpnIds + updatedWgIds
            val wgRpnIds = combinedIds.joinToString(",")
            val rpnOrWgOrDefaultTid =
                if (combinedIds.any { it == Backend.BlockAll }) {
                    logd("(onQuery)proxy block rule present, return BlockAll for uid: $uid")
                    Backend.BlockAll
                } else if (combinedIds.isNotEmpty() && isAnyWgOrRpnDns(combinedIds)) {
                    logd("(onQuery-pid)wg/rpn ids($wgRpnIds) found for uid: $uid")
                    wgRpnIds
                } else {
                    logd("(onQuery)no wg/rpn found, return $fallbackTid, global-proxy-lockdown? $globalProxyLockdown")
                    fallbackTid
                }
            logd("(onQuery)dns ids($rpnOrWgOrDefaultTid) found for uid: $uid")
            return Pair(rpnOrWgOrDefaultTid, "")
        }
    }

    private fun isAlreadyBlocked(tidcsv: String): Boolean {
        if (tidcsv.isBlank()) return false
        return tidcsv.split(",").any { token ->
            val transport = token.split(":").firstOrNull()?.removePrefix(Backend.CT)
            transport == Backend.BlockAll || transport == Backend.Block
        }
    }

    private suspend fun checkUserAllowedDnsQtypes(result: DnsDecision, qtype: Long): DnsDecision {
        if (isAlreadyBlocked(result.tid.first)) {
            logd("onQuery: [checkUserAllowedDnsQtypes] already blocked for ${result.domain}")
            return result
        }
        val allowedTypes = persistentState.getAllowedDnsRecordTypesAsEnum()
        if (!ResourceRecordTypes.isQtypeAllowed(qtype.toInt(), allowedTypes)) {
            val r = makeDecision(result.uid, Pair(Backend.BlockAll, ""), result.domain, false, result.isIfaceCellular, result.ssid, BRANCH_QTYPE_BLOCK)
            logd("onQuery: [checkUserAllowedDnsQtypes] qtype blocked for ${result.domain}, qtype: $qtype")
            return r.copy(qtypeBlocked = true)
        } else {
            logd("onQuery: [checkUserAllowedDnsQtypes] qtype allowed for ${result.domain}")
            return result
        }
    }

    private suspend fun makeDecision(
        uid: Int,
        tid: Pair<String, String>,
        domain: String,
        bypassLocalBlocklists: Boolean = false,
        isIfaceCellular: Boolean,
        ssid: String?,
        branch: String
    ): DnsDecision {
        return DnsDecision(uid, tid, domain, bypassLocalBlocklists, isIfaceCellular, ssid, branch)
    }

    internal suspend fun decisionToDnsOpts(decision: DnsDecision): DNSOpts {
        return decision.toNsOpts()
    }

    private suspend fun DnsDecision.toNsOpts(): DNSOpts {
        val opts = DNSOpts()
        opts.uid = uid.toString()
        opts.ipcsv = "" // as of now, no suggested ips
        val tidCsv = tid.first.split(",").joinToString(",") { appendDnsCacheIfNeeded(it) }
        val secCsv = if (tid.second.isNotEmpty()) tid.second.split(",").joinToString(",") { appendDnsCacheIfNeeded(it) } else ""

        // if (uid == rethinkUid && !rinr) { opts.pidcsv = Backend.Base }
        // firestack is already expected to bypass proxies for rethink uid when rinr is false
        // for rethink over origin internal case, no matter what the proxyId is set, if the
        // transport is set to default in that case, the proxyId will be ignored in firestack
        val pidcsv = if (appConfig.getBraveMode().isDnsMode()) {
            Backend.Base
        } else {
            proxyIdForOnQuery(uid, domain, isIfaceCellular, ssid)
        }

        opts.tidcsv =  buildTidCsv(tidCsv, pidcsv)
        opts.tidseccsv = buildTidCsv(secCsv, pidcsv)
        opts.noblock = noblock
        log("onQuery: for $uid, $domain, returning $opts")
        return opts
    }

    private fun buildTidCsv(csv: String, pidcsv: String): String {
        if (csv.isBlank()) return ""
        val proxyIds = pidcsv.split(",")
        return csv.split(",").joinToString(",") { id ->
            val pids = if (isAnyUserSetProxy(id)) {
                proxyIds.filter(ProxyManager::isLocalProxy).joinToString(":")
            } else {
                proxyIds.joinToString(":")
            }

            "$id:$pids"
        }
    }

    private fun transportIdsAlg(preferredId: Pair<String, String>): Pair<String, String> {
        if (!persistentState.enableDnsAlg) {
            // if dns alg is not enabled, then return the preferred id as it is, no need to append
            // BlockFree, only alg needs ip address mapping which is why we use BlockFree transport
            return preferredId
        }
        if (persistentState.splitDns) {
            // case when splitDns is true, then tid will already be appended with Fixed
            // so no need to append BlockFree again
            return preferredId // ex: CT+Preferred,Fixed
        }
        // case when userPreferredId is Alg, then return BlockFree + tid
        // tid can be System / ProxyId / Preferred
        return if (isRethinkDnsEnabled()) {
            val tr1 = Backend.BlockFree
            val tr2 = preferredId.first // ideally, it should be Preferred
            val p = Pair(tr1, tr2)
            p
        } else {
            preferredId
        }
    }

    private fun isRethinkDnsEnabled(): Boolean {
        return appConfig.isRethinkDnsConnected() && !WireguardManager.oneWireGuardEnabled()
    }

    private fun canUseDnsCacheOnTransportId(userPreferredId: String): Boolean {
        // if userPreferredId is Dnsx.BlockAll, Alg then don't need to append CT
        return persistentState.enableDnsCache && userPreferredId != Backend.BlockAll
    }

    private suspend fun proxyIdForOnQuery(uid: Int, domain: String, isIfaceCellular: Boolean, ssid: String?): String {
        // NOTE: when the transport id is set to default/Auto then the proxyId are ignored
        // in tunnel. so no need to set the proxyId in that case, as of now we are not
        // handling the case when the transport id is set to default/Auto. the proxyId
        // calculation is done
        // TODO: check if the transport id is set to default/Auto, then return empty string/base

        val defaultProxy = Backend.Base

        val isGlobalProxyLockdown = persistentState.wgGlobalLockdown
        val fallbackProxy = if (isGlobalProxyLockdown) Backend.Block else defaultProxy
        val pkgName = FirewallManager.getAppInfoByUid(uid)?.packageName ?: ""

        // NOTE: no need to handle the rethink Uid and rinr in here, if the default is selected
        // as transport id then the proxy id is not considered in go. All other cases rethink
        // will be treated as any other app.

        // proxies are used only in dns-firewall mode
        if (!appConfig.getBraveMode().isDnsFirewallMode()) {
            logd("(onQuery-pid)not in dns-firewall mode")
            return defaultProxy
        }

        // user setting to disable proxy dns
        if (!persistentState.proxyDns) {
            logd("(onQuery-pid)proxyDns is disabled, return $fallbackProxy, global-proxy-lockdown? $isGlobalProxyLockdown")
            return fallbackProxy
        }

        if (FirewallManager.isAppExcludedFromProxy(uid)) {
            logd("(onQuery-pid) app excluded from proxy, return $fallbackProxy, global-proxy-lockdown? $isGlobalProxyLockdown")
            return fallbackProxy
        }

        if (appConfig.isDnsProxyActive()) {
            val endpoint = appConfig.getSelectedDnsProxyDetails()
            val app = endpoint?.proxyAppName
            if (!app.isNullOrEmpty() && app == pkgName) {
                logd("(onQuery-pid)proxy app: $app, return $fallbackProxy, global-proxy-lockdown? $isGlobalProxyLockdown")
                return fallbackProxy
            }
        }
        val usesCellularNw = isIfaceCellular
        val ssid = ssid ?: ""
        val rpnIds = if (RpnProxyManager.isRpnActive()) RpnProxyManager.getAllPossibleConfigIdsForApp(uid, "", 0, domain, usesCellularNw, ssid) else emptyList()

        val emptyOrDefault = if (isGlobalProxyLockdown) "" else defaultProxy
        return if (appConfig.isCustomSocks5Enabled()) {
            val id = proxyIdBuilder(ProxyManager.ID_S5_BASE, emptyOrDefault)
            logd("(onQuery-pid)customSocks5 enabled, return $rpnIds,$id, global-proxy-lockdown? $isGlobalProxyLockdown")
            rpnIds.plus(id).joinToString(":")
        } else if (appConfig.isCustomHttpProxyEnabled()) {
            val id = proxyIdBuilder(ProxyManager.ID_HTTP_BASE, emptyOrDefault)
            logd("(onQuery-pid)customHttp enabled, return $rpnIds,$id, global-proxy-lockdown? $isGlobalProxyLockdown")
            rpnIds.plus(id).joinToString(":")
        } else if (appConfig.isOrbotProxyEnabled()) {
            val id = proxyIdBuilder(ProxyManager.ID_ORBOT_BASE, emptyOrDefault)
            logd("(onQuery-pid)orbot enabled, return $rpnIds,$id, global-proxy-lockdown? $isGlobalProxyLockdown")
            rpnIds.plus(id).joinToString(":")
        } else {
            // if the enabled wireguard is catchall-wireguard, then return wireguard id
            val ids = WireguardManager.getAllPossibleConfigIdsForApp(
                uid,
                ip = "",
                port = 0,
                domain,
                usesCellularNw,
                ssid,
                if (rpnIds.isNotEmpty()) "" else emptyOrDefault // no need add default proxy in case if rpn id's available
            )
            val proxyIds = rpnIds + ids
            if (proxyIds.any { it == Backend.Block || it == Backend.BlockAll }) {
                return Backend.Block
            }
            val noWgOrRpnTransport = !isAnyWgOrRpnDns(proxyIds)
            if (noWgOrRpnTransport) {
                logd("(onQuery-pid)no wg/rpn found, return $fallbackProxy [${proxyIds}], global-proxy-lockdown? $isGlobalProxyLockdown for uid: $uid")
                fallbackProxy
            } else {
                logd("(onQuery-pid)wg ids(${proxyIds}) found for uid: $uid")
                proxyIds.joinToString(":")
            }
        }
    }

    private fun proxyIdBuilder(id: String, emptyOrDefault: String): String {
        return buildString {
            append(id)
            if (emptyOrDefault.isNotEmpty()) {
                append(':')
                append(emptyOrDefault)
            }
        }
    }


    private fun isAnyWgOrRpnDns(tid: List<String>): Boolean {
        return tid.any { it.startsWith(ID_WG_BASE, ignoreCase = true) || it.startsWith(Backend.RpnWin, ignoreCase = true) }
    }

    private fun appendDnsCacheIfNeeded(id: String): String {
        return if (canUseDnsCacheOnTransportId(id) && !id.startsWith(Backend.CT)) {
            Backend.CT + id
        } else {
            id
        }
    }

    private fun getTransportIdToBypass(id: Pair<String, String>, isLockdown: Boolean): Pair<String, String> {
        // add already used transport id's as secondary transport id (both tid, tidsec)
        val secTransport = listOf(id.first, id.second)
            .filter { it.isNotBlank() }
            .joinToString(",")

        // if split-dns is off (ui will be disabled to the user, and we will not respect this
        // setting), the setting will default to BlockFreeDnsMode.AUTO in 12+ and will use
        // BlockFreeDnsMode.FALLBACK on 12 and below.
        val blockFreeMode = if (persistentState.splitDns) {
            BlockFreeDnsModeBottomSheet.BlockFreeDnsMode.fromMode(persistentState.blockFreeDnsMode)
        } else {
            if (isAtleastR()) {
                BlockFreeDnsModeBottomSheet.BlockFreeDnsMode.AUTO
            } else {
                BlockFreeDnsModeBottomSheet.BlockFreeDnsMode.FALLBACK
            }
        }
        when (blockFreeMode) {
            BlockFreeDnsModeBottomSheet.BlockFreeDnsMode.AUTO -> {
                // decide based on the splitDns, if splitDns is enabled do not process
                // trusted queries with blockfree to avoid dns leak, if splitDns is disabled,
                // then use blockfree for trusted queries to bypass blocks
                if (persistentState.splitDns) {
                    // send same id, no changes needed if its blocked then it will stay blocked
                    // in this case, even the trusted queries will be blocked, but it prevents
                    // dns leaks which is more desired behavior when split dns /prevent dns leaks
                    // is enabled.
                    logd("getTransportIdToBypass: splitDns & auto, returning same id $secTransport")
                    return id
                } else if (persistentState.preventDnsLeaks) {
                    logd("getTransportIdToBypass: preventDns & auto, returning same id $secTransport")
                    return id
                } else {
                    // there are certain cases where the blockfree won't be available, so better
                    // to use default transport in AUTO mode
                    // chances are there that preferred transport is already in secondary
                    logd("getTransportIdToBypass: auto, returning default with prev ids as secondary $secTransport")
                    return Pair(Backend.Default, secTransport)
                }
            }

            BlockFreeDnsModeBottomSheet.BlockFreeDnsMode.GLOBAL -> {
                // user selected dns regardless of split dns setting
                // there maybe multiple transports when split dns is enabled, so add the already
                // used transport id as secondary transport id
                logd("getTransportIdToBypass: global, returning preferred with prev ids as secondary $secTransport")
                val defaultTid =
                    if (appConfig.isSystemDns() || (VpnController.isAppPaused() && isLockdown)) {
                        // in vpn-lockdown mode+appPause , use system dns if the app is paused to mimic
                        // as if the apps are excluded from vpn
                        Backend.System
                    } else if (appConfig.isSmartDnsEnabled()) {
                        // if smart dns is enabled, use plus transport id
                        Backend.Plus
                    } else {
                        Backend.Preferred
                    }
                return Pair(defaultTid, secTransport)
            }

            BlockFreeDnsModeBottomSheet.BlockFreeDnsMode.FALLBACK -> {
                // always use the fallback dns as block free transport
                logd("getTransportIdToBypass: fallback, returning default with prev ids as secondary $secTransport")
                return Pair(Backend.Default, secTransport)
            }
        }
    }

    fun handleOnResponse(summary: DNSSummary?, onRegionUpdate: (String?) -> Unit) {
        if (summary == null) {
            log("received null summary for dns")
            return
        }
        logd("onResponse: $summary, rethinkUid: $rethinkUid")
        Logger.vv(
            LOG_TAG_VPN,
            "onResponse obj: " +
                    "id: ${summary.id}, " +
                    "type: ${summary.type}, " +
                    "uid: ${summary.uid}, " +
                    "lat: ${summary.latency}, " +
                    "qname: ${summary.qName}, " +
                    "qtype: ${summary.qType}, " +
                    "targets: ${summary.targets}, " +
                    "cached: ${summary.cached}, " +
                    "rdata: ${summary.rData}, " +
                    "rcode: ${summary.rCode}, " +
                    "rttl: ${summary.rTtl}, " +
                    "server: ${summary.server}, " +
                    "pid: ${summary.pid}, " +
                    "rpid: ${summary.rpid}, " +
                    "status: ${summary.status}, " +
                    "blocklists: ${summary.blocklists}, " +
                    "blockedTarget: ${summary.blockedTarget}, " +
                    "upstreamBlocks: ${summary.upstreamBlocks}, " +
                    "do: ${summary.`do`}, " +
                    "ad: ${summary.ad}, " +
                    "msg: ${summary.msg}, " +
                    "region: ${summary.region}"
        )
        if (!DEBUG) {
            if (summary.id.contains(Backend.Fixed)) {
                return
            }
        }
        aggregateDnsActivity(summary)
        netLogTracker.processDnsLog(summary)
        onRegionUpdate(summary.region)
    }

    /**
     * Arrival-time activity aggregation, owned at this caller level; the
     * trackers stay persistence-only. Kept behind the same gates as
     * processDnsLog so the in-memory grid and the dns-log table stay in sync.
     */
    private fun aggregateDnsActivity(summary: DNSSummary) {
        if (!persistentState.logsEnabled) return

        activityAggregator.recordOnArrival(
            LogActivityEvent(
                summary.start,
                LogActivitySource.DNS,
                DnsLogTracker.isBlockedDnsAnswer(
                    transportId = summary.id,
                    statusCode = summary.status,
                    response = summary.rData ?: "",
                    qType = summary.qType,
                    blocklists = summary.blocklists ?: "",
                    upstreamBlock = summary.upstreamBlocks
                )
            )
        )
    }

    /**
     * Records the firewall rule that decided the fate of a dns query during the
     * upstream-answer evaluation. The put overwrites any prior entry for the same
     * fid, so a decision can never be duplicated for one dns query.
     */
    fun trackDnsFilterReason(fid: String, ruleId: String) {
        if (fid.isEmpty() || ruleId.isEmpty()) return

        trackedDnsFilterReasons.put(fid, ruleId)
        Logger.vv(LOG_TAG_VPN, "$TAG trackedDnsFilterReasons: fid: $fid, rule: $ruleId, cache-size: ${trackedDnsFilterReasons.size()}")
    }

    /**
     * Consumes (returns and removes) the recorded decision for the given fid so the
     * same reason is never applied to more than one dns log entry.
     */
    fun consumeDnsFilterReason(fid: String): String {
        if (fid.isEmpty()) return ""

        val reason = trackedDnsFilterReasons.getIfPresent(fid) ?: return ""
        trackedDnsFilterReasons.invalidate(fid)
        return reason
    }

    // drops all recorded decisions; called on vpn teardown so the next session
    // does not inherit stale fid -> rule entries
    fun clearTrackedDnsFilterReasons() {
        trackedDnsFilterReasons.invalidateAll()
    }

    suspend fun handleOnUpstreamAnswer(params: UpstreamAnswerParams): DNSOpts {
        val decision = decideUpstreamAnswer(params)
        logd("onUpstreamAnswer: decision: $decision")
        return when (decision.action) {
            UpstreamAction.KEEP_PREVIOUS -> dnsOptsFactory()
            UpstreamAction.BLOCK -> {
                if (decision.rule != null) {
                    trackDnsFilterReason(params.smm.fid, decision.rule.id)
                }
                dnsOptsFactory().apply {
                    tidcsv = Backend.BlockAll
                    noblock = false
                }
            }
            UpstreamAction.BYPASS -> {
                if (decision.rule != null) {
                    trackDnsFilterReason(params.smm.fid, decision.rule.id)
                }
                dnsOptsFactory().apply {
                    if (decision.sameTid) {
                        tidcsv = decision.tid.first
                        tidseccsv = decision.tid.second
                    } else {
                        val tid = decision.tid.first.split(",").joinToString(",") { appendDnsCacheIfNeeded(it) }
                        val tidSec = if (decision.tid.second.isEmpty()) "" else decision.tid.second.split(",")
                            .joinToString(",") { appendDnsCacheIfNeeded(it) }
                        tidcsv = buildTidCsv(tid, decision.pidcsv)
                        tidseccsv = buildTidCsv(tidSec, decision.pidcsv)
                    }
                    noblock = true
                }
            }
        }
    }

    // dry-run entry: resolves the upstream-answer stage decision for the given params
    // without constructing DNSOpts or recording filter reasons; shared by the live
    // onUpstreamAnswer path and the connection simulator
    internal suspend fun decideUpstreamAnswer(params: UpstreamAnswerParams, dryRun: Boolean = false): UpstreamDecision {
        // There are scenarios that need to be handled before this is safe.
        //
        // For example, an app may have a port-based allow rule while also being
        // configured to use Isolate. At this stage, only the destination IP address
        // is known; the destination port is not available until the connection is
        // established. Applying the Isolate rule here would incorrectly block the
        // app, even though a matching port-based allow rule may exist.
        //
        // We should defer rule evaluation or skip it here whenever a port-based rule
        // exists for either the app or the universal firewall rules.

        val uid = try {
            params.smm.uid.toInt()
        } catch (e: NumberFormatException) {
            Logger.e(
                LOG_TAG_VPN,
                "onUpstreamAnswer: invalid uid ${params.smm.uid}, error: ${e.message}"
            )
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_INVALID_UID)
        }

        // keep the previous DNS options for captive portals, using Dnsx.System and noBlock is
        // set to true
        if (params.isCaptivePortal) {
            Logger.i(LOG_TAG_VPN, "onUpstreamAnswer: captive portal, returning prev DNSOpts()")
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_CAPTIVE_PORTAL)
        }

        val isAnyPortRule = IpRulesManager.isPortRuleSetForIp(params.ipcsv, uid) ||
            IpRulesManager.isPortRuleSetForIp(params.ipcsv, UID_EVERYBODY)
        if (isAnyPortRule) {
            Logger.i(LOG_TAG_VPN, "onUpstreamAnswer: port-based rule exists for ip: ${params.ipcsv}, returning prev DNSOpts()")
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_PORT_RULE)
        }
        Logger.vv(
            LOG_TAG_VPN,
            "onUpstreamAnswer: init, ${params.id}, sum: ${params.smm}, ipcsv: ${params.ipcsv}, opts: ${params.rcvdDnsOpts}"
        )
        if (params.ipcsv.isEmpty()) {
            Logger.w(LOG_TAG_VPN, "onUpstreamAnswer: empty ipcsv, returning prev DNSOpts()")
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_EMPTY_IPCSV)
        }
        if (appConfig.getBraveMode().isDnsMode()) {
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_DNS_MODE)
        }

        val isTransportDnsFixed =
            params.rcvdDnsOpts.tidcsv.contains(Backend.Fixed) || params.rcvdDnsOpts.tidseccsv.contains(Backend.Fixed)
        if (isTransportDnsFixed) {
            Logger.i(LOG_TAG_VPN, "onUpstreamAnswer: fixed transport, returning prev DNSOpts()")
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_FIXED_TRANSPORT)
        }

        if (uid == INVALID_UID && persistentState.blockDnsForUnknownApp) {
            val id = Pair(params.rcvdDnsOpts.tidcsv, params.rcvdDnsOpts.tidseccsv)
            Logger.vv(
                LOG_TAG_VPN,
                "onUpstreamAnswer: block dns for unknown app, original tid: $id, bypass tid: [${Backend.BlockAll}, ], ipcsv: ${params.ipcsv}"
            )
            return UpstreamDecision(UpstreamAction.BLOCK, BRANCH_UA_UNKNOWN_APP)
        }

        val firstDestIp = params.ipcsv.split(",").firstOrNull()
        if (firstDestIp.isNullOrEmpty()) {
            Logger.e(
                LOG_TAG_VPN,
                "onUpstreamAnswer: empty rdata for ${params.smm.qName}, ipcsv: ${params.ipcsv} uid: $uid"
            )
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_EMPTY_IPCSV)
        }
        val userId = FirewallManager.userId(uid)
        val srcIp = ""
        val srcPort = 0
        val dstPort = 0
        val protocol = KnownPorts.DNS_PORT
        val blocklists = params.smm.blocklists
        val connId = ""
        val isSplApp = params.isSpecialApp(uid)
        val domain = params.smm.qName.orEmpty()
        val anyRealIpBlocked = false
        val connType =
            if (params.isConnectionMetered(firstDestIp)) {
                ConnectionTracker.ConnType.METERED
            } else {
                ConnectionTracker.ConnType.UNMETERED
            }

        val rinr = persistentState.routeRethinkInRethink
        val connInfo = ConnTrackerMetaData(
            uid,
            userId,
            srcIp,
            srcPort,
            params.ipcsv,
            dstPort,
            System.currentTimeMillis(),
            false,
            "",
            "",
            blocklists.orEmpty(),
            protocol,
            domain,
            connId,
            connType.value
        )
        logd("onUpstreamAnswer: connInfo: $connInfo, domain: $domain, anyRealIpBlocked: $anyRealIpBlocked, isSplApp: $isSplApp, rinr: $rinr")
        val firewallParams = TunFirewallManager.FirewallParameters(
            scope = params.scope,
            connInfo = connInfo,
            domains = domain,
            anyRealIpBlocked = anyRealIpBlocked,
            isSplApp = isSplApp,
            rinr = rinr,
            isAlg = true, // see rules for multiple available ips
            forUpstreamAnswer = true,
            isDeviceLocked = params.isDeviceLocked,
            onDeviceLocked = params.onDeviceLocked,
            underlyingNetworks = params.underlyingNetworks,
            isLockdown = params.isLockdown,
            isAppPaused = VpnController.isAppPaused(),
            accessibilityServiceFunctional = params.accessibilityServiceFunctional,
            onAccessibilityFailure = params.onAccessibilityFailure,
            keyguardManager = params.keyguardManager,
            connectivityManager = params.connectivityManager,
            dryRun = dryRun
        )
        val rule = TunFirewallManager.firewall(firewallParams)
        val blocked = FirewallRuleset.ground(rule)
        if (blocked) {
            logd("onUpstreamAnswer: blocked by firewall, rule: $rule, connInfo: $connInfo, ipcsv: ${params.ipcsv}")
            return UpstreamDecision(UpstreamAction.BLOCK, BRANCH_UA_FIREWALL_BLOCKED, rule)
        }
        // special case: in case of bypass rule for domain or ip, if the app is set to isolate
        // that should be treated as allowed rule instead of bypass rule, so no need to use
        // the blockfree / default transport. Avoid sending the same transport id back,
        // instead send DNSOpts() object. Sending the same transport id again will make the tun
        // to start the resolve process again which is not needed
        val appFirewallStatus = FirewallManager.appStatus(uid)
        val isAppIsolated = appFirewallStatus.isIsolate()
        val isDmnOrIpTrusted = rule == FirewallRuleset.RULE2F || rule == FirewallRuleset.RULE2B
        if (isAppIsolated && isDmnOrIpTrusted) {
            logd("onUpstreamAnswer: app is isolate and domain/ip is trusted, treat as allowed, rule: $rule, connInfo: $connInfo, ipcsv: ${params.ipcsv}")
            return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_ISOLATE_TRUSTED, rule)
        }
        val isBypass = FirewallRuleset.isDnsBypassRule(rule)
        if (isBypass) {
            // use the same pid for non-blocking case, as the decision is already made in onQuery
            // and the same pid will be used in tunnel for the upstream query
            // For tid, based on the useFallbackDnsToBypass settings, the blockfree, default
            // will be used, else the same tid from onQuery will be used
            val id = Pair(params.rcvdDnsOpts.tidcsv, params.rcvdDnsOpts.tidseccsv)
            val tidPair = getTransportIdToBypass(id, params.isLockdown)
            if (tidPair == id) {
                logd("onUpstreamAnswer: no bypass transport needed, rule: $rule")
                return UpstreamDecision(UpstreamAction.BYPASS, BRANCH_UA_BYPASS, rule, tidPair, sameTid = true)
            }
            val mark = params.determineProxyDetails(
                connInfo,
                persistentState.routeRethinkInRethink,
                true
            )
            Logger.vv(
                LOG_TAG_VPN,
                "onUpstreamAnswer: bypass rule: $rule, original tid: $id, bypass tid: [${tidPair.first}], [${tidPair.second}], pid: ${mark.pidcsv} connInfo: $connInfo, ipcsv: ${params.ipcsv}"
            )
            return UpstreamDecision(UpstreamAction.BYPASS, BRANCH_UA_BYPASS, rule, tidPair, mark.pidcsv)
        }

        if (DEBUG) logd("onUpstreamAnswer: ${params.smm}, ipcsv: ${params.ipcsv}")
        logd("onUpstreamAnswer: no action needed, default DNSOpts")
        return UpstreamDecision(UpstreamAction.KEEP_PREVIOUS, BRANCH_UA_NO_ACTION)
    }
}
