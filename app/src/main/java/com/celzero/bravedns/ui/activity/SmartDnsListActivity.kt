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
import androidx.core.view.WindowInsetsControllerCompat
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivitySmartDnsListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.fragment.SmartDnsListFragment
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class SmartDnsListActivity : BaseActivity(R.layout.activity_smart_dns_list) {
    private val b by viewBinding(ActivitySmartDnsListBinding::bind)

    private val persistentState by inject<PersistentState>()

    companion object {
        fun getIntent(context: Context): Intent {
            return Intent(context, SmartDnsListActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        b.smartDnsListHeading.text = getString(R.string.smart_dns)

        supportFragmentManager.beginTransaction()
            .replace(R.id.smart_dns_list_fragment_container, SmartDnsListFragment.newInstance())
            .commit()
    }
}
