/*
 * Copyright 2024 RethinkDNS and its authors
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
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppConnectionAdapter
import com.celzero.bravedns.databinding.ActivityAppWiseLogsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AppWiseLogsActivity :
    AppCompatActivity(R.layout.activity_app_wise_logs), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityAppWiseLogsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()
    private var uid: Int = Constants.INVALID_UID
    private var layoutManager: RecyclerView.LayoutManager? = null

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        uid = intent.getIntExtra(AppInfoActivity.UID_INTENT_NAME, Constants.INVALID_UID)
        if (uid == Constants.INVALID_UID) {
            finish()
        }
        b.awlSearch.setOnQueryTextListener(this)
        setAdapter()
    }

    private fun setAdapter() {
        networkLogsViewModel.setUid(uid)
        b.awlRecyclerConnection.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(this)
        b.awlRecyclerConnection.layoutManager = layoutManager
        val recyclerAdapter = AppConnectionAdapter(this, this, uid)
        networkLogsViewModel.allAppNetworkLogs.observe(this) {
            recyclerAdapter.submitData(this.lifecycle, it)
        }
        b.awlRecyclerConnection.adapter = recyclerAdapter
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        networkLogsViewModel.setFilter(query, AppConnectionsViewModel.FilterType.ALL)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        Utilities.delay(500, lifecycleScope) {
            if (!this.isFinishing) {
                networkLogsViewModel.setFilter(query, AppConnectionsViewModel.FilterType.ALL)
            }
        }
        return true
    }
}
