/*
 * Copyright 2023 RethinkDNS and its authors
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
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AlertAdapter
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AlertRegistry
import com.celzero.bravedns.databinding.ActivityAlertsBinding
import com.celzero.bravedns.service.AlertCategory
import com.celzero.bravedns.service.AlertSeverity
import com.celzero.bravedns.service.AlertType
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.viewmodel.AlertsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AlertsActivity : AppCompatActivity(R.layout.activity_alerts) {

    /*private val b by viewBinding(ActivityAlertsBinding::bind)
    private var alertAdapter: AlertAdapter? = null
    private val persistentState by inject<PersistentState>()
    private val alertsViewModel: AlertsViewModel by viewModel()
    private val alerts: Array<AlertRegistry?> = Array(size = 3, init = { null })

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        observeIpBlock()
        observeAppBlock()
        observeDnsBlock()
        setupRecyclerView()
    }

    private fun observeIpBlock() {
        // observe top 3 blocked ip addresses from the database (ConnectionTrackerRepository)
        // and display them in a recycler view
        alertsViewModel.getBlockedIpLogList().observe(this) { process(it, AlertCategory.FIREWALL) }
    }

    private fun process(it: List<AppConnection>, category: AlertCategory) {
        // process the list of AppConnection objects
        // and convert them to AlertRegistry objects
        // and add them to the alerts list
        val pos =
            when (category) {
                AlertCategory.APP -> 0
                AlertCategory.DNS -> 1
                AlertCategory.FIREWALL -> 2
                else -> 0
            }
        alerts[pos] = convertToAlert(it, category)
        notifyDatasetChanged()
    }

    private fun convertToAlert(it: List<AppConnection>, category: AlertCategory): AlertRegistry {
        // convert AppConnection to AlertRegistry
        val type = AlertType.INFO.name
        val title =
            when (category) {
                AlertCategory.FIREWALL -> "IP Block"
                AlertCategory.APP -> "App Block"
                AlertCategory.DNS -> "Domain Block"
                else -> "Unknown"
            }
        var message = ""

        when (category) {
            AlertCategory.FIREWALL ->
                it.forEach {
                    message +=
                        "Blocked ${it.ipAddress} from accessing the network for ${it.count} times. \n"
                }
            AlertCategory.APP ->
                it.forEach {
                    message +=
                        "Blocked app ${it.appOrDnsName} tried accessing the network for ${it.count} times. \n"
                }
            AlertCategory.DNS ->
                it.forEach {
                    message +=
                        "Blocked ${it.appOrDnsName} from accessing the network for ${it.count} times. \n"
                }
            else -> message = "Unknown"
        }

        val severity = AlertSeverity.LOW.name
        val actions = "Check network logs to either allow or block the connection"
        return AlertRegistry(
            id = 0 *//* id *//*, // Room auto-increments id when its set to zero.
            title,
            type,
            1,
            System.currentTimeMillis(),
            message,
            category.name,
            severity,
            actions,
            alertStatus = "" *//* alertStatus *//*,
            alertSolution = "" *//* alertSolution *//*,
            isRead = false *//* isRead *//*,
            isDeleted = false *//* isDeleted *//*,
            isCustom = false *//* isCustom *//*,
            isNotified = false *//* isNotified *//*
        )
    }

    private fun observeAppBlock() {
        // observe top 3 blocked ip addresses from the database (ConnectionTrackerRepository)
        // and display them in a recycler view
        alertsViewModel.getBlockedAppsLogList().observe(this) { process(it, AlertCategory.APP) }
    }

    private fun observeDnsBlock() {
        io {
            val isAppBypassed = FirewallManager.isAnyAppBypassesDns()
            uiCtx {
                alertsViewModel.getBlockedDnsLogList(isAppBypassed).observe(this) {
                    process(it, AlertCategory.DNS)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        alertAdapter = AlertAdapter(this, alerts)
        val layoutManager = LinearLayoutManager(this)
        b.alertsRecyclerView.layoutManager = layoutManager
        b.alertsRecyclerView.adapter = alertAdapter
    }

    private fun notifyDatasetChanged() {
        alertAdapter?.notifyDataSetChanged()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }*/
}
