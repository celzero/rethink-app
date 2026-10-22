/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.dns

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable row snapshot. Lazy rows never retain or mutate a recycled ViewHolder. */
internal enum class DnsEndpointFlagStyle { Standard, Proxy }

internal data class DnsEndpointRowState(
    val id: Int, val name: String, val selected: Boolean, val deletable: Boolean,
    val address: String, val pollStatus: Boolean = false, val relay: Boolean = false,
    val description: (suspend () -> String?)?, val flag: suspend () -> String?,
    val onSelect: () -> Unit, val onInfo: () -> Unit,
    // Static text belongs to the bind itself and does not dispatch an asynchronous read.
    val initialDescription: String? = null,
    val flagStyle: DnsEndpointFlagStyle = DnsEndpointFlagStyle.Standard,
)

internal data class DnsEndpointDescriptionState(
    val initial: String? = null, val read: (suspend () -> String?)? = null,
    val poll: Boolean = false
)

/** The adapter chose this branch once in displayDetails, before any asynchronous status read. */
internal fun bindDnsEndpointDescription(
    selected: Boolean, canPoll: () -> Boolean, selectedLabel: () -> String,
    status: suspend () -> String?
): DnsEndpointDescriptionState {
    if (!selected) return DnsEndpointDescriptionState()
    return if (canPoll()) DnsEndpointDescriptionState(read = status, poll = true)
    else DnsEndpointDescriptionState(initial = selectedLabel())
}

