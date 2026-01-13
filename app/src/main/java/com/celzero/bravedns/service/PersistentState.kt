/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.ui.activity.AntiCensorshipActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastR
import hu.autsoft.krate.SimpleKrate
import hu.autsoft.krate.booleanPref
import hu.autsoft.krate.default.withDefault
import hu.autsoft.krate.intPref
import hu.autsoft.krate.longPref
import hu.autsoft.krate.stringPref
import org.koin.core.component.KoinComponent

class PersistentState(context: Context) : SimpleKrate(context), KoinComponent {
    companion object {
        const val BRAVE_MODE = "brave_mode"
        const val BACKGROUND_MODE = "background_mode"
        const val ALLOW_BYPASS = "allow_bypass"
        const val LOCAL_BLOCK_LIST = "enable_local_list"
        const val LOCAL_BLOCK_LIST_STAMP = "local_block_list_stamp"
        const val LOCAL_BLOCK_LIST_UPDATE = "local_block_list_downloaded_time"
        const val PROXY_TYPE = "proxy_proxytype"
        const val NETWORK = "add_all_networks_to_vpn"
        const val NOTIFICATION_ACTION = "notification_action"
        const val DNS_CHANGE = "connected_dns_name"
        const val INTERNET_PROTOCOL = "internet_protocol"
        const val PROTOCOL_TRANSLATION = "protocol_translation"
        const val DEFAULT_DNS_SERVER = "default_dns_query"
        const val PCAP_MODE = "pcap_mode"
        const val PCAP_FILE_PATH = "pcap_file_path"
        const val REMOTE_BLOCKLIST_UPDATE = "remote_block_list_downloaded_time"
        const val DNS_ALG = "dns_alg"
        const val APP_VERSION = "app_version"
        const val PRIVATE_IPS = "private_ips"
        const val RETHINK_IN_RETHINK = "route_rethink_in_rethink"
        const val PREVENT_DNS_LEAKS = "prevent_dns_leaks"
        const val CONNECTIVITY_CHECKS = "connectivity_check"
        const val NOTIFICATION_PERMISSION = "notification_permission_request"
        const val EXCLUDE_APPS_IN_PROXY = "exclude_apps_in_proxy"
        const val BIOMETRIC_AUTH = "biometric_authentication"
        const val ANTI_CENSORSHIP_TYPE = "dial_strategy"
        const val RETRY_STRATEGY = "retry_strategy"
        const val ENDPOINT_INDEPENDENCE = "endpoint_independence"
        const val TCP_KEEP_ALIVE = "tcp_keep_alive"
        const val USE_SYSTEM_DNS_FOR_UNDELEGATED_DOMAINS = "use_system_dns_for_undelegated_domains"
        const val NETWORK_ENGINE_EXPERIMENTAL = "network_engine_experimental"
        const val USE_RPN = "rpn_state"
        const val RPN_MODE = "rpn_mode"
        const val DIAL_TIMEOUT_SEC = "dial_timeout_sec"
        const val AUTO_DIALS_PARALLEL = "auto_dials_parallel"
        const val STALL_ON_NO_NETWORK = "fail_open_on_no_network"
        const val TUN_NETWORK_POLICY = "tun_network_handling_policy"
        const val USE_MAX_MTU = "use_max_mtu"
        const val SET_VPN_BUILDER_TO_METERED = "set_vpn_builder_to_metered"
        const val PANIC_RANDOM = "panic_random"

        // SE Proxy for Anti-Censorship
        const val AUTO_PROXY_ENABLED = "auto_proxy_enabled"

        // Custom LAN IP settings for VPN tunnel
        const val CUSTOM_LAN_MODE_IPS_CHANGED = "custom_lan_mode_ip_changed"

        const val FIREWALL_BUBBLE = "pref_firewall_bubble_enabled"
    }

    // when vpn is started by the user, this is set to true; set to false when user stops
    // the vpn. In case vpn crashes, this value remains true, which is expected.
    private var _vpnEnabled by booleanPref("enabled").withDefault<Boolean>(false)

    // OOBE (out-of-the-box experience) screens are shown if the user
    // launches the app for the very first time (after install or post clear-data)
    var firstTimeLaunch by booleanPref("is_first_time_launch").withDefault<Boolean>(true)

