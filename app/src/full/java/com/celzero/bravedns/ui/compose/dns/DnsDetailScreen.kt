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
package com.celzero.bravedns.ui.compose.dns


import Logger
import Logger.LOG_TAG_DNS
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator
import com.celzero.bravedns.data.AppConfig.Companion.DOH_INDEX
import com.celzero.bravedns.data.AppConfig.Companion.DOT_INDEX
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.bottomsheet.RuleSheetModal
import com.celzero.bravedns.ui.bottomsheet.RuleSheetSummaryPill
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetCard
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkMultiActionDialog
import com.celzero.bravedns.ui.compose.theme.RethinkTwoOptionSegmentedRow
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.RETHINK_SEARCH_URL
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.blocklistCanonicalPath
import com.celzero.bravedns.util.Utilities.convertLongToTime
import com.celzero.bravedns.util.Utilities.deleteRecursive
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.firestack.backend.Backend
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DNS Detail Screen - A composable screen that shows DNS settings and configuration.
 * This is the Compose equivalent of DnsDetailActivity.
 *
 * @param viewModel The DnsSettingsViewModel for managing DNS settings state
 * @param persistentState The PersistentState for accessing app preferences
 * @param appDownloadManager The AppDownloadManager for handling blocklist downloads
 * @param onCustomDnsClick Callback when custom DNS is clicked (navigates to DNS list)
 * @param onRethinkPlusDnsClick Callback when Rethink Plus DNS is clicked
 * @param onLocalBlocklistConfigureClick Callback when local blocklist configure is clicked
 * @param onBackClick Optional callback for back navigation
 */
