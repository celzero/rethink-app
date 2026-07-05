/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.bubble.BubbleScreen
import com.celzero.bravedns.ui.compose.theme.RethinkTheme
import com.celzero.bravedns.viewmodel.AllowedAppsBubbleViewModel
import com.celzero.bravedns.viewmodel.BlockedAppsBubbleViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * BubbleActivity - Content activity for Android Bubble notifications.
 */
// TODO-refactor: Consider migrating to navigation component
class BubbleActivity : AppCompatActivity() {
    private val connectionTrackerDAO by inject<ConnectionTrackerDAO>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val dnsLogDAO by inject<DnsLogDAO>()

    private var vpnOn by mutableStateOf(false)
    private var refreshKey by mutableIntStateOf(0)

    companion object {
        private const val TAG = "BubbleActivity"
        private const val PAGE_SIZE = 20
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Napier.d("$TAG onCreate, taskId: $taskId")

        setContent {
            RethinkTheme {
                val allowedFlow = remember(vpnOn, refreshKey) { allowedAppsFlow() }
                val blockedFlow = remember(vpnOn, refreshKey) { blockedAppsFlow() }
                val allowedItems = allowedFlow.collectAsLazyPagingItems()
                val blockedItems = blockedFlow.collectAsLazyPagingItems()

                BubbleScreen(
                    vpnOn = vpnOn,
                    allowedItems = allowedItems,
                    blockedItems = blockedItems,
                    onAllowApp = { app, onRefresh -> allowApp(app, onRefresh) },
                    onRemoveAllowed = { app, onRefresh -> removeAllowedApp(app, onRefresh) }
                )
            }
        }

        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Napier.d("$TAG onNewIntent - bubble clicked again")
    }

    override fun onResume() {
        super.onResume()
        vpnOn = VpnController.hasTunnel()
        refreshKey++
        if (!vpnOn) {
            Napier.i("$TAG VPN is off; showing empty state")
        }
    }

    private fun allowApp(blockedApp: BlockedAppInfo, onRefresh: () -> Unit) {
        lifecycleScope.launch {
            try {
                Napier.i("Temporarily allowing app for 15 minutes: ${blockedApp.appName} (uid: ${blockedApp.uid})")

                withContext(Dispatchers.IO) {
                    FirewallManager.updateTempAllow(blockedApp.uid, true)
                }

                onRefresh()
                Napier.i("App temporarily allowed successfully for 15 minutes")
            } catch (e: Exception) {
                Napier.e("err allowing app: ${e.message}")
            }
        }
    }

    private fun removeAllowedApp(allowedApp: AllowedAppInfo, onRefresh: () -> Unit) {
        lifecycleScope.launch {
            try {
                Napier.i("Removing temp allow for app: ${allowedApp.appName} (uid: ${allowedApp.uid})")

                withContext(Dispatchers.IO) {
                    appInfoRepository.clearTempAllowByUid(allowedApp.uid)
                }

                onRefresh()
                Napier.i("Temp allow removed successfully")
            } catch (e: Exception) {
                Napier.e("err removing allowed app: ${e.message}")
            }
        }
    }

    private fun allowedAppsFlow(): Flow<PagingData<AllowedAppInfo>> {
        if (!vpnOn) return flowOf(PagingData.empty())
        val now = System.currentTimeMillis()
        return Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = { AllowedAppsBubbleViewModel(appInfoRepository, now) }
        ).flow.cachedIn(lifecycleScope)
    }

    private fun blockedAppsFlow(): Flow<PagingData<BlockedAppInfo>> {
        if (!vpnOn) return flowOf(PagingData.empty())
        val now = System.currentTimeMillis()
        val last15Mins = now - (15 * 60 * 1000)
        return Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = {
                BlockedAppsBubbleViewModel(
                    connectionTrackerDAO,
                    dnsLogDAO,
                    appInfoRepository,
                    last15Mins,
                    emptySet()
                )
            }
        ).flow.cachedIn(lifecycleScope)
    }
}