    // One among AppConfig.BraveMode enum; 2's default, which is BraveMode.DNS_FIREWAL
    var braveMode by intPref("brave_mode").withDefault<Int>(AppConfig.BraveMode.DNS_FIREWALL.mode)

    // enable / disable logging dns and tcp/udp connections to db
    var logsEnabled by booleanPref("local_logs").withDefault<Boolean>(true)

    // the last-set app version; useful to detect an update where the current
    // app version is going to be greater than the one stored, and so, update flow
    // can be triggered accordingly, if any,
    var appVersion by intPref("app_version").withDefault<Int>(0)

    // Last known time when app update checks were done (successful?)
    var lastAppUpdateCheck by longPref("app_update_last_check").withDefault<Long>(0)

    // total blocklists set by the user for RethinkDNS+ (server-side dns blocking)
    private var numberOfRemoteBlocklists by intPref("remote_block_list_count").withDefault<Int>(0)

    // total blocklists set by the user (on-device dns blocking)
    var numberOfLocalBlocklists by intPref("local_block_list_count").withDefault<Int>(0)

    // whether all udp connection except dns must be dropped
    private var _udpBlocked by
        booleanPref("block_udp_traffic_other_than_dns").withDefault<Boolean>(false)

    // user chosen blocklists stored custom dictionary indexed in base64
    var localBlocklistStamp by
        stringPref("local_block_list_stamp")
            .withDefault<String>(if (Utilities.isHeadlessFlavour()) "1:YAYBACABEDAgAA==" else "")

    // whether to drop packets when the source app originating the reqs couldn't be determined
    private var _blockUnknownConnections by
        booleanPref("block_unknown_connections").withDefault<Boolean>(false)

    // whether user has enable on-device blocklists
    var blocklistEnabled by
        booleanPref("enable_local_list").withDefault<Boolean>(Utilities.isHeadlessFlavour())

    // the version (which is a unix timestamp) of the current rethinkdns+ remote blocklist files
    var remoteBlocklistTimestamp by
        longPref("remote_block_list_downloaded_time").withDefault<Long>(INIT_TIME_MS)

    // the version (which is a unix timestamp) of the current on-device blocklist files
    var localBlocklistTimestamp by longPref("local_block_list_downloaded_time").withDefault<Long>(0)

    // user set http proxy port
    var httpProxyPort by intPref("http_proxy_port").withDefault<Int>(INVALID_PORT)

    // user set http proxy ip / hostname
    var httpProxyHostAddress by
        stringPref("http_proxy_ipaddress").withDefault<String>("http://127.0.0.1:8118")

    // whether apps subject to the RethinkDNS VPN tunnel can bypass the tunnel on-demand
    // default: false
    var allowBypass by booleanPref("allow_bypass").withDefault<Boolean>(false)

    // user set among AppConfig.DnsType enum; RETHINK_REMOTE is default which is Rethink-DoH
    var dnsType by
        intPref("dns_type")
            .withDefault<Int>(
                if (!Utilities.isHeadlessFlavour()) AppConfig.DnsType.RETHINK_REMOTE.type
                else AppConfig.DnsType.SYSTEM_DNS.type
            )

    // whether the app must attempt to startup on reboot if it was running before shutdown
    var prefAutoStartBootUp by booleanPref("auto_start_on_boot").withDefault<Boolean>(true)

    // user set preference whether firewall should block all connections when device is locked
    private var _blockWhenDeviceLocked by booleanPref("screen_state").withDefault<Boolean>(false)

    // whether to block connections from apps not in the foreground
    private var _blockAppWhenBackground by
        booleanPref("background_mode").withDefault<Boolean>(false)

    // whether to check for app updates once-a-week (on website / play-store builds)
    var checkForAppUpdate by booleanPref("check_for_app_update").withDefault<Boolean>(true)

    // last connected dns label name and url
    var connectedDnsName by
        stringPref("connected_dns_name")
            .withDefault<String>(context.getString(R.string.default_dns_name))

    // the current light/dark theme; 0's the default which is "Set by System"
    var theme by intPref("app_theme").withDefault<Int>(0)

    // user selected notification action type, ref: Constants#NOTIFICATION_ACTION_STOP
    var notificationActionType by intPref("notification_action").withDefault<Int>(0)

