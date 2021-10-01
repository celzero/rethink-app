/*
 * Copyright 2021 RethinkDNS and its authors
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
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityCustomDomainsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class CustomDomainActivity : AppCompatActivity(R.layout.activity_custom_domains) {
    private val b by viewBinding(ActivityCustomDomainsBinding::bind)
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
    }

    private fun init() {
        val allowedDomainFragment = CustomDomainFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                          allowedDomainFragment,
                                                          allowedDomainFragment.javaClass.simpleName).commit()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
}
