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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConsoleLogAdapter
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.databinding.ActivityConnIdLogsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper.applySystemBarEdgeInsets
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

// shows all log lines mentioning a single conn-id: db rows plus live go-side trace logs
class ConnIdLogsActivity : BaseActivity(R.layout.activity_conn_id_logs) {

    private val b by viewBinding(ActivityConnIdLogsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val consoleLogRepository by inject<ConsoleLogRepository>()

    private var recyclerAdapter: ConsoleLogAdapter? = null

    companion object {
        const val EXTRA_CONN_ID = "conn_id"
        private const val MAX_LOG_ROWS = 10_000
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        b.root.applySystemBarEdgeInsets()

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        val cid = intent.getStringExtra(EXTRA_CONN_ID).orEmpty()
        if (cid.isEmpty()) {
            Logger.w(LOG_TAG_UI, "conn-id missing in ConnIdLogsActivity intent")
            finish()
            return
        }

        b.connIdLogsInfoText.text =
            getString(R.string.two_argument_space, getString(R.string.conn_id_logs_title), cid)
        setAdapter()
        loadLogs(cid)
    }

    private fun setAdapter() {
        b.connIdLogsList.setHasFixedSize(true)
        b.connIdLogsList.itemAnimator = null
        b.connIdLogsList.layoutManager = LinearLayoutManager(this)
        val adapter = ConsoleLogAdapter(this)
        recyclerAdapter = adapter
        b.connIdLogsList.adapter = adapter
    }

    private fun loadLogs(cid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dbLogs = try {
                consoleLogRepository.getLogsByCid(cid, MAX_LOG_ROWS)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err loading conn-id($cid) logs: ${e.message}")
                emptyList()
            }
            val goLogs = try {
                VpnController.peekTrace(cid)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err extracting go logs for $cid: ${e.message}")
                ""
            }
            val now = System.currentTimeMillis()
            // negative ids keep go lines unique vs auto-generated console-log row ids
            val goLines = goLogs.lines()
                .filter { it.isNotBlank() }
                .mapIndexed { i, line -> ConsoleLog(-(i + 1), line, 0L, now) }
            val merged = (goLines + dbLogs)
                .sortedWith(
                    compareByDescending<ConsoleLog> { it.timestamp }
                        .thenByDescending { it.id }
                )
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                recyclerAdapter?.submitData(lifecycle, PagingData.from(merged))
                b.connIdLogsEmpty.visibility =
                    if (merged.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