    // add all networks (say, both wifi / mobile) with internet capability to the vpn tunnel
    var useMultipleNetworks by booleanPref("add_all_networks_to_vpn").withDefault<Boolean>(false)

    // user selected proxy type (e.g., http, socks5)
    var proxyType by
        stringPref("proxy_proxytype").withDefault<String>(AppConfig.ProxyType.NONE.name)

    // user selected proxy provider, as of now two providers (custom, orbot)
    var proxyProvider by
        stringPref("proxy_proxyprovider").withDefault<String>(AppConfig.ProxyProvider.NONE.name)

    // the last collected app exit info's timestamp
    var lastAppExitInfoTimestamp by longPref("prev_trace_timestamp").withDefault<Long>(INIT_TIME_MS)

    // fetch fav icons for domains in dns request
    var fetchFavIcon by
        booleanPref("fav_icon_enabled").withDefault<Boolean>(!Utilities.isFdroidFlavour())

    // whether to show "what's new" chip on the homescreen, usually
    // shown after a update and until the user dismisses it
    var showWhatsNewChip by booleanPref("show_whats_new_chip").withDefault<Boolean>(true)

    // block dns which are not resolved by app
    private var _disallowDnsBypass by booleanPref("disallow_dns_bypass").withDefault<Boolean>(false)

    // trap all packets on port 53 to be sent to a dns endpoint or just the packets sent to vpn's
    // dns-ip
    var preventDnsLeaks by booleanPref("prevent_dns_leaks").withDefault<Boolean>(true)

    // block all newly installed apps
    private var _blockNewlyInstalledApp by booleanPref("block_new_app").withDefault<Boolean>(false)

    // user setting to use custom download manager or android's default download manager
    // default: true, i.e., use in-build download manager, as we see lot of failures with
    // android's download manager because of the blocking nature of the app
    var useCustomDownloadManager by
        booleanPref("use_custom_download_managet").withDefault<Boolean>(true)

    // custom download manager's last generated id
    var customDownloaderLastGeneratedId by
        longPref("custom_downloader_last_generated_id").withDefault<Long>(0)

    // android download manager's active download ids (comma-separated)
    var androidDownloadManagerIds by
        stringPref("android_download_manager_ids").withDefault<String>("")

    // local timestamp for which the update is available
    var newestLocalBlocklistTimestamp by
        longPref("local_blocklist_update_ts").withDefault<Long>(INIT_TIME_MS)

    // remote timestamp for which the update is available
    var newestRemoteBlocklistTimestamp by
        longPref("remote_blocklist_update_ts").withDefault<Long>(INIT_TIME_MS)

    // auto-check for blocklist update periodically (once in a day)
    var periodicallyCheckBlocklistUpdate by
        booleanPref("check_blocklist_update").withDefault<Boolean>(false)

    // user-preferred Internet Protocol type, default IPv4
    var internetProtocolType by
        intPref(INTERNET_PROTOCOL)
            .withDefault<Int>(
                if (!Utilities.isHeadlessFlavour()) InternetProtocol.IPv4.id
                else InternetProtocol.IPv46.id
            )

    // user-preferred 6to4 protocol translation, on IPv6 mode (default: PTMODEAUTO)
    var protocolTranslationType by booleanPref(PROTOCOL_TRANSLATION).withDefault<Boolean>(false)

    // filter IPv6 compatible IPv4 address in custom ips
    var filterIpv4inIpv6 by booleanPref("filter_ip4_ipv6").withDefault<Boolean>(true)

    // universal firewall settings to block all http connections
    private var _blockHttpConnections by
        booleanPref("block_http_connections").withDefault<Boolean>(false)

    // universal firewall settings to block all metered connections
    private var _blockMeteredConnections by
        booleanPref("block_metered_connections").withDefault<Boolean>(false)

    // universal firewall settings to lockdown all apps
    private var _universalLockdown by booleanPref("universal_lockdown").withDefault<Boolean>(false)

    // notification permission request (Android 13 ana above)
    var shouldRequestNotificationPermission by
        booleanPref("notification_permission_request").withDefault<Boolean>(true)

    // make notification persistent (Android 13 and above), default false
    var persistentNotification by booleanPref("persistent_notification").withDefault<Boolean>(false)

