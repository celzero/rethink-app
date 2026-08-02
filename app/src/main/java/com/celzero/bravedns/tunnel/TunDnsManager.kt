package com.celzero.bravedns.tunnel

import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.ProxyManager.isAnyUserSetProxy
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.bottomsheet.BlockFreeDnsModeBottomSheet
import com.celzero.bravedns.util.AndroidUidConfig
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

object TunDnsManager: KoinComponent {
    private const val TAG = "TunDnsManager"
    data class DnsParams(val origin: String, val uidStr: String?, val fqdn: String, val qtype: Long, val isLockdown: Boolean, val ssid: String?, val isIfaceCellular: Boolean)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private val rethinkUid = android.os.Process.myUid()

    // marks whether the uid is included in the dns requests. universal firewall rules are enforced
    // only when this flag is true, ensuring unknown app DNS requests are blocked and avoiding
    // issues when Android omits uid in dns requests
    private var isUidPresentInAnyDnsRequest: Boolean = false

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
        if (!DEBUG) return handleOnQueryInternal(d)

        val startNanos = System.nanoTime()
        try {
            return handleOnQueryInternal(d)
        } finally {
            // measure the execution time of handleOnQuery logic on every call;
            // gated behind DEBUG to avoid overhead on the DNS hot path in release builds
            if (DEBUG) {
                val elapsedNanos = System.nanoTime() - startNanos
                logd("onQuery: handleOnQuery exec time: ${elapsedNanos / 1_000_000.0}ms ($elapsedNanos ns) for ${d.fqdn}")
            }
        }
    }

    private suspend fun handleOnQueryInternal(d: DnsParams): DNSOpts {
        // uid: $uid
        logd("onQuery: rcvd params: $d")
        val uidStr = d.uidStr.orEmpty()
        var result: DNSOpts?

        val uid = getUid(uidStr)
        val rinr = persistentState.routeRethinkInRethink
        val appMode = appConfig.getBraveMode()
        logd("onQuery: appConfig.getBraveMode for ${d.fqdn}")
        if (appMode.isDnsMode()) {
            result = getTransportIdForDnsMode(uid, d.fqdn, rinr, d.isLockdown, d.isIfaceCellular, d.ssid)
            logd("onQuery: getTransportIdForDnsMode for ${d.fqdn}, dnsx: $result")
            val r = checkUserAllowedDnsQtypes(result, uid, d.fqdn, d.qtype, d.isIfaceCellular, d.ssid)
            logd("onQuery: checkUserAllowedDnsQtypes (Dns) for ${d.fqdn}")
            return r
        }

        if (appMode.isDnsFirewallMode()) {
            result = getTransportIdForDnsFirewallMode(uid, d.fqdn, d.origin, rinr = rinr, d.isLockdown, d.isIfaceCellular, d.ssid)
            logd("onQuery: getTransportIdForDnsFirewallMode for ${d.fqdn}, dnsx: $result")
            val r = checkUserAllowedDnsQtypes(result, uid, d.fqdn, d.qtype, d.isIfaceCellular, d.ssid)
            logd("onQuery: checkUserAllowedDnsQtypes (Dns+Firewall) for ${d.fqdn}")
            return r
        }

        val smartDnsEnabled = appConfig.isSmartDnsEnabled()
        logd("onQuery: appConfig.isSmartDnsEnabled for ${d.fqdn}")
        val tid = if (smartDnsEnabled) {
            Backend.Plus
        } else {
            Backend.Preferred
        }
        result = makeNsOpts(uid, Pair(tid, ""), d.fqdn, false, d.isIfaceCellular, d.ssid) // should not reach here
        logd("onQuery: makeNsOpts (fallback) for ${d.fqdn}, dnsx: $result")
        loge("onQuery: unknown mode ${appMode}, ${d.fqdn}, returning $result")
        val r = checkUserAllowedDnsQtypes(result, uid, d.fqdn, d.qtype, d.isIfaceCellular, d.ssid)
        logd("onQuery: checkUserAllowedDnsQtypes (fallback) for ${d.fqdn}")
        return r
    }

    // function to decide which transport id to return on Dns only mode
    private suspend fun getTransportIdForDnsMode(uid: Int, fqdn: String, rinr: Boolean, isLockdown: Boolean, isIfaceCellular: Boolean, ssid: String?): DNSOpts {
        val tid = determineDnsTransportIdForDnsMode(isLockdown)
        logd("onQuery: determineDnsTransportIdForDnsMode for $fqdn")

        if (uid == rethinkUid && !rinr) {
            val r = makeNsOpts(uid, tid, fqdn, false, isIfaceCellular, ssid)
            logd("onQuery: makeNsOpts(rethink) for $fqdn, return $r")
            return r
        }

        if (uid != INVALID_UID) {
            when (DomainRulesManager.getDomainRule(fqdn, uid)) {
                DomainRulesManager.Status.TRUST -> {
                    logd("onQuery: DomainRulesManager.getDomainRule($fqdn, uid=$uid) is trusted")
                    val r = makeNsOpts(uid, getTransportIdToBypass(tid, isLockdown), fqdn, true, isIfaceCellular, ssid)
                    logd("onQuery: makeNsOpts(app-trusted) for $fqdn, return $r")
                    return r
                }

                DomainRulesManager.Status.BLOCK -> {
                    logd("onQuery: DomainRulesManager.getDomainRule($fqdn, uid=$uid) is blocked")
                    val r = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                    logd("onQuery: makeNsOpts(app-blocked) for $fqdn, return $r")
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
                val r = makeNsOpts(uid, getTransportIdToBypass(tid, isLockdown), fqdn, true, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(global-trusted) for $fqdn, return $r")
                return r
            }
            DomainRulesManager.Status.BLOCK -> {
                logd("onQuery: DomainRulesManager.getDomainRule($fqdn, UID_EVERYBODY) is blocked")
                val r = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(global-blocked) for $fqdn, return $r")
                return r
            }
            else -> {
                logd("onQuery: DomainRulesManager.getDomainRule($fqdn, UID_EVERYBODY) is unknown")
            }
        }

        val r = makeNsOpts(uid, tid, fqdn, false, isIfaceCellular, ssid)
        logd("onQuery: makeNsOpts($r) for $fqdn, return $r")
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
    private suspend fun getTransportIdForDnsFirewallMode(uid: Int, fqdn: String, origin: String?, rinr: Boolean, isLockdown: Boolean, isIfaceCellular: Boolean, ssid: String?): DNSOpts {
        val tid = determineDnsTransportIdForDFMode(uid, fqdn, origin, isLockdown, isIfaceCellular, ssid)
        val forceBypassLocalBlocklists = VpnController.isAppPaused() && isLockdown

        if (uid == rethinkUid && !rinr) {
            val opts = makeNsOpts(uid, tid, fqdn, true, isIfaceCellular, ssid)
            logd("onQuery: makeNsOpts(rethink-df) for $fqdn")
            return opts
        }
        if (forceBypassLocalBlocklists) {
            val opts = makeNsOpts(uid, tid, fqdn, true, isIfaceCellular, ssid)
            logd("onQuery: makeNsOpts(force-bypass) for $fqdn")
            return opts
        }

        if (uid == INVALID_UID) {
            val anyAppBypass = FirewallManager.isAnyAppBypassesDns()
            logd("onQuery: FirewallManager.isAnyAppBypassesDns for $fqdn")
            if (anyAppBypass) {
                val opts = makeNsOpts(uid, transportIdsAlg(tid), fqdn, true, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(no-uid-bypass) for $fqdn")
                return opts
            }
            val domainTrusted = DomainRulesManager.isDomainTrusted(fqdn)
            logd("onQuery: DomainRulesManager.isDomainTrusted for $fqdn")
            if (domainTrusted) {
                val opts = makeNsOpts(uid, transportIdsAlg(tid), fqdn, true, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(no-uid-trusted) for $fqdn")
                return opts
            }
            val globalBlock = getDomainRule(fqdn, UID_EVERYBODY).first == DomainRulesManager.Status.BLOCK
            logd("onQuery: getDomainRule(UID_EVERYBODY) for $fqdn")
            if (globalBlock) {
                val opts = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(no-uid-global-block) for $fqdn")
                return opts
            }
            if (isUidPresentInAnyDnsRequest && persistentState.getBlockUnknownConnections()) {
                val opts = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(no-uid-unknown-block) for $fqdn")
                return opts
            }
            val opts = makeNsOpts(uid, tid, fqdn, false, isIfaceCellular, ssid)
            logd("onQuery: makeNsOpts(no-uid-default) for $fqdn")
            return opts
        } else {
            if (!isUidPresentInAnyDnsRequest && uid != AndroidUidConfig.DNS.uid) {
                isUidPresentInAnyDnsRequest = true
                logv("$TAG; onQuery: uid present in dns request")
            }
            val isTempAllowed = FirewallManager.isTempAllowed(uid)
            logd("onQuery: FirewallManager.isTempAllowed for $fqdn")
            if (isTempAllowed) {
                val opts = makeNsOpts(uid, tid, fqdn, true, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(temp-allowed) for $fqdn")
                return opts
            }
            val connectionStatus = FirewallManager.connectionStatus(uid)
            logd("onQuery: FirewallManager.connectionStatus for $fqdn")
            if (connectionStatus.blocked()) {
                val opts = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(conn-blocked) for $fqdn")
                return opts
            }

            val appStatus = FirewallManager.appStatus(uid)
            logd("onQuery: FirewallManager.appStatus for $fqdn")
            if (appStatus.bypassDnsFirewall()) {
                val opts = makeNsOpts(uid, tid, fqdn, true, isIfaceCellular, ssid)
                logd("onQuery: makeNsOpts(bypass-dns) for $fqdn")
                return opts
            }

            val appDomainRule = getDomainRule(fqdn, uid).first
            logd("onQuery: getDomainRule($fqdn, uid=$uid) for $fqdn")
            when (appDomainRule) {
                DomainRulesManager.Status.TRUST -> {
                    val opts = makeNsOpts(uid, tid, fqdn, true, isIfaceCellular, ssid)
                    logd("onQuery: makeNsOpts(app-trusted-df) for $fqdn")
                    return opts
                }
                DomainRulesManager.Status.BLOCK -> {
                    val opts = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                    logd("onQuery: makeNsOpts(app-blocked-df) for $fqdn")
                    return opts
                }
                DomainRulesManager.Status.NONE -> {}
            }

            // disable global rules check, see #onUpstreamAnswer() for more details.
            val skipGlobalRules = true
            if (skipGlobalRules) {
                val globalDomainRule = getDomainRule(fqdn, UID_EVERYBODY).first
                logd("onQuery: getDomainRule($fqdn, UID_EVERYBODY) for $fqdn")
                when (globalDomainRule) {
                    DomainRulesManager.Status.TRUST -> {
                        val opts = makeNsOpts(uid, tid, fqdn, true, isIfaceCellular, ssid)
                        logd("onQuery: makeNsOpts(global-trusted-df) for $fqdn")
                        return opts
                    }

                    DomainRulesManager.Status.BLOCK -> {
                        val opts = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
                        logd("onQuery: makeNsOpts(global-blocked-df) for $fqdn")
                        return opts
                    }

                    DomainRulesManager.Status.NONE -> {}
                }
            }

            val opts = makeNsOpts(uid, tid, fqdn, false, isIfaceCellular, ssid)
            logd("onQuery: makeNsOpts(default-df) for $fqdn")
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
            val wgIds = WireguardManager.getAllPossibleConfigIdsForApp(uid, ip = "", port = 0, domain, usesCellularNw, ssid, defaultTid)
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

    private suspend fun checkUserAllowedDnsQtypes(result: DNSOpts, uid: Int, fqdn: String, qtype: Long, isIfaceCellular: Boolean, ssid: String?): DNSOpts {
        if (isAlreadyBlocked(result.tidcsv)) {
            logd("onQuery: [checkUserAllowedDnsQtypes] already blocked for $fqdn")
            return result
        }
        val allowedTypes = persistentState.getAllowedDnsRecordTypesAsEnum()
        if (!ResourceRecordTypes.isQtypeAllowed(qtype.toInt(), allowedTypes)) {
            val r = makeNsOpts(uid, Pair(Backend.BlockAll, ""), fqdn, false, isIfaceCellular, ssid)
            logd("onQuery: [checkUserAllowedDnsQtypes] qtype blocked for $fqdn, qtype: $qtype")
            return r
        } else {
            logd("onQuery: [checkUserAllowedDnsQtypes] qtype allowed for $fqdn")
            return result
        }
    }

    private suspend fun makeNsOpts(
        uid: Int,
        tid: Pair<String, String>,
        domain: String,
        bypassLocalBlocklists: Boolean = false,
        isIfaceCellular: Boolean,
        ssid: String?
    ): DNSOpts {
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
        opts.noblock = bypassLocalBlocklists
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

    private fun getDomainRule(domain: String?, uid: Int): Pair<DomainRulesManager.Status, String?> {
        if (domain.isNullOrEmpty()) {
            return Pair(DomainRulesManager.Status.NONE, "")
        }

        val domains = if (isAtleastR()) {
            // on Android R and above, go will give the first domain as accurate domain so
            // no need to check further domains
            val d = domain.lowercase(Locale.getDefault()).split(",").firstOrNull()
            if (d.isNullOrEmpty()) return Pair(DomainRulesManager.Status.NONE, "")
            listOf(d)
        } else {
            domain.lowercase(Locale.getDefault()).split(",")
        }

        if (domains.isEmpty()) {
            return Pair(DomainRulesManager.Status.NONE, "")
        }

        var hasTrustedDomain = false
        var trustedDomain = ""
        var hasBlockedDomain = false
        var blockedDomain = ""
        for (d in domains) {
            val status = DomainRulesManager.status(d, uid)
            when (status) {
                DomainRulesManager.Status.TRUST -> {
                    hasTrustedDomain = true
                    trustedDomain = d
                }

                DomainRulesManager.Status.BLOCK -> {
                    hasBlockedDomain = true
                    blockedDomain = d
                }

                else -> {
                    // no-op
                }
            }
        }

        when {
            hasTrustedDomain -> return Pair(DomainRulesManager.Status.TRUST, trustedDomain)
            hasBlockedDomain -> return Pair(DomainRulesManager.Status.BLOCK, blockedDomain)
        }

        return Pair(DomainRulesManager.Status.NONE, "")
    }
}
