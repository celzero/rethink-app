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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.fragment.RethinkBlocklistFragment
import com.celzero.bravedns.ui.fragment.RethinkListFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class ConfigureRethinkBasicActivity : AppCompatActivity(R.layout.fragment_rethink_basic) {
    private val persistentState by inject<PersistentState>()

    enum class FragmentLoader {
        REMOTE,
        LOCAL,
        DB_LIST
    }

    companion object {
        const val INTENT = "RethinkDns_Intent"
        const val RETHINK_BLOCKLIST_TYPE = "RethinkBlocklistType"
        const val RETHINK_BLOCKLIST_NAME = "RethinkBlocklistName"
        const val RETHINK_BLOCKLIST_URL = "RethinkBlocklistUrl"
        const val UID = "UID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        val type = intent.getIntExtra(INTENT, FragmentLoader.REMOTE.ordinal)
        val fl = FragmentLoader.entries[type]
        val fragment = fragment(fl)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .add(R.id.root_container, fragment, fragment.javaClass.simpleName)
                .commit()
        } else {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.root_container, fragment, fragment.javaClass.simpleName)
                .commit()
        }
    }

    private fun fragment(fl: FragmentLoader): Fragment {
        // return fragment based on the received intent
        return when (fl) {
            FragmentLoader.REMOTE -> {
                val name = intent.getStringExtra(RETHINK_BLOCKLIST_NAME) ?: ""
                val url = intent.getStringExtra(RETHINK_BLOCKLIST_URL) ?: ""

                // load the Rethink remote dns configure screen (default)
                val rr = RethinkBlocklistFragment.newInstance()
                var bundle =
                    createBundle(
                        RETHINK_BLOCKLIST_TYPE,
                        RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
                    )
                bundle = updateBundle(bundle, RETHINK_BLOCKLIST_NAME, name)
                rr.arguments = updateBundle(bundle, RETHINK_BLOCKLIST_URL, url)
                rr
            }
            FragmentLoader.LOCAL -> {
                // load the local blocklist configure screen
                val rl = RethinkBlocklistFragment.newInstance()
                rl.arguments =
                    createBundle(
                        RETHINK_BLOCKLIST_TYPE,
                        RethinkBlocklistManager.RethinkBlocklistType.LOCAL.ordinal
                    )
                rl
            }
            FragmentLoader.DB_LIST -> {
                // load the list of already added rethink doh urls
                val r = RethinkListFragment.newInstance()
                r.arguments = createBundle(UID, Constants.MISSING_UID)
                r
            }
        }
    }

    private fun createBundle(id: String, value: Int): Bundle {
        val bundle = Bundle()
        bundle.putInt(id, value)
        return bundle
    }

    private fun updateBundle(bundle: Bundle, id: String, value: String): Bundle {
        bundle.putString(id, value)
        return bundle
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
