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
package com.celzero.bravedns.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentRethinkBasicBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class ConfigureRethinkBasicActivity : AppCompatActivity(R.layout.fragment_rethink_basic) {
    private val b by viewBinding(FragmentRethinkBasicBinding::bind)
    private val persistentState by inject<PersistentState>()

    enum class FragmentLoader {
        REMOTE, LOCAL, DB_LIST;
    }

    companion object {
        const val INTENT = "RethinkDns_Intent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        loadFragment()
    }

    private fun loadFragment() {
        // add the fragment to the activity based on the received intent
        val i = intent.getIntExtra(INTENT, FragmentLoader.REMOTE.ordinal)
        when (i) {
            FragmentLoader.REMOTE.ordinal -> {
                // load the Rethink remote dns configure screen (default)
                val rethinkRemote = RethinkRemoteBlocklistFragment.newInstance()
                supportFragmentManager.beginTransaction().replace(R.id.root_container,
                                                                  rethinkRemote, rethinkRemote.javaClass.simpleName).commit()
                return
            }
            FragmentLoader.LOCAL.ordinal -> {
                // load the local blocklist configure screen
                val rethinkLocal = RethinkLocalBlocklistFragment.newInstance()
                supportFragmentManager.beginTransaction().replace(R.id.root_container,
                                                                  rethinkLocal, rethinkLocal.javaClass.simpleName).commit()
                return
            }
            FragmentLoader.DB_LIST.ordinal -> {
                // load the list of already added rethink doh urls
                val r = RethinkListFragment.newInstance()
                val bundle = Bundle()
                bundle.putInt("UID", Constants.MISSING_UID)
                r.arguments = bundle
                supportFragmentManager.beginTransaction().replace(R.id.root_container, r, r.javaClass.simpleName).commit()
                return
            }
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
