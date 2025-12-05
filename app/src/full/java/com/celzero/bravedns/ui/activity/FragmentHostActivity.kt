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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class FragmentHostActivity : AppCompatActivity(R.layout.activity_fragment_host) {
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val EXTRA_FRAGMENT_CLASS_NAME = "extra_fragment_class_name"
        private const val EXTRA_FRAGMENT_ARGUMENTS = "extra_fragment_arguments"
        private const val STATE_FRAGMENT_CLASS_NAME = "state_fragment_class_name"

        /**
         * Helper function to create an intent to show any fragment
         * @param context Context to start activity
         * @param fragmentClass Class of the Fragment to load
         * @param args optional Bundle of arguments to pass to fragment
         */
        fun createIntent(
            context: Context,
            fragmentClass: Class<out Fragment>,
            args: Bundle? = null
        ): Intent {
            val intent = Intent(context, FragmentHostActivity::class.java)
            intent.putExtra(EXTRA_FRAGMENT_CLASS_NAME, fragmentClass.name)
            if (args != null) {
                intent.putExtra(EXTRA_FRAGMENT_ARGUMENTS, args)
            }
            return intent
        }
    }

    private var fragmentClassName: String? = null

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        setContentView(R.layout.activity_fragment_host)

        // Retrieve fragment class name from savedInstanceState or intent
        fragmentClassName = savedInstanceState?.getString(STATE_FRAGMENT_CLASS_NAME)
            ?: intent.getStringExtra(EXTRA_FRAGMENT_CLASS_NAME)

        if (fragmentClassName == null) {
            Logger.e(LOG_TAG_UI, "Fragment class name not provided, finishing activity")
            finish()
            return
        }

        if (savedInstanceState == null) {
            // Only create fragment on first launch, not on recreation
            val arguments = intent.getBundleExtra(EXTRA_FRAGMENT_ARGUMENTS)

            try {
                // Instantiate fragment from class name
                val fragment = supportFragmentManager.fragmentFactory.instantiate(
                    classLoader,
                    fragmentClassName!!
                ).apply {
                    if (arguments != null) this.arguments = arguments
                    Logger.i(LOG_TAG_UI, "Loading fragment: $fragmentClassName with arguments: $arguments")
                }

                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .commit()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "Error instantiating fragment: $fragmentClassName", e)
                finish()
            }
        } else {
            // Fragment will be automatically restored by FragmentManager
            Logger.i(LOG_TAG_UI, "Restoring fragment: $fragmentClassName from savedInstanceState")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save fragment class name to restore after process death
        fragmentClassName?.let {
            outState.putString(STATE_FRAGMENT_CLASS_NAME, it)
        }
    }
}