    // biometric authentication TODO: remove this
    var biometricAuth by booleanPref("biometric_authentication").withDefault<Boolean>(false)

    // bio-metric authentication type
    var biometricAuthType by intPref("biometric_authentication_type").withDefault<Int>(0)

    // enable dns alg
    var enableDnsAlg by booleanPref("dns_alg").withDefault<Boolean>(false)

    // default dns url
    var defaultDnsUrl by stringPref("default_dns_query").withDefault<String>(Constants.DEFAULT_DNS_LIST[1].url)

    // packet capture type
    var pcapMode by intPref("pcap_mode").withDefault<Int>(PcapMode.NONE.id)

    // packet capture file path
    var pcapFilePath by stringPref("pcap_file_path").withDefault<String>("")

    // dns caching in tunnel
    var enableDnsCache by booleanPref("dns_cache").withDefault<Boolean>(true)

    // private ips, default false (route private ips to tunnel)
    var privateIps by booleanPref("private_ips").withDefault<Boolean>(false)

    // biometric last auth time
    var biometricAuthTime by longPref("biometric_auth_time").withDefault<Long>(INIT_TIME_MS)

    // go logger level, default 3 -> info
    var goLoggerLevel by longPref("go_logger_level").withDefault<Long>(3)

    // firewall bubble feature toggle
    var firewallBubbleEnabled by booleanPref("pref_firewall_bubble_enabled").withDefault<Boolean>(false)

    // previous data usage check timestamp
    var prevDataUsageCheck by longPref("prev_data_usage_check").withDefault<Long>(INIT_TIME_MS)

    // route rethink in rethink
    var routeRethinkInRethink by booleanPref("route_rethink_in_rethink").withDefault<Boolean>(false)

    // perform connectivity checks
    var connectivityChecks by
        booleanPref("connectivity_check").withDefault<Boolean>(Utilities.isPlayStoreFlavour())

    // proxy dns requests over proxy
    var proxyDns by booleanPref("proxy_dns").withDefault<Boolean>(true)

    // exclude apps which are configured in proxy (socks5, http, dns proxy)
    var excludeAppsInProxy by booleanPref("exclude_apps_in_proxy").withDefault<Boolean>(true)

    var pingv4Ips by stringPref("ping_ipv4_ips").withDefault<String>(Constants.ip4probes.joinToString(","))

    var pingv6Ips by stringPref("ping_ipv6_ips").withDefault<String>(Constants.ip6probes.joinToString(","))

    var pingv4Url by stringPref("ping_ipv4_url").withDefault<String>(Constants.urlV4probes.joinToString(","))

    var pingv6Url by stringPref("ping_ipv6_url").withDefault<String>(Constants.urlV6probes.joinToString(","))


    // anti-censorship type (auto, split_tls, split_tcp, desync)
    var dialStrategy by intPref("dial_strategy").withDefault<Int>(AntiCensorshipActivity.DialStrategies.SPLIT_AUTO.mode)

    // retry strategy type (before split, after split, never)
    var retryStrategy by intPref("retry_strategy").withDefault<Int>(AntiCensorshipActivity.RetryStrategies.RETRY_AFTER_SPLIT.mode)

    // bypass blocking in dns level, decision is made in flow() (see BraveVPNService#flow)
    var bypassBlockInDns by booleanPref("bypass_block_in_dns").withDefault<Boolean>(false)

    // randomize listen port for advanced wireguard configuration, default false
    // restart of tunnel when wireguard is enabled is required to randomize the port to work properly
    // this is not a user facing option, but a developer option
    var randomizeListenPort by booleanPref("randomize_listen_port").withDefault<Boolean>(true)

    // endpoint independent mapping/filtering
    var endpointIndependence by booleanPref("endpoint_independence").withDefault<Boolean>(false)

    var tcpKeepAlive by booleanPref("tcp_keep_alive").withDefault<Boolean>(false)

    // enable split dns, default on Android R and above, as we can identify app which is sending dns
    var splitDns by booleanPref("split_dns").withDefault<Boolean>(isAtleastR())

    // use system dns for undelegatedDomains
    var useSystemDnsForUndelegatedDomains by booleanPref("use_system_dns_for_undelegated_domains").withDefault<Boolean>(false)

    // different modes the rpn proxy can function, see enum RpnMode
    var rpnMode by intPref("rpn_mode").withDefault<Int>(1)

