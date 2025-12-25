/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import Logger
import Logger.LOG_TAG_DNS
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ListItemStatisticsSummaryBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.DomainConnectionsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment.SummaryStatisticsType
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getFlag
import com.celzero.bravedns.util.Utilities.isAtleastN
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2

class SummaryStatisticsAdapter(
    private val context: Context,
    private val persistentState: PersistentState,
    private val appConfig: AppConfig,
    private val type: SummaryStatisticsType
) :
    PagingDataAdapter<AppConnection, SummaryStatisticsAdapter.AppNetworkActivityViewHolder>(
        DIFF_CALLBACK
    ) {

    private var maxValue: Int = 0
    private var timeCategory = SummaryStatisticsViewModel.TimeCategory.ONE_HOUR

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {
                // Fix: Compare by unique identifiers instead of object equality
                // to prevent RecyclerView position inconsistencies
                override fun areItemsTheSame(old: AppConnection, new: AppConnection): Boolean {
                    return old.uid == new.uid &&
                            old.ipAddress == new.ipAddress &&
                            old.port == new.port
                }

                override fun areContentsTheSame(old: AppConnection, new: AppConnection): Boolean {
                    return old.uid == new.uid &&
                            old.ipAddress == new.ipAddress &&
                            old.port == new.port &&
                            old.count == new.count &&
                            old.flag == new.flag &&
                            old.blocked == new.blocked &&
                            old.appOrDnsName == new.appOrDnsName &&
                            old.downloadBytes == new.downloadBytes &&
                            old.uploadBytes == new.uploadBytes &&
                            old.totalBytes == new.totalBytes
                }
            }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppNetworkActivityViewHolder {
        val itemBinding =
            ListItemStatisticsSummaryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return AppNetworkActivityViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppNetworkActivityViewHolder, position: Int) {
        // Fix: Validate position to prevent IndexOutOfBoundsException
        if (position < 0 || position >= itemCount) {
            Logger.w(LOG_TAG_DNS, "Invalid position $position, itemCount: $itemCount")
            return
        }
        val conn = getItem(position) ?: return
        holder.bind(conn)
    }

    private fun calculatePercentage(c: Double): Int {
        val value = (log2(c) * 100).toInt()
        // maxValue will be based on the count returned by the database query (order by count desc)
        if (value > maxValue) {
            maxValue = value
        }
        return if (maxValue == 0) {
            0
        } else {
            (value * 100 / maxValue)
        }
    }

    fun setTimeCategory(timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        this.timeCategory = timeCategory
    }

    inner class AppNetworkActivityViewHolder(
        private val itemBinding: ListItemStatisticsSummaryBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(appConnection: AppConnection) {
            setName(appConnection)
            io { setIcon(appConnection) }
            showDataUsage(appConnection)
            setProgress(appConnection)
            setConnectionCount(appConnection)
            setupClickListeners(appConnection)
        }

        private fun setConnectionCount(appConnection: AppConnection) {
            itemBinding.ssCount.text = appConnection.count.toString()
        }

        private fun showDataUsage(appConnection: AppConnection) {
            if (SummaryStatisticsType.MOST_CONNECTED_APPS != type) {
                itemBinding.ssName.visibility = View.GONE
                itemBinding.ssCount.text = appConnection.count.toString()
                return
            }

            if (appConnection.downloadBytes == null || appConnection.uploadBytes == null) {
                itemBinding.ssName.visibility = View.GONE
                itemBinding.ssCount.text = appConnection.count.toString()
                return
            }

            itemBinding.ssName.visibility = View.VISIBLE
            val download =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(appConnection.downloadBytes, true)
                )
            val upload =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(appConnection.uploadBytes, true)
                )
            val total = context.getString(R.string.two_argument, upload, download)
            itemBinding.ssDataUsage.text = total
            itemBinding.ssCount.text = appConnection.count.toString()
        }

        private suspend fun setIcon(appConnection: AppConnection) {

            when (type) {
                SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                    io {
                        val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                        uiCtx {
                            itemBinding.ssIcon.visibility = View.VISIBLE
                            itemBinding.ssFlag.visibility = View.GONE
                            loadAppIcon(
                                Utilities.getIcon(
                                    context,
                                    appInfo?.packageName ?: "",
                                    appInfo?.appName ?: ""
                                )
                            )
                        }
                    }
                }
                SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                    io {
                        val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                        uiCtx {
                            itemBinding.ssIcon.visibility = View.VISIBLE
                            itemBinding.ssFlag.visibility = View.GONE
                            loadAppIcon(
                                Utilities.getIcon(
                                    context,
                                    appInfo?.packageName.orEmpty(),
                                    appInfo?.appName.orEmpty()
                                )
                            )
                        }
                    }
                }
                SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                    io {
                        val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                        uiCtx {
                            itemBinding.ssIcon.visibility = View.VISIBLE
                            itemBinding.ssFlag.visibility = View.GONE
                            loadAppIcon(
                                Utilities.getIcon(
                                    context,
                                    appInfo?.packageName.orEmpty(),
                                    appInfo?.appName.orEmpty()
                                )
                            )
                        }
                    }
                }
                SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                    uiCtx {
                        if (appConnection.flag.isNotEmpty()) {
                            val flag = getFlag(appConnection.flag)
                            itemBinding.ssFlag.text = flag
                        } else {
                            itemBinding.ssFlag.text = "--"
                        }
                        itemBinding.ssIcon.visibility = View.GONE
                        itemBinding.ssFlag.visibility = View.VISIBLE
                    }
                }
                SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                    uiCtx {
                        if (appConnection.flag.isNotEmpty()) {
                            val flag = getFlag(appConnection.flag)
                            itemBinding.ssFlag.text = flag
                        } else {
                            itemBinding.ssFlag.text = "--"
                        }
                        itemBinding.ssIcon.visibility = View.GONE
                        itemBinding.ssFlag.visibility = View.VISIBLE
                    }
                }
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                    uiCtx {
                        itemBinding.ssFlag.text = appConnection.flag
                        val query = appConnection.appOrDnsName?.dropLastWhile { it == ',' }
                        if (query == null) {
                            hideFavIcon()
                            showFlag()
                            return@uiCtx
                        }

                        // no need to check in glide cache if the value is available in failed
                        // cache
                        if (FavIconDownloader.isUrlAvailableInFailedCache(query) != null) {
                            hideFavIcon()
                            showFlag()
                        } else {
                            // Glide will cache the icons against the urls. To extract the fav
                            // icon from the cache, first verify that the cache is available with
                            // the next dns url. If it is not available then glide will throw an
                            // error, do the duckduckgo url check in that case.
                            displayNextDnsFavIcon(query)
                        }
                    }
                }
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                    uiCtx {
                        itemBinding.ssIcon.visibility = View.GONE
                        itemBinding.ssFlag.visibility = View.VISIBLE
                        itemBinding.ssFlag.text = appConnection.flag
                    }
                }
                SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                    uiCtx {
                        itemBinding.ssIcon.visibility = View.GONE
                        itemBinding.ssFlag.visibility = View.VISIBLE
                        itemBinding.ssFlag.text = appConnection.flag
                    }
                }
                SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                    uiCtx {
                        itemBinding.ssIcon.visibility = View.GONE
                        itemBinding.ssFlag.visibility = View.VISIBLE
                        itemBinding.ssFlag.text = appConnection.flag
                    }
                }
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                    uiCtx {
                        itemBinding.ssIcon.visibility = View.GONE
                        itemBinding.ssFlag.visibility = View.VISIBLE
                        itemBinding.ssFlag.text = appConnection.flag
                    }
                }
            }
        }

        private fun setName(appConnection: AppConnection) {
            when (type) {
                SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                    io {
                        val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                        uiCtx {
                            val appName = getAppName(appConnection, appInfo)
                            itemBinding.ssDataUsage.visibility = View.VISIBLE
                            itemBinding.ssDataUsage.text = appName
                        }
                    }
                }
                SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                    io {
                        val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                        uiCtx {
                            val appName = getAppName(appConnection, appInfo)
                            itemBinding.ssName.visibility = View.VISIBLE
                            itemBinding.ssName.text = appName
                        }
                    }
                }
                SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                    io {
                        val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                        uiCtx {
                            val appName = getAppName(appConnection, appInfo)
                            itemBinding.ssDataUsage.visibility = View.VISIBLE
                            itemBinding.ssDataUsage.text = appName
                        }
                    }
                }
                SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    itemBinding.ssDataUsage.text = appConnection.appOrDnsName
                }
                SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    itemBinding.ssDataUsage.text = appConnection.appOrDnsName
                }
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                    itemBinding.ssContainer.visibility = View.VISIBLE
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    // now there won't be any trailing '.' in the domain name, from v0.5.5o
                    // TODO: remove this in later versions
                    itemBinding.ssDataUsage.text =
                        appConnection.appOrDnsName?.dropLastWhile { it == '.' }
                }
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                    itemBinding.ssContainer.visibility = View.VISIBLE
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    // now there won't be any trailing '.' in the domain name, from v0.5.5o
                    // TODO: remove this in later versions
                    itemBinding.ssDataUsage.text =
                        appConnection.appOrDnsName?.dropLastWhile { it == '.' }
                }
                SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    itemBinding.ssDataUsage.text = appConnection.ipAddress
                }
                SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    itemBinding.ssDataUsage.text = appConnection.ipAddress
                }
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                    itemBinding.ssDataUsage.visibility = View.VISIBLE
                    val flag = getCountryNameFromFlag(appConnection.flag)
                    if (flag.isNotEmpty() && flag != "--") {
                        itemBinding.ssDataUsage.text = getCountryNameFromFlag(appConnection.flag)
                    } else {
                        itemBinding.ssDataUsage.text = context.getString(
                            R.string.two_argument_space,
                            context.getString(R.string.network_log_app_name_unknown),
                            appConnection.flag
                        )
                    }
                }
            }
        }

        private fun getAppName(appConnection: AppConnection, appInfo: AppInfo?): String? {
            return if (appConnection.appOrDnsName.isNullOrEmpty()) {
                if (appInfo?.appName.isNullOrEmpty()) {
                    context.getString(R.string.network_log_app_name_unnamed, "($appConnection.uid)")
                } else {
                    appInfo?.appName ?: context.getString(R.string.network_log_app_name_unnamed, "(${appConnection.uid})")
                }
            } else {
                appConnection.appOrDnsName
            }
        }

        private fun setProgress(appConnection: AppConnection) {
            val c =
                if (type == SummaryStatisticsType.MOST_CONNECTED_APPS) {
                    val d = appConnection.downloadBytes ?: 0L
                    val u = appConnection.uploadBytes ?: 0L
                    (d + u).toDouble()
                } else {
                    appConnection.count.toDouble()
                }
            val isBlocked = appConnection.blocked
            val percentage = calculatePercentage(c)
            if (isBlocked) {
                itemBinding.ssProgress.setIndicatorColor(
                    fetchToggleBtnColors(context, R.color.accentBad)
                )
            } else {
                itemBinding.ssProgress.setIndicatorColor(
                    fetchToggleBtnColors(context, R.color.accentGood)
                )
            }
            if (isAtleastN()) {
                itemBinding.ssProgress.setProgress(percentage, true)
            } else {
                itemBinding.ssProgress.progress = percentage
            }
        }

        private fun loadAppIcon(drawable: Drawable?) {
            ui {
                Glide.with(context)
                    .load(drawable)
                    .error(Utilities.getDefaultIcon(context))
                    .into(itemBinding.ssIcon)
            }
        }

        private fun setupClickListeners(appConnection: AppConnection) {
            itemBinding.ssContainer.setOnClickListener {
                when (type) {
                    SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                        startAppInfoActivity(appConnection)
                    }
                    SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                        io {
                            if (isUnknownApp(appConnection)) {
                                uiCtx {
                                    showNetworkLogs(
                                        appConnection,
                                        SummaryStatisticsType.MOST_CONNECTED_APPS
                                    )
                                }
                            } else {
                                uiCtx { startAppInfoActivity(appConnection) }
                            }
                        }
                    }
                    SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                        io {
                            if (isUnknownApp(appConnection)) {
                                uiCtx {
                                    showNetworkLogs(
                                        appConnection,
                                        SummaryStatisticsType.MOST_BLOCKED_APPS
                                    )
                                }
                            } else {
                                uiCtx { startAppInfoActivity(appConnection) }
                            }
                        }
                    }
                    SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.ASN)
                    }
                    SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.ASN, true)
                    }
                    SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.DOMAIN)
                    }
                    SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.DOMAIN, true)
                    }
                    SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.IP)
                    }
                    SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.IP, true)
                    }
                    SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                        startDomainConnectionsActivity(appConnection, DomainConnectionsActivity.InputType.FLAG)
                    }
                }
            }
        }

        private suspend fun isUnknownApp(appConnection: AppConnection): Boolean {
            val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
            return appInfo == null
        }

        private fun startDomainConnectionsActivity(appConnection: AppConnection, input: DomainConnectionsActivity.InputType, isBlocked: Boolean = false) {
            val intent = Intent(context, DomainConnectionsActivity::class.java)
            intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TYPE, input.type)
            when (input) {
                DomainConnectionsActivity.InputType.DOMAIN -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_DOMAIN, appConnection.appOrDnsName)
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                }
                DomainConnectionsActivity.InputType.ASN -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_ASN, appConnection.appOrDnsName)
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                }
                DomainConnectionsActivity.InputType.FLAG -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_FLAG, appConnection.flag)
                }
                DomainConnectionsActivity.InputType.IP -> {
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IP, appConnection.ipAddress)
                    intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, isBlocked)
                }
            }
            intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TIME_CATEGORY, timeCategory.value)
            context.startActivity(intent)
        }

        private fun startAppInfoActivity(appConnection: AppConnection) {
            val intent = Intent(context, AppInfoActivity::class.java)
            intent.putExtra(AppInfoActivity.INTENT_UID, appConnection.uid)
            context.startActivity(intent)
        }

        private fun showDnsLogs(appConnection: AppConnection) {
            if (!handleVpnState()) return

            if (appConfig.getBraveMode().isDnsActive()) {
                startActivity(NetworkLogsActivity.Tabs.DNS_LOGS.screen, appConnection.appOrDnsName)
            } else {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.dns_card_latency_inactive),
                    Toast.LENGTH_SHORT
                )
            }
        }

        private fun showNetworkLogs(appConnection: AppConnection, type: SummaryStatisticsType) {
            if (!handleVpnState()) return

            if (!appConfig.getBraveMode().isFirewallActive()) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.firewall_card_text_inactive),
                    Toast.LENGTH_SHORT
                )
                return
            }

            when (type) {
                SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.appOrDnsName
                    )
                }
                SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.appOrDnsName
                    )
                }
                SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.ipAddress
                    )
                }
                SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                    startActivity(
                        NetworkLogsActivity.Tabs.NETWORK_LOGS.screen,
                        appConnection.ipAddress
                    )
                }
                SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                    startActivity(NetworkLogsActivity.Tabs.NETWORK_LOGS.screen, appConnection.flag)
                }
                else -> {
                    // should never happen, but just in case we'll show all logs
                    startActivity(NetworkLogsActivity.Tabs.NETWORK_LOGS.screen, appConnection.appOrDnsName)
                }
            }
        }

        private fun handleVpnState(): Boolean {
            if (persistentState.vpnEnabledLiveData.value == false) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.ssv_toast_start_rethink),
                    Toast.LENGTH_SHORT
                )
                return false
            }
            return true
        }

        private fun startActivity(screenToLoad: Int, searchParam: String?) {
            val intent = Intent(context, NetworkLogsActivity::class.java)
            intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, screenToLoad)
            intent.putExtra(Constants.SEARCH_QUERY, searchParam.orEmpty())
            context.startActivity(intent)
        }

        private fun displayNextDnsFavIcon(query: String) {
            // url to check if the icon is cached from nextdns
            val nextDnsUrl = FavIconDownloader.constructFavIcoUrlNextDns(query)
            // url to check if the icon is cached from duckduckgo
            val duckDuckGoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(query)
            // subdomain to check if the icon is cached from duckduckgo
            val duckduckgoDomainURL = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(query)
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                Glide.with(context.applicationContext)
                    .load(nextDnsUrl)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(
                        // on error, check if the icon is stored in the name of duckduckgo url
                        displayDuckduckgoFavIcon(duckDuckGoUrl, duckduckgoDomainURL)
                    )
                    .transition(DrawableTransitionOptions.withCrossFade(factory))
                    .into(
                        object : CustomViewTarget<ImageView, Drawable>(itemBinding.ssIcon) {
                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                showFlag()
                                hideFavIcon()
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                hideFlag()
                                showFavIcon(resource)
                            }

                            override fun onResourceCleared(placeholder: Drawable?) {
                                hideFavIcon()
                                showFlag()
                            }
                        }
                    )
            } catch (_: Exception) {
                Logger.d(LOG_TAG_DNS, "err loading icon, load flag instead")
                displayDuckduckgoFavIcon(duckDuckGoUrl, duckduckgoDomainURL)
            }
        }

        /**
         * Loads the fav icons from the cache, the icons are cached by favIconDownloader. On
         * failure, will check if there is a icon for top level domain is available in cache. Else,
         * will show the Flag.
         *
         * This method will be executed only when show fav icon setting is turned on.
         */
        private fun displayDuckduckgoFavIcon(url: String, subDomainURL: String) {
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                Glide.with(context.applicationContext)
                    .load(url)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(
                        Glide.with(context.applicationContext)
                            .load(subDomainURL)
                            .onlyRetrieveFromCache(true)
                    )
                    .transition(DrawableTransitionOptions.withCrossFade(factory))
                    .into(
                        object : CustomViewTarget<ImageView, Drawable>(itemBinding.ssIcon) {
                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                showFlag()
                                hideFavIcon()
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                hideFlag()
                                showFavIcon(resource)
                            }

                            override fun onResourceCleared(placeholder: Drawable?) {
                                hideFavIcon()
                                showFlag()
                            }
                        }
                    )
            } catch (_: Exception) {
                Logger.d(LOG_TAG_DNS, "err loading icon, load flag instead")
                showFlag()
                hideFavIcon()
            }
        }

        private fun showFavIcon(drawable: Drawable) {
            itemBinding.ssIcon.visibility = View.VISIBLE
            itemBinding.ssIcon.setImageDrawable(drawable)
        }

        private fun hideFavIcon() {
            itemBinding.ssIcon.visibility = View.GONE
            itemBinding.ssIcon.setImageDrawable(null)
        }

        private fun showFlag() {
            itemBinding.ssFlag.visibility = View.VISIBLE
        }

        private fun hideFlag() {
            itemBinding.ssFlag.visibility = View.GONE
        }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
