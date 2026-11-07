package com.celzero.bravedns.ui.compose.blocklists

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.compose.dnssettings.DnsControl
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.*

class RethinkEndpointPresenter(private val context: Context, private val appConfig: AppConfig, private val lifecycleOwner: LifecycleOwner, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val TAG = "RethinkEndpointAdapter"
    var metadata by mutableStateOf<RethinkDnsEndpoint?>(null)
    fun editable(endpoint: RethinkDnsEndpoint) = endpoint.isEditable(context)
    fun select(endpoint: RethinkDnsEndpoint) = updateConnection(endpoint)
    fun edit(endpoint: RethinkDnsEndpoint) = openEditConfiguration(endpoint)
    fun copy(endpoint: RethinkDnsEndpoint) {
        clipboardCopy(context, endpoint.url, context.getString(R.string.copy_clipboard_label))
        Utilities.showToastUiCentered(context, context.getString(R.string.info_dialog_url_copy_toast_msg), Toast.LENGTH_SHORT)
    }
        private fun updateConnection(endpoint: RethinkDnsEndpoint) {
            Logger.d(
                LOG_TAG_DNS,
                "$TAG rdns update; ${endpoint.name}, ${endpoint.url}, ${endpoint.isActive}"
            )

            io {
                endpoint.isActive = true
                appConfig.handleRethinkChanges(endpoint)
            }
        }
        private fun openEditConfiguration(endpoint: RethinkDnsEndpoint) {

            if (!VpnController.hasTunnel()) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.ssv_toast_start_rethink),
                    Toast.LENGTH_SHORT
                )
                return
            }

            val intent = Intent(context, ConfigureRethinkBasicActivity::class.java)
            intent.putExtra(
                ConfigureRethinkBasicActivity.RETHINK_BLOCKLIST_TYPE,
                RethinkBlocklistManager.RethinkBlocklistType.REMOTE
            )
            intent.putExtra(ConfigureRethinkBasicActivity.RETHINK_BLOCKLIST_NAME, endpoint.name)
            intent.putExtra(ConfigureRethinkBasicActivity.RETHINK_BLOCKLIST_URL, endpoint.url)
            context.startActivity(intent)
        }
        private fun io(f: suspend () -> Unit): Job? {
            return lifecycleOwner?.lifecycleScope?.launch { withContext(ioDispatcher) { f() } }
        }
    fun bind(endpoint: RethinkDnsEndpoint): BoundEndpoint = BoundEndpoint(endpoint)
    inner class BoundEndpoint(private val endpoint: RethinkDnsEndpoint) {
        val rethinkEndpointListUrlExplanation = DnsControl()
        val rethinkEndpointListUrlFlagText = DnsControl().apply { visible = false }
        private val b get() = this
        private var statusCheckJob: Job? = null
        private var flagJob: Job? = null
        init {
            // Evaluate the polling guard at bind time only, just as the original adapter.
            if (endpoint.isActive && VpnController.hasTunnel() && !appConfig.isSmartDnsEnabled()) {
                statusCheckJob = io {
                    while (true) {
                        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) break
                        updateDnsStatus(endpoint)
                        delay(1000L)
                    }
                }
            } else if (endpoint.isActive) {
                rethinkEndpointListUrlExplanation.text = context.getString(R.string.rt_filter_parent_selected)
                rethinkEndpointListUrlExplanation.visible = true
            } else {
                rethinkEndpointListUrlExplanation.text = ""
                rethinkEndpointListUrlExplanation.visible = false
            }
            flagJob = io { updateFlag(endpoint) }
        }
        fun dispose() { statusCheckJob?.cancel() }
        private suspend fun updateDnsStatus(endpoint: RethinkDnsEndpoint) {
            val state = VpnController.getDnsStatus(Backend.Preferred)
            val status = UIUtils.getDnsStatusStringRes(state)
            uiCtx {
                if (status != R.string.dns_connected) {
                    b.rethinkEndpointListUrlExplanation.text =
                        context.getString(status).replaceFirstChar(Char::titlecase)
                    b.rethinkEndpointListUrlExplanation.visible = true
                    return@uiCtx
                }

                if (endpoint.blocklistCount > 0) {
                    b.rethinkEndpointListUrlExplanation.text =
                        context.getString(
                            R.string.dns_connected_rethink_plus,
                            endpoint.blocklistCount.toString()
                        )
                } else {
                    b.rethinkEndpointListUrlExplanation.text = context.getString(status)
                }
                b.rethinkEndpointListUrlExplanation.visible = true
            }

        }
        private suspend fun updateFlag(endpoint: RethinkDnsEndpoint) {
            var ip: String? = null

            if (endpoint.isActive) {
                val ips = VpnController.getDnsIps(Backend.Preferred)
                ip = ips?.split(",")?.firstOrNull()?.trim()?.let { stripPort(it) }
            }

            if (ip.isNullOrBlank()) {
                val url = getBaseUrl(endpoint.url)
                if (url.isNullOrBlank()) {
                    uiCtx { b.rethinkEndpointListUrlFlagText.visible = false }
                    return
                }
                ip = Utilities.getIpForUrl(context, url)
            }

            if (ip.isNullOrBlank()) {
                uiCtx { b.rethinkEndpointListUrlFlagText.visible = false }
                return
            }

            val ipInfo = IpInfoDownloader.getIpInfo(ip)
            uiCtx {
                if (ipInfo != null && ipInfo.countryCode.isNotEmpty()) {
                    b.rethinkEndpointListUrlFlagText.text = Utilities.getFlag(ipInfo.countryCode)
                    b.rethinkEndpointListUrlFlagText.visible = true
                } else {
                    b.rethinkEndpointListUrlFlagText.visible = false
                }
            }
        }
        fun getBaseUrl(url: String): String? {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""

            return "$scheme://$host$port/"
        }
        private fun stripPort(addr: String): String {
            return IpRulesManager.splitHostPort(addr).first
        }
        private suspend fun uiCtx(f: suspend () -> Unit) {
            val owner = lifecycleOwner ?: return

            withContext(Dispatchers.Main.immediate) {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@withContext
                }

                f()
            }
        }
    }
}