    // current rpn state, see enum RpnState
    var rpnState by intPref("rpn_state").withDefault<Int>(RpnProxyManager.RpnState.DISABLED.id)

    // subscribe product id for the current user, empty string if not subscribed
    var rpnProductId by stringPref("rpn_product_id").withDefault<String>("")

    var nwEngExperimentalFeatures by booleanPref("network_engine_experimental").withDefault<Boolean>(false)

    var dialTimeoutSec by intPref("dial_timeout_sec").withDefault<Int>(0)

    // treat only mobile data as metered
    var treatOnlyMobileNetworkAsMetered by booleanPref("treat_only_mobile_nw_as_metered").withDefault<Boolean>(false)

    var showConfettiOnRPlus by booleanPref("show_confetti_on_rplus").withDefault<Boolean>(true)

    var autoDialsParallel by booleanPref("auto_dials_parallel").withDefault<Boolean>(false)

    // user setting whether to download ip info for the given ip address
    var downloadIpInfo by booleanPref("download_ip_info").withDefault<Boolean>(Utilities.isPlayStoreFlavour())

    // user setting to allow only added packages can trigger the app
    var appTriggerPackages by stringPref("app_trigger_packages").withDefault<String>("")

    // last key rotation time
    var pipKeyRotationTime by longPref("pip_key_rotation_time").withDefault<Long>(INIT_TIME_MS)

    // perform auto or manual network connectivity checks
    var performAutoNetworkConnectivityChecks by booleanPref("perform_auto_network_connectivity_checks").withDefault<Boolean>(true)

    // stall on no network
    // TODO: add routes as normal but do not send fd to netstack
    // repopulateTrackedNetworks also fails open see isAnyNwValidated
    var stallOnNoNetwork by booleanPref("fail_open_on_no_network").withDefault<Boolean>(false)

    // last grace period reminder time, when rethinkdns+ is enabled and user is cancelled/expired
    // this is used to show a reminder to the user to renew the subscription with grace period
    var lastGracePeriodReminderTime by longPref("last_grace_period_reminder_time").withDefault<Long>(INIT_TIME_MS)

    var newSettings by stringPref("new_settings").withDefault<String>("")
    var newSettingsSeen by stringPref("new_settings_seen").withDefault<String>("")
    var appUpdateTimeTs by longPref("app_update_time_ts").withDefault<Long>(INIT_TIME_MS)

    // 0 - auto, 1 - relaxed, 2 - aggressive, 3 - fixed
    var vpnBuilderPolicy by intPref("tun_network_handling_policy").withDefault<Int>(0)

    // whether to use default dns for trusted ips and domains
    var useFallbackDnsToBypass by booleanPref("use_fallback_dns_to_bypass").withDefault<Boolean>(true)

    // Firebase error reporting enabled (only for play and website variants)
    var firebaseErrorReportingEnabled by booleanPref("firebase_error_reporting").withDefault<Boolean>(Utilities.isPlayStoreFlavour())

    // setting to enable/disable tombstone apps feature
    var tombstoneApps by booleanPref("tombstone_apps").withDefault<Boolean>(false)

    // Token for Firebase userId
    var firebaseUserToken by stringPref("firebase_user_token").withDefault("")
    var firebaseUserTokenTimestamp by longPref("firebase_user_token_timestamp").withDefault(0L)

    // experimental feature to use max mtu
    var useMaxMtu by booleanPref("use_max_mtu").withDefault<Boolean>(false)

    // set vpn builder to metered/unmetered
    var setVpnBuilderToMetered by booleanPref("set_vpn_builder_to_metered").withDefault<Boolean>(false)

    // debug settings, panic random
    var panicRandom by booleanPref("panic_random").withDefault<Boolean>(false)

    // universal rule, block all non A & AAAA dns responses
    private var _blockOtherDnsRecordTypes by booleanPref("block_non_ip_dns_responses").withDefault<Boolean>(false)

    // global lockdown for wireguard proxy
    var wgGlobalLockdown by booleanPref("wg_global_lockdown").withDefault<Boolean>(false)