/** Adapter-owned actions moved without changing the AppConfig/service entry points. */
internal class DnsEndpointPresenter(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val appConfig: AppConfig,
    private val persistentState: PersistentState? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    fun doh(e: DoHEndpoint) = row(e.id, secureName(e.dohName, e.isSecure), e.isSelected, e.isDeletable(), e.dohURL,
        select = { e.isSelected = true; appConfig.handleDoHChanges(e) },
        delete = { appConfig.deleteDohEndpoint(e.id) },
        deleteTitle = R.string.doh_custom_url_remove_dialog_title,
        deleteMessage = R.string.doh_custom_url_remove_dialog_message,
        success = R.string.doh_custom_url_remove_success, noDimDelete = true,
        info = {
            // Preserve the original adapter's branch precedence when bootstrap IPs are present.
            val ips = e.dohIp
            val message = e.dohURL + if (!ips.isNullOrEmpty()) "\n\n$ips" else "" + "\n\n" + dnsDesc(e.dohExplanation)
            metadata(e.dohName, message, e.dohURL, noDim = true)
        })

    fun dot(e: DoTEndpoint) = row(e.id, secureName(e.name, e.isSecure), e.isSelected, e.isDeletable(), e.url,
        select = { e.isSelected = true; appConfig.handleDoTChanges(e) },
        delete = { appConfig.deleteDoTEndpoint(e.id) },
        deleteTitle = R.string.dot_custom_url_remove_dialog_title,
        deleteMessage = R.string.dot_custom_url_remove_dialog_message,
        success = R.string.doh_custom_url_remove_success,
        info = { metadata(e.name, e.url + "\n\n" + dnsDesc(e.desc), e.url, noDim = true) })

    fun odoh(e: ODoHEndpoint) = row(e.id, e.name, e.isSelected, e.isDeletable(), e.resolver,
        select = { e.isSelected = true; appConfig.handleODoHChanges(e) },
        delete = { appConfig.deleteODoHEndpoint(e.id) },
        deleteTitle = R.string.dot_custom_url_remove_dialog_title,
        deleteMessage = R.string.dot_custom_url_remove_dialog_message,
        success = R.string.doh_custom_url_remove_success,
        info = { metadata(e.name, e.proxy + "\n\n" + e.resolver + "\n\n" + dnsDesc(e.desc), e.resolver) })

    fun crypt(e: DnsCryptEndpoint) = row(e.id, e.dnsCryptName, e.isSelected, e.isDeletable(), e.dnsCryptURL,
        select = { e.isSelected = true; appConfig.handleDnscryptChanges(e) },
        delete = { appConfig.deleteDnscryptEndpoint(e.id) },
        deleteTitle = R.string.dns_crypt_custom_url_remove_dialog_title,
        deleteMessage = R.string.dns_crypt_url_remove_dialog_message,
        success = R.string.dns_crypt_url_remove_success, noDimDelete = true,
        info = { metadata(e.dnsCryptName, cryptMessage(e.dnsCryptURL, e.dnsCryptExplanation), e.dnsCryptURL, noDim = true) })

    fun relay(e: DnsCryptRelayEndpoint) = row(e.id, e.dnsCryptRelayName, e.isSelected, e.isDeletable(), e.dnsCryptRelayURL,
        select = {
            val selected = !e.isSelected
            if (selected && !appConfig.isDnscryptRelaySelectable()) {
                ui { toast(R.string.dns_crypt_relay_error_toast, Toast.LENGTH_LONG) }
            } else {
                e.isSelected = selected
                appConfig.handleDnsrelayChanges(e)
            }
        },
        delete = { appConfig.deleteDnscryptRelayEndpoint(e.id) },
        deleteTitle = R.string.dns_crypt_relay_remove_dialog_title,
        deleteMessage = R.string.dns_crypt_relay_remove_dialog_message,
        success = R.string.dns_crypt_relay_remove_success, noDimDelete = true,
        info = { metadata(e.dnsCryptRelayName, cryptMessage(e.dnsCryptRelayURL, e.dnsCryptRelayExplanation), e.dnsCryptRelayURL, noDim = true) },
        relay = true, polling = false,
        description = if (e.isSelected && !appConfig.isSmartDnsEnabled()) ::dnsStatus else null)

    fun proxy(e: DnsProxyEndpoint) = row(e.id, e.proxyName, e.isSelected, e.isDeletable(), e.proxyIP.orEmpty(),
        select = {
            val app = e.proxyAppName
            if (persistentState?.wgGlobalLockdown == true && !app.isNullOrBlank() && app != context.getString(R.string.cd_custom_dns_proxy_default_app)) {
                val appName = FirewallManager.getAppInfoByPackage(app)?.appName ?: app
                ui {
                    WgAlertDialogBuilder(context).setTitle(R.string.lockdown_check_dialog_title)
                        .setMessage(context.getString(R.string.dns_proxy_lockdown_conflict_message, appName))
                        .setCancelable(true).setPositiveButton(R.string.dns_info_positive) { dialog, _ -> dialog.dismiss() }.show()
                }
            } else {
                e.isSelected = true
                appConfig.handleDnsProxyChanges(e)
            }
        },
        delete = { appConfig.deleteDnsProxyEndpoint(e.id) },
        deleteTitle = R.string.dns_proxy_remove_dialog_title,
        deleteMessage = R.string.dns_proxy_remove_dialog_message,
        success = R.string.dns_proxy_remove_success,
        info = {
            val app = FirewallManager.getAppInfoByPackage(e.getPackageName())?.appName
            val message = if (!app.isNullOrEmpty()) context.getString(R.string.dns_proxy_dialog_message, app, e.proxyIP, e.proxyPort.toString())
                else context.getString(R.string.dns_proxy_dialog_message_no_app, e.proxyIP, e.proxyPort.toString())
            metadata(e.proxyName, message, e.proxyIP, ip = true)
        }, directIp = true, polling = false,
        description = {
            val appInfo = FirewallManager.getAppInfoByPackage(e.proxyAppName)
            val appName = if (e.proxyName != context.getString(R.string.cd_custom_dns_proxy_default_app))
                appInfo?.appName ?: context.getString(R.string.cd_custom_dns_proxy_default_app)
                else e.proxyAppName ?: context.getString(R.string.cd_custom_dns_proxy_default_app)
            e.getExplanationText(context, appName)
        })

    private fun row(
        id: Int, name: String, selected: Boolean, deletable: Boolean, address: String,
        select: suspend () -> Unit, delete: suspend () -> Unit,
        deleteTitle: Int, deleteMessage: Int, success: Int,
        info: suspend () -> Unit, noDimDelete: Boolean = false, directIp: Boolean = false,
        polling: Boolean = true, relay: Boolean = false,
        description: (suspend () -> String?)? = null
    ): DnsEndpointRowState {
        val boundDescription = if (polling) bindDnsEndpointDescription(
            selected,
            canPoll = { VpnController.hasTunnel() && !appConfig.isSmartDnsEnabled() },
            selectedLabel = { context.getString(R.string.rt_filter_parent_selected) },
            status = ::dnsStatus
        ) else DnsEndpointDescriptionState(read = description)
        return DnsEndpointRowState(id, name, selected, deletable, address,
            boundDescription.poll, relay,
            boundDescription.read, { flag(address, selected, directIp) },
            onSelect = { io(select) },
            onInfo = {
                if (deletable) {
                    WgAlertDialogBuilder(context, if (noDimDelete) R.style.App_Dialog_NoDim else 0)
                        .setTitle(deleteTitle).setMessage(deleteMessage).setCancelable(true)
                        .setPositiveButton(R.string.lbl_delete) { _, _ -> io { delete(); ui { toast(success) } } }
                        .setNegativeButton(R.string.lbl_cancel) { _, _ -> }.show()
                } else io(info)
            }, initialDescription = boundDescription.initial,
            flagStyle = if (directIp) DnsEndpointFlagStyle.Proxy else DnsEndpointFlagStyle.Standard)
    }

    private suspend fun metadata(title: String, message: String, copy: String?, noDim: Boolean = false, ip: Boolean = false) {
        ui {
            WgAlertDialogBuilder(context, if (noDim) R.style.App_Dialog_NoDim else 0)
                .setTitle(title).setMessage(message).setCancelable(true)
                .setPositiveButton(R.string.dns_info_positive) { dialog, _ -> dialog.dismiss() }
                .setNeutralButton(R.string.dns_info_neutral) { _, _ ->
                    if (copy != null) {
                        UIUtils.clipboardCopy(context, copy, context.getString(R.string.copy_clipboard_label))
                        toast(if (ip) R.string.info_dialog_copy_toast_msg else R.string.info_dialog_url_copy_toast_msg)
                    }
                }.show()
        }
    }

    private fun secureName(name: String, secure: Boolean) = if (secure) name else context.getString(R.string.ci_desc, name, context.getString(R.string.lbl_insecure))
    private suspend fun dnsStatus() = context.getString(UIUtils.getDnsStatusStringRes(VpnController.getDnsStatus(Backend.Preferred))).replaceFirstChar(Char::titlecase)
    private suspend fun flag(address: String, selected: Boolean, directIp: Boolean): String? {
        var ip: String? = null
        if (selected) ip = VpnController.getDnsIps(Backend.Preferred)?.split(",")?.firstOrNull()?.trim()?.let { IpRulesManager.splitHostPort(it).first }
        if (ip.isNullOrBlank()) ip = if (directIp) address else Utilities.getIpForUrl(context, address)
        if (ip.isNullOrBlank()) return null
        return IpInfoDownloader.getIpInfo(ip)?.countryCode?.takeIf(String::isNotEmpty)?.let(Utilities::getFlag)
    }
    private fun cryptMessage(url: String, message: String?) = if (message == null) url else url + "\n\n" + dnsDesc(message)
    private fun dnsDesc(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        return try {
            if (message.contains("R.string.")) context.getString(context.resources.getIdentifier(message.substringAfter("R.string."), "string", context.packageName)) else message
        } catch (_: Exception) { "" }
    }
    private fun toast(message: Int, duration: Int = Toast.LENGTH_SHORT) = Utilities.showToastUiCentered(context, context.getString(message), duration)
    private fun io(block: suspend () -> Unit) { owner.lifecycleScope.launch(ioDispatcher) { block() } }
    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main.immediate) {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) block()
    }
}