@Composable
fun DnsDetailScreen(
    viewModel: DnsSettingsViewModel,
    persistentState: PersistentState,
    appDownloadManager: AppDownloadManager,
    initialFocusKey: String? = null,
    onCustomDnsClick: () -> Unit,
    onRethinkPlusDnsClick: () -> Unit,
    onLocalBlocklistConfigureClick: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Dialog/Sheet state
    var showRecordTypesSheet by remember { mutableStateOf(false) }
    var showSystemDnsDialog by remember { mutableStateOf(false) }
    var systemDnsDialogText by remember { mutableStateOf("") }
    var showSmartDnsDialog by remember { mutableStateOf(false) }
    var smartDnsDialogText by remember { mutableStateOf("") }
    var showLocalBlocklistsSheet by remember { mutableStateOf(false) }

    // Local blocklist state
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadDialogIsRedownload by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLockdownDialog by remember { mutableStateOf(false) }

    var headingText by remember { mutableStateOf("") }
    var versionText by remember { mutableStateOf("") }
    var canConfigure by remember { mutableStateOf(false) }
    var canCopy by remember { mutableStateOf(false) }
    var canSearch by remember { mutableStateOf(false) }
    var showCheckDownload by remember { mutableStateOf(true) }
    var showDownload by remember { mutableStateOf(false) }
    var showRedownload by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isRedownloading by remember { mutableStateOf(false) }
    val localBlocklistInUseText = stringResource(
        R.string.settings_local_blocklist_in_use,
        persistentState.numberOfLocalBlocklists.toString(),
    )
    val localBlocklistHeadingText = stringResource(R.string.lbbs_heading)
    val localBlocklistVersionText =
        if (persistentState.localBlocklistTimestamp == INIT_TIME_MS) {
            ""
        } else {
            stringResource(
                R.string.settings_local_blocklist_version,
                convertLongToTime(
                    persistentState.localBlocklistTimestamp,
                    Constants.TIME_FORMAT_2,
                ),
            )
        }
    val blocklistUpdateFailureText = stringResource(R.string.blocklist_update_check_failure)
    val blocklistUpdateNotRequiredText = stringResource(R.string.blocklist_update_check_not_required)
    val blocklistNotAvailableToastText = stringResource(R.string.blocklist_not_available_toast)
    val configAddSuccessToastText = stringResource(R.string.config_add_success_toast)
    val ssvToastStartRethinkText = stringResource(R.string.ssv_toast_start_rethink)
    val smartDnsDescriptionText = stringResource(R.string.smart_dns_desc)
    val symbolStarText = stringResource(R.string.symbol_star)
    val copyClipboardLabelText = stringResource(R.string.copy_clipboard_label)
    val infoDialogUrlCopyToastText = stringResource(R.string.info_dialog_url_copy_toast_msg)
    val infoDialogRethinkToastText = stringResource(R.string.info_dialog_rethink_toast_msg)
    // Helper functions for local blocklist UI state
    fun showCheckUpdateUi() {
        showCheckDownload = true
        showDownload = false
        showRedownload = false
        isChecking = false
        isDownloading = false
        isRedownloading = false
    }

    fun showUpdateUi() {
        showCheckDownload = false
        showDownload = true
        showRedownload = false
        isChecking = false
        isDownloading = false
        isRedownloading = false
    }

    fun showRedownloadUi() {
        showCheckDownload = false
        showDownload = false
        showRedownload = true
        isChecking = false
        isDownloading = false
        isRedownloading = false
    }

    fun enableBlocklistUi() {
        headingText = localBlocklistInUseText
        canConfigure = true
        canCopy = true
        canSearch = true
    }

    fun disableBlocklistUi() {
        headingText = localBlocklistHeadingText
        canConfigure = false
        canCopy = false
        canSearch = false
    }

    fun updateLocalBlocklistUi() {
        if (Utilities.isPlayStoreFlavour()) {
            return
        }

        if (persistentState.blocklistEnabled) {
            enableBlocklistUi()
            return
        }

        disableBlocklistUi()
    }

    fun initLocalBlocklistVersion() {
        if (persistentState.localBlocklistTimestamp == INIT_TIME_MS) {
            showCheckUpdateUi()
            versionText = ""
            return
        }

        versionText = localBlocklistVersionText

        if (persistentState.newestRemoteBlocklistTimestamp == INIT_TIME_MS) {
            showCheckUpdateUi()
            return
        }

        if (persistentState.newestLocalBlocklistTimestamp > persistentState.localBlocklistTimestamp) {
            showUpdateUi()
            return
        }

        showCheckUpdateUi()
    }

    fun handleDownloadStatus(status: AppDownloadManager.DownloadManagerStatus) {
        when (status) {
            AppDownloadManager.DownloadManagerStatus.IN_PROGRESS -> {
                isChecking = true
            }
            AppDownloadManager.DownloadManagerStatus.STARTED -> {
                isChecking = true
            }
            AppDownloadManager.DownloadManagerStatus.NOT_STARTED -> {
                // no-op
            }
            AppDownloadManager.DownloadManagerStatus.SUCCESS -> {
                showUpdateUi()
                isChecking = false
                isDownloading = false
                isRedownloading = false
                appDownloadManager.downloadRequired.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                )
            }
            AppDownloadManager.DownloadManagerStatus.FAILURE -> {
                isChecking = false
                isDownloading = false
                isRedownloading = false
                Utilities.showToastUiCentered(
                    context,
                    blocklistUpdateFailureText,
                    Toast.LENGTH_SHORT
                )
                appDownloadManager.downloadRequired.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                )
            }
            AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED -> {
                showRedownloadUi()
                isChecking = false
                Utilities.showToastUiCentered(
                    context,
                    blocklistUpdateNotRequiredText,
                    Toast.LENGTH_SHORT
                )
                appDownloadManager.downloadRequired.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED
                )
            }
            AppDownloadManager.DownloadManagerStatus.NOT_AVAILABLE -> {
                Utilities.showToastUiCentered(
                    context,
                    blocklistNotAvailableToastText,
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    fun dismissLocalBlocklistsSheet() {
        showLocalBlocklistsSheet = false
        viewModel.updateUiState()
    }

    fun proceedWithDownload(isRedownload: Boolean) {
        scope.launch(Dispatchers.Main) {
            var status = AppDownloadManager.DownloadManagerStatus.NOT_STARTED
            isDownloading = !isRedownload
            isRedownloading = isRedownload
            val currentTs = persistentState.localBlocklistTimestamp
            withContext(Dispatchers.IO) {
                status = appDownloadManager.downloadLocalBlocklist(currentTs, isRedownload)
            }
            handleDownloadStatus(status)
        }
    }

    fun downloadLocalBlocklist(isRedownload: Boolean) {
        if (VpnController.isVpnLockdown() && !persistentState.useCustomDownloadManager) {
            showLockdownDialog = true
            return
        }
        proceedWithDownload(isRedownload)
    }

    fun deleteLocalBlocklist() {
        scope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                val path = blocklistCanonicalPath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME)
                val dir = File(path)
                deleteRecursive(dir)
                persistentState.localBlocklistTimestamp = INIT_TIME_MS
                persistentState.localBlocklistStamp = ""
                persistentState.newestLocalBlocklistTimestamp = INIT_TIME_MS
            }

            updateLocalBlocklistUi()
            showCheckUpdateUi()
            Utilities.showToastUiCentered(
                context,
                configAddSuccessToastText,
                Toast.LENGTH_SHORT
            )
        }
    }

    fun isBlocklistUpdateAvailable() {
        scope.launch(Dispatchers.IO) {
            appDownloadManager.isDownloadRequired(
                com.celzero.bravedns.service.RethinkBlocklistManager.DownloadType.LOCAL
            )
        }
    }

    fun isLocalBlocklistStampAvailable(): Boolean {
        return persistentState.localBlocklistStamp.isNotEmpty()
    }

    fun setBraveDnsLocal() {
        persistentState.blocklistEnabled = true
    }

    fun removeBraveDnsLocal() {
        persistentState.blocklistEnabled = false
    }

    fun enableBlocklist() {
        if (persistentState.blocklistEnabled) {
            removeBraveDnsLocal()
            updateLocalBlocklistUi()
            return
        }

        if (!VpnController.hasTunnel()) {
            Utilities.showToastUiCentered(
                context,
                ssvToastStartRethinkText,
                Toast.LENGTH_SHORT
            )
            return
        }

        scope.launch(Dispatchers.Main) {
            val blocklistsExist = withContext(Dispatchers.Default) {
                Utilities.hasLocalBlocklists(
                    context,
                    persistentState.localBlocklistTimestamp
                )
            }

            if (blocklistsExist) {
                setBraveDnsLocal()
                if (isLocalBlocklistStampAvailable()) {
                    updateLocalBlocklistUi()
                } else {
                    dismissLocalBlocklistsSheet()
                    onLocalBlocklistConfigureClick()
                }
            } else {
                dismissLocalBlocklistsSheet()
                onLocalBlocklistConfigureClick()
            }
        }
    }

    fun invokeLocalBlocklistActivity() {
        if (!VpnController.hasTunnel()) {
            Utilities.showToastUiCentered(
                context,
                ssvToastStartRethinkText,
                Toast.LENGTH_SHORT
            )
            return
        }

        dismissLocalBlocklistsSheet()
        onLocalBlocklistConfigureClick()
    }

    fun openLocalBlocklist() {
        updateLocalBlocklistUi()
        initLocalBlocklistVersion()
        showLocalBlocklistsSheet = true
    }

    fun showSystemDnsDialog(dns: String) {
        systemDnsDialogText = dns
        showSystemDnsDialog = true
    }

    fun showSmartDnsInfoDialog() {
        scope.launch(Dispatchers.IO) {
            val ids = VpnController.getPlusResolvers()
            val dnsList: MutableList<String> = mutableListOf()
            ids.forEach {
                val index = it.substringAfter(Backend.Plus).getOrNull(0)
                if (index == null) {
                    Logger.w(LOG_TAG_DNS, "smart(plus) dns resolver id is empty: $it")
                    return@forEach
                }
                if (index != DOH_INDEX && index != DOT_INDEX) {
                    Logger.w(LOG_TAG_DNS, "smart(plus) dns resolver id is not doh or dot: $it")
                    return@forEach
                }
                val transport = VpnController.getPlusTransportById(it)
                val address = transport?.addr?.tos() ?: ""
                if (address.isNotEmpty()) dnsList.add(address)
            }

            Logger.i(LOG_TAG_DNS, "smart(plus) dns list size: ${dnsList.size}")
            withContext(Dispatchers.Main) {
                val stringBuilder = StringBuilder()
                val desc = smartDnsDescriptionText
                stringBuilder.append(desc).append("\n\n")
                dnsList.forEach {
                    val txt = "$symbolStarText $it"
                    stringBuilder.append(txt).append("\n")
                }
                smartDnsDialogText = stringBuilder.toString()
                showSmartDnsDialog = true
            }
        }
    }

    // Initialize local blocklist state
    LaunchedEffect(Unit) {
        updateLocalBlocklistUi()
        initLocalBlocklistVersion()
    }

    val workManager = WorkManager.getInstance(context)
    val downloadRequiredStatus by appDownloadManager.downloadRequired
        .asFlow()
        .collectAsStateWithLifecycle(initialValue = AppDownloadManager.DownloadManagerStatus.NOT_STARTED)
    val customDownloadWorkInfos by workManager
        .getWorkInfosByTagLiveData(LocalBlocklistCoordinator.CUSTOM_DOWNLOAD)
        .asFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadTagWorkInfos by workManager
        .getWorkInfosByTagLiveData(DownloadConstants.DOWNLOAD_TAG)
        .asFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val fileTagWorkInfos by workManager
        .getWorkInfosByTagLiveData(DownloadConstants.FILE_TAG)
        .asFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(downloadRequiredStatus) {
        Napier.i("Check for blocklist update, status: $downloadRequiredStatus")
        if (downloadRequiredStatus != AppDownloadManager.DownloadManagerStatus.NOT_STARTED) {
            handleDownloadStatus(downloadRequiredStatus)
        }
    }

    LaunchedEffect(customDownloadWorkInfos) {
        val workInfo = customDownloadWorkInfos.getOrNull(0) ?: return@LaunchedEffect
        Napier.i("WorkManager state: ${workInfo.state} for ${LocalBlocklistCoordinator.CUSTOM_DOWNLOAD}")
        if (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING) {
            isDownloading = true
        } else if (workInfo.state == WorkInfo.State.SUCCEEDED) {
            isDownloading = false
            showUpdateUi()
            workManager.pruneWork()
        } else if (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED) {
            isDownloading = false
            Utilities.showToastUiCentered(
                context,
                blocklistUpdateFailureText,
                Toast.LENGTH_SHORT
            )
            workManager.pruneWork()
            workManager.cancelAllWorkByTag(LocalBlocklistCoordinator.CUSTOM_DOWNLOAD)
        }
    }

    LaunchedEffect(downloadTagWorkInfos) {
        val workInfo = downloadTagWorkInfos.getOrNull(0) ?: return@LaunchedEffect
        Napier.i("WorkManager state: ${workInfo.state} for ${DownloadConstants.DOWNLOAD_TAG}")
        if (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING) {
            isDownloading = true
        } else if (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED) {
            isDownloading = false
            Utilities.showToastUiCentered(
                context,
                blocklistUpdateFailureText,
                Toast.LENGTH_SHORT
            )
            workManager.pruneWork()
            workManager.cancelAllWorkByTag(DownloadConstants.DOWNLOAD_TAG)
            workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
        }
    }

    LaunchedEffect(fileTagWorkInfos) {
        val workInfo = fileTagWorkInfos.getOrNull(0) ?: return@LaunchedEffect
        if (workInfo.state == WorkInfo.State.SUCCEEDED) {
            isDownloading = false
            showUpdateUi()
            workManager.pruneWork()
        } else if (workInfo.state == WorkInfo.State.CANCELLED || workInfo.state == WorkInfo.State.FAILED) {
            isDownloading = false
            Utilities.showToastUiCentered(
                context,
                blocklistUpdateFailureText,
                Toast.LENGTH_SHORT
            )
            workManager.pruneWork()
            workManager.cancelAllWorkByTag(DownloadConstants.FILE_TAG)
        }
    }

    // Observe lifecycle for onResume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updateUiState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Main content
    DnsSettingsScreen(
        uiState = uiState,
        initialFocusKey = initialFocusKey,
        onRefreshClick = { viewModel.refreshDns() },
        onSystemDnsClick = { viewModel.enableSystemDns() },
        onSystemDnsInfoClick = {
            scope.launch(Dispatchers.IO) {
                val sysDns = VpnController.getSystemDns()
                withContext(Dispatchers.Main) {
                    showSystemDnsDialog(sysDns)
                }
            }
        },
        onCustomDnsClick = onCustomDnsClick,
        onRethinkPlusDnsClick = onRethinkPlusDnsClick,
        onSmartDnsClick = { viewModel.enableSmartDns() },
        onSmartDnsInfoClick = { showSmartDnsInfoDialog() },
        onLocalBlocklistClick = { openLocalBlocklist() },
        onCustomDownloaderChange = { viewModel.setUseCustomDownloadManager(it) },
        onPeriodicUpdateChange = { viewModel.setPeriodicallyCheckBlocklistUpdate(it) },
        onDnsAlgChange = { viewModel.setDnsAlgEnabled(it) },
        onSplitDnsChange = { viewModel.setSplitDns(it) },
        onBypassDnsBlockChange = { viewModel.setBypassBlockInDns(it) },
        onAllowedRecordTypesClick = { showRecordTypesSheet = true },
        onFavIconChange = { viewModel.setFavIconEnabled(it) },
        onDnsCacheChange = { viewModel.setEnableDnsCache(it) },
        onProxyDnsChange = { viewModel.setProxyDns(it) },
        onUndelegatedDomainsChange = { viewModel.setUseSystemDnsForUndelegatedDomains(it) },
        onFallbackChange = { viewModel.setUseFallbackDnsToBypass(it) },
        onPreventLeaksChange = { viewModel.setPreventDnsLeaksEnabled(it) }
    )

    // DNS Record Types Sheet
    if (showRecordTypesSheet) {
        DnsRecordTypesSheet(
            persistentState = persistentState,
            onDismiss = { showRecordTypesSheet = false }
        )
    }

    // System DNS Dialog
    if (showSystemDnsDialog) {
        RethinkMultiActionDialog(
            onDismissRequest = { showSystemDnsDialog = false },
            title = stringResource(R.string.network_dns),
            message = systemDnsDialogText,
            primaryText = stringResource(R.string.ada_noapp_dialog_positive),
            onPrimary = { showSystemDnsDialog = false },
            secondaryText = stringResource(R.string.dns_info_neutral),
            onSecondary = {
                UIUtils.clipboardCopy(
                    context,
                    systemDnsDialogText,
                    copyClipboardLabelText
                )
                Utilities.showToastUiCentered(
                    context,
                    infoDialogUrlCopyToastText,
                    Toast.LENGTH_SHORT
                )
                showSystemDnsDialog = false
            }
        )
    }

    // Smart DNS Dialog
    if (showSmartDnsDialog) {
        RethinkMultiActionDialog(
            onDismissRequest = { showSmartDnsDialog = false },
            title = stringResource(R.string.smart_dns),
            message = smartDnsDialogText,
            primaryText = stringResource(R.string.ada_noapp_dialog_positive),
            onPrimary = { showSmartDnsDialog = false },
            secondaryText = stringResource(R.string.dns_info_neutral),
            onSecondary = {
                UIUtils.clipboardCopy(
                    context,
                    smartDnsDialogText,
                    copyClipboardLabelText
                )
                Utilities.showToastUiCentered(
                    context,
                    infoDialogUrlCopyToastText,
                    Toast.LENGTH_SHORT
                )
                showSmartDnsDialog = false
            }
        )
    }

    // Local Blocklists Sheet
    if (showLocalBlocklistsSheet) {
        LocalBlocklistsSheet(
            headingText = headingText,
            versionText = versionText,
            canConfigure = canConfigure,
            canCopy = canCopy,
            canSearch = canSearch,
            showCheckDownload = showCheckDownload,
            showDownload = showDownload,
            showRedownload = showRedownload,
            isChecking = isChecking,
            isDownloading = isDownloading,
            isRedownloading = isRedownloading,
            isBlocklistEnabled = persistentState.blocklistEnabled,
            onDismiss = { dismissLocalBlocklistsSheet() },
            onEnableBlocklist = { enableBlocklist() },
            onConfigure = { invokeLocalBlocklistActivity() },
            onCopy = {
                val url = Constants.RETHINK_BASE_URL_MAX + persistentState.localBlocklistStamp
                UIUtils.clipboardCopy(
                    context,
                    url,
                    copyClipboardLabelText
                )
                Utilities.showToastUiCentered(
                    context,
                    infoDialogRethinkToastText,
                    Toast.LENGTH_SHORT
                )
            },
            onSearch = {
                dismissLocalBlocklistsSheet()
                val url = RETHINK_SEARCH_URL + Uri.encode(persistentState.localBlocklistStamp)
                UIUtils.openUrl(context, url)
            },
            onCheckUpdate = {
                isChecking = true
                isBlocklistUpdateAvailable()
            },
            onDownload = {
                downloadDialogIsRedownload = false
                showDownloadDialog = true
            },
            onRedownload = {
                downloadDialogIsRedownload = true
                showDownloadDialog = true
            },
            onDelete = { showDeleteDialog = true }
        )
    }

    // Download Dialog
    if (showDownloadDialog) {
        val title = if (downloadDialogIsRedownload) {
            stringResource(R.string.local_blocklist_redownload)
        } else {
            stringResource(R.string.local_blocklist_download)
        }
        val message = if (downloadDialogIsRedownload) {
            stringResource(
                R.string.local_blocklist_redownload_desc,
                convertLongToTime(
                    persistentState.localBlocklistTimestamp,
                    Constants.TIME_FORMAT_2
                )
            )
        } else {
            stringResource(R.string.local_blocklist_download_desc)
        }
        RethinkConfirmDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = title,
            message = message,
            confirmText = stringResource(R.string.settings_local_blocklist_dialog_positive),
            dismissText = stringResource(R.string.lbl_cancel),
            onConfirm = {
                showDownloadDialog = false
                downloadLocalBlocklist(downloadDialogIsRedownload)
            },
            onDismiss = { showDownloadDialog = false }
        )
    }

    // Delete Dialog
    if (showDeleteDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.lbl_delete),
            message = stringResource(R.string.local_blocklist_delete_desc),
            confirmText = stringResource(R.string.lbl_delete),
            dismissText = stringResource(R.string.lbl_cancel),
            isConfirmDestructive = true,
            onConfirm = {
                showDeleteDialog = false
                deleteLocalBlocklist()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    // Lockdown Dialog
    if (showLockdownDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showLockdownDialog = false },
            title = stringResource(R.string.lockdown_download_enable_inapp),
            message = stringResource(R.string.lockdown_download_message),
            confirmText = stringResource(R.string.lockdown_download_enable_inapp),
            dismissText = stringResource(R.string.lbl_cancel),
            onConfirm = {
                showLockdownDialog = false
                persistentState.useCustomDownloadManager = true
                downloadLocalBlocklist(downloadDialogIsRedownload)
            },
            onDismiss = {
                showLockdownDialog = false
                proceedWithDownload(downloadDialogIsRedownload)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsRecordTypesSheet(
    persistentState: PersistentState,
    onDismiss: () -> Unit
) {
    var isAutoMode by remember { mutableStateOf(persistentState.dnsRecordTypesAutoMode) }
    val selected = remember {
        mutableStateListOf<String>().apply {
            addAll(getInitialRecordSelection(persistentState))
        }
    }

    val allTypes = remember {
        ResourceRecordTypes.entries.filter { it != ResourceRecordTypes.UNKNOWN }
    }

    val sortedTypes by remember {
        derivedStateOf { allTypes.sortedBy { it.name } }
    }
    val selectedCount = if (isAutoMode) allTypes.size else selected.size

    RuleSheetModal(onDismissRequest = onDismiss) {
        RethinkBottomSheetCard(
            modifier = Modifier.padding(horizontal = Dimensions.screenPaddingHorizontal),
            contentPadding = PaddingValues(Dimensions.cardPadding)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(Dimensions.iconContainerMd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_allow_dns_records),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(Dimensions.iconSizeSm)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
                ) {
                    Text(
                        text = stringResource(R.string.cd_allowed_dns_record_types_heading),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.cd_allowed_dns_record_types_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)) {
                RuleSheetSummaryPill(
                    text = if (isAutoMode) {
                        stringResource(R.string.settings_ip_text_ipv46)
                    } else {
                        stringResource(R.string.lbl_manual)
                    }
                )
                RuleSheetSummaryPill(
                    text = "${stringResource(R.string.rt_filter_parent_selected)} $selectedCount/${allTypes.size}"
                )
            }

            RethinkTwoOptionSegmentedRow(
                leftLabel = stringResource(R.string.settings_ip_text_ipv46),
                rightLabel = stringResource(R.string.lbl_manual),
                leftSelected = isAutoMode,
                onLeftClick = {
                    if (!isAutoMode) {
                        isAutoMode = true
                        persistentState.dnsRecordTypesAutoMode = true
                    }
                },
                onRightClick = {
                    if (isAutoMode) {
                        isAutoMode = false
                        persistentState.dnsRecordTypesAutoMode = false
                    }
                }
            )
        }

        SectionHeader(title = stringResource(R.string.lbl_allowed))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.screenPaddingHorizontal),
            contentPadding = PaddingValues(bottom = Dimensions.spacing2xl),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
        ) {
            itemsIndexed(
                items = sortedTypes,
                key = { _, type -> type.value }
            ) { index, type ->
                val position = when {
                    sortedTypes.size == 1 -> CardPosition.Single
                    index == 0 -> CardPosition.First
                    index == sortedTypes.lastIndex -> CardPosition.Last
                    else -> CardPosition.Middle
                }
                RecordTypeRow(
                    type = type,
                    isAutoMode = isAutoMode,
                    isSelected = selected.contains(type.name),
                    position = position,
                    onToggle = {
                        if (isAutoMode) return@RecordTypeRow
                        if (selected.contains(type.name)) {
                            selected.remove(type.name)
                        } else {
                            selected.add(type.name)
                        }
                        persistentState.setAllowedDnsRecordTypes(selected.toSet())
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordTypeRow(
    type: ResourceRecordTypes,
    isAutoMode: Boolean,
    isSelected: Boolean,
    position: CardPosition,
    onToggle: () -> Unit
) {
    val containerColor = if (isSelected && !isAutoMode) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    RethinkListItem(
        headline = type.name,
        supporting = type.desc,
        position = position,
        defaultContainerColor = containerColor,
        enabled = !isAutoMode,
        onClick = onToggle,
        trailing = {
            Box(
                modifier = Modifier.width(Dimensions.touchTargetMin),
                contentAlignment = Alignment.CenterEnd
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { _ ->
                        if (!isAutoMode) {
                            onToggle()
                        }
                    },
                    enabled = !isAutoMode
                )
            }
        }
    )
}

private fun getInitialRecordSelection(persistentState: PersistentState): List<String> {
    if (!persistentState.dnsRecordTypesAutoMode) {
        return persistentState.getAllowedDnsRecordTypes().toList()
    }
    val storedSelection = persistentState.allowedDnsRecordTypesString
    if (storedSelection.isNotEmpty()) {
        return storedSelection.split(",").filter { it.isNotEmpty() }
    }
    return listOf(
        ResourceRecordTypes.A.name,
        ResourceRecordTypes.AAAA.name,
        ResourceRecordTypes.CNAME.name,
        ResourceRecordTypes.HTTPS.name,
        ResourceRecordTypes.SVCB.name
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalBlocklistsSheet(
    headingText: String,
    versionText: String,
    canConfigure: Boolean,
    canCopy: Boolean,
    canSearch: Boolean,
    showCheckDownload: Boolean,
    showDownload: Boolean,
    showRedownload: Boolean,
    isChecking: Boolean,
    isDownloading: Boolean,
    isRedownloading: Boolean,
    isBlocklistEnabled: Boolean,
    onDismiss: () -> Unit,
    onEnableBlocklist: () -> Unit,
    onConfigure: () -> Unit,
    onCopy: () -> Unit,
    onSearch: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownload: () -> Unit,
    onRedownload: () -> Unit,
    onDelete: () -> Unit
) {
    RuleSheetModal(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimensions.screenPaddingHorizontal,
                    vertical = Dimensions.spacingSm
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            RethinkBottomSheetCard(contentPadding = PaddingValues(Dimensions.cardPadding)) {
                Text(
                    text = headingText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (versionText.isNotEmpty()) {
                    Text(
                        text = versionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionHeader(title = stringResource(R.string.lbbs_state_header))
            RethinkListGroup {
                RethinkActionListItem(
                    title = if (isBlocklistEnabled) {
                        stringResource(R.string.lbbs_toggle_off)
                    } else {
                        stringResource(R.string.lbbs_toggle_on)
                    },
                    description = stringResource(R.string.lbbs_toggle_desc),
                    iconRes = R.drawable.ic_local_blocklist,
                    position = CardPosition.Single,
                    onClick = onEnableBlocklist
                )
            }

            SectionHeader(title = stringResource(R.string.lbbs_actions_header))
            RethinkListGroup {
                RethinkActionListItem(
                    title = stringResource(R.string.lbbs_configure),
                    iconRes = R.drawable.ic_settings,
                    position = CardPosition.First,
                    enabled = canConfigure,
                    onClick = onConfigure
                )
                RethinkActionListItem(
                    title = stringResource(R.string.lbbs_copy),
                    iconRes = R.drawable.ic_copy,
                    position = CardPosition.Middle,
                    enabled = canCopy,
                    onClick = onCopy
                )
                RethinkActionListItem(
                    title = stringResource(R.string.lbbs_search),
                    iconRes = R.drawable.ic_search,
                    position = CardPosition.Last,
                    enabled = canSearch,
                    onClick = onSearch
                )
            }

            SectionHeader(title = stringResource(R.string.lbbs_maintenance_header))
            RethinkListGroup {
                var maintenanceIndex = 0
                val maintenanceCount =
                    (if (showCheckDownload) 1 else 0) +
                        (if (showDownload) 1 else 0) +
                        (if (showRedownload) 1 else 0) +
                        1

                fun pos(): CardPosition {
                    return when {
                        maintenanceCount == 1 -> CardPosition.Single
                        maintenanceIndex == 0 -> CardPosition.First
                        maintenanceIndex == maintenanceCount - 1 -> CardPosition.Last
                        else -> CardPosition.Middle
                    }
                }

                if (showCheckDownload) {
                    RethinkActionListItem(
                        title = stringResource(R.string.lbbs_update_check),
                        iconRes = R.drawable.ic_blocklist_update_check,
                        position = pos(),
                        enabled = !isChecking,
                        trailing = if (isChecking) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            null
                        },
                        onClick = onCheckUpdate
                    )
                    maintenanceIndex += 1
                }
                if (showDownload) {
                    RethinkActionListItem(
                        title = stringResource(R.string.local_blocklist_download),
                        iconRes = R.drawable.ic_update,
                        position = pos(),
                        enabled = !isDownloading,
                        trailing = if (isDownloading) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            null
                        },
                        onClick = onDownload
                    )
                    maintenanceIndex += 1
                }
                if (showRedownload) {
                    RethinkActionListItem(
                        title = stringResource(R.string.local_blocklist_redownload),
                        iconRes = R.drawable.ic_update,
                        position = pos(),
                        enabled = !isRedownloading,
                        trailing = if (isRedownloading) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            null
                        },
                        onClick = onRedownload
                    )
                    maintenanceIndex += 1
                }
                RethinkActionListItem(
                    title = stringResource(R.string.lbl_delete),
                    iconRes = R.drawable.ic_delete,
                    position = pos(),
                    onClick = onDelete
                )
            }
        }
    }
}