    var orbotConnectionStatus: MutableLiveData<Boolean> = MutableLiveData()
    var vpnEnabledLiveData: MutableLiveData<Boolean> = MutableLiveData()
    var universalRulesCount: MutableLiveData<Int> = MutableLiveData()
    private var proxyStatus: MutableLiveData<Int> = MutableLiveData()

    // data class to store dnscrypt relay details
    data class DnsCryptRelayDetails(val relay: DnsCryptRelayEndpoint, val added: Boolean)

    var dnsCryptRelays: MutableLiveData<DnsCryptRelayDetails> = MutableLiveData()

    var remoteBlocklistCount: MutableLiveData<Int> = MutableLiveData()

    fun setVpnEnabled(isOn: Boolean) {
        vpnEnabledLiveData.postValue(isOn)
        _vpnEnabled = isOn
    }

    fun getVpnEnabled(): Boolean {
        return _vpnEnabled
    }

    fun setRemoteBlocklistCount(c: Int) {
        numberOfRemoteBlocklists = c
        remoteBlocklistCount.postValue(c)
    }

    fun getRemoteBlocklistCount(): Int {
        return numberOfRemoteBlocklists
    }

    private fun setUniversalRulesCount() {
        val list =
            listOf(
                _blockHttpConnections,
                _blockMeteredConnections,
                _universalLockdown,
                _blockNewlyInstalledApp,
                _disallowDnsBypass,
                _udpBlocked,
                _blockUnknownConnections,
                _blockAppWhenBackground,
                _blockWhenDeviceLocked
            )
        universalRulesCount.postValue(list.count { it })
    }

    fun getUniversalRulesCount(): Int {
        if (universalRulesCount.value == null) setUniversalRulesCount()
        return universalRulesCount.value ?: 0
    }

    fun setBlockHttpConnections(b: Boolean) {
        _blockHttpConnections = b
        setUniversalRulesCount()
    }

    fun getBlockHttpConnections(): Boolean {
        return _blockHttpConnections
    }

    fun setBlockMeteredConnections(b: Boolean) {
        _blockMeteredConnections = b
        setUniversalRulesCount()
    }

    fun getBlockMeteredConnections(): Boolean {
        return _blockMeteredConnections
    }

    fun setUniversalLockdown(b: Boolean) {
        _universalLockdown = b
        setUniversalRulesCount()
    }

    fun getUniversalLockdown(): Boolean {
        return _universalLockdown
    }

    fun setBlockNewlyInstalledApp(b: Boolean) {
        _blockNewlyInstalledApp = b
        setUniversalRulesCount()
    }

    fun getBlockNewlyInstalledApp(): Boolean {
        return _blockNewlyInstalledApp
    }

    fun setDisallowDnsBypass(b: Boolean) {
        _disallowDnsBypass = b
        setUniversalRulesCount()
    }

    fun getDisallowDnsBypass(): Boolean {
        return _disallowDnsBypass
    }

    fun setUdpBlocked(b: Boolean) {
        _udpBlocked = b
        setUniversalRulesCount()
    }

    fun getUdpBlocked(): Boolean {
        return _udpBlocked
    }

    fun setBlockUnknownConnections(b: Boolean) {
        _blockUnknownConnections = b
        setUniversalRulesCount()
    }

    fun getBlockUnknownConnections(): Boolean {
        return _blockUnknownConnections
    }

    fun setBlockAppWhenBackground(b: Boolean) {
        _blockAppWhenBackground = b
        setUniversalRulesCount()
    }

    fun getBlockAppWhenBackground(): Boolean {
        return _blockAppWhenBackground
    }

    fun setBlockWhenDeviceLocked(b: Boolean) {
        _blockWhenDeviceLocked = b
        setUniversalRulesCount()
    }

    fun getBlockWhenDeviceLocked(): Boolean {
        return _blockWhenDeviceLocked
    }

    fun getProxyStatus(): MutableLiveData<Int> {
        return updateProxyStatus()
    }

    fun updateProxyStatus(): MutableLiveData<Int> {
        val status =
            when (AppConfig.ProxyProvider.getProxyProvider(proxyProvider)) {
                AppConfig.ProxyProvider.WIREGUARD -> {
                    R.string.lbl_wireguard
                }
                AppConfig.ProxyProvider.ORBOT -> {
                    R.string.orbot
                }
                AppConfig.ProxyProvider.TCP -> {
                    R.string.orbot_socks5
                }
                AppConfig.ProxyProvider.CUSTOM -> {
                    val type = AppConfig.ProxyType.of(proxyType)
                    when (type) {
                        AppConfig.ProxyType.SOCKS5 -> {
                            R.string.lbl_socks5
                        }
                        AppConfig.ProxyType.HTTP -> {
                            R.string.lbl_http
                        }
                        else -> {
                            R.string.lbl_http_socks5
                        }
                    }
                }
                else -> {
                    -1
                }
            }
        proxyStatus.postValue(status)
        return proxyStatus
    }

    /**
     * Enable settings which are dependent on stability program participation.
     * Currently, only Firebase error reporting is enabled here.
     */
    fun enableStabilityDependentSettings(context: Context) {
        // Skip for fdroid flavor
        if (Utilities.isFdroidFlavour()) {
            return
        }

        // Enable Firebase error reporting for play and website variants
        if (!firebaseErrorReportingEnabled) {
            firebaseErrorReportingEnabled = true
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.stability_program_toast), Toast.LENGTH_LONG).show()
            }
        }

        return
    }

    // Allowed DNS record types (stored as comma-separated enum names)
    // Default: A, AAAA, CNAME, HTTPS, SVCB, IPSECKEY
    internal var allowedDnsRecordTypesString by stringPref("allowed_dns_record_types")
        .withDefault(setOf(
            ResourceRecordTypes.A.name,
            ResourceRecordTypes.AAAA.name,
            ResourceRecordTypes.CNAME.name,
            ResourceRecordTypes.HTTPS.name,
            ResourceRecordTypes.SVCB.name,
            ResourceRecordTypes.IPSECKEY.name
        ).joinToString(","))

    // Auto mode for DNS record types - when enabled, all record types are allowed
    // Default: true (Auto mode ON)
    var dnsRecordTypesAutoMode by booleanPref("dns_record_types_auto_mode")
        .withDefault(true)

    fun getAllowedDnsRecordTypes(): Set<String> {
        // If Auto mode is enabled, return all record types
        if (dnsRecordTypesAutoMode) {
            return ResourceRecordTypes.entries
                .filter { it != ResourceRecordTypes.UNKNOWN }
                .map { it.name }
                .toSet()
        }

        val value = allowedDnsRecordTypesString
        return if (value.isEmpty()) {
            emptySet()
        } else {
            value.split(",").filter { it.isNotEmpty() }.toSet()
        }
    }

    fun setAllowedDnsRecordTypes(types: Set<String>) {
        allowedDnsRecordTypesString = types.joinToString(",")
    }

    fun getAllowedDnsRecordTypesAsEnum(): Set<ResourceRecordTypes> {
        return getAllowedDnsRecordTypes().mapNotNull { name ->
            try {
                ResourceRecordTypes.valueOf(name)
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toSet()
    }

    // SE Proxy for Anti-Censorship
    var autoProxyEnabled by booleanPref(AUTO_PROXY_ENABLED).withDefault<Boolean>(false)

    // Custom LAN IP configuration mode: 0 = AUTO (default), 1 = MANUAL
    var customLanIpMode by booleanPref("custom_lan_ip_mode").withDefault<Boolean>(false)

    // Custom LAN IPs. Store IP and prefix together as a single value (e.g., "10.111.222.1/24").
    // Empty string means: use defaults.
    var customLanGatewayIpv4 by stringPref("custom_lan_gateway_ipv4").withDefault<String>("10.111.222.1/24")
    var customLanGatewayIpv6 by stringPref("custom_lan_gateway_ipv6").withDefault<String>("fd66:f83a:c650::1/120")

    var customLanRouterIpv4 by stringPref("custom_lan_router_ipv4").withDefault<String>("10.111.222.2/32")
    var customLanRouterIpv6 by stringPref("custom_lan_router_ipv6").withDefault<String>("fd66:f83a:c650::2/128")

    var customLanDnsIpv4 by stringPref("custom_lan_dns_ipv4").withDefault<String>("10.111.222.3/32")
    var customLanDnsIpv6 by stringPref("custom_lan_dns_ipv6").withDefault<String>("fd66:f83a:c650::3/128")

    var customModeOrIpChanged by booleanPref("custom_lan_mode_ip_changed").withDefault<Boolean>(false)
}
