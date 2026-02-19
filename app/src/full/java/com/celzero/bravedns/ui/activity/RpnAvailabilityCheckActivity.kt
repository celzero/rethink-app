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
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityRpnAvailabililtyBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RpnAvailabilityCheckActivity : AppCompatActivity() {
    private val b by viewBinding(ActivityRpnAvailabililtyBinding::bind)
    private val persistentState by inject<PersistentState>()

    private lateinit var options: Array<String>

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "RpnAvailabilityCheckActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rpn_availabililty)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        options = listOf("WIN-US", "WIN-UK", "WIN-IN", "WIN-DE", "WIN-CA").toTypedArray()
        startChecks()
    }

    private fun startChecks() {
        var strength = 0
        val ctx = this
        lifecycleScope.launch {
            ui {
                val optionsFiltered = options.dropLast(1) // exclude Exit
                for (option in optionsFiltered) {
                    val rowView =
                        layoutInflater.inflate(
                            R.layout.item_rpn_availability_row,
                            b.statusContainer,
                            false
                        )
                    val optionText = rowView.findViewById<TextView>(R.id.optionName)
                    val resultText = rowView.findViewById<TextView>(R.id.resultText)
                    val loader = rowView.findViewById<ProgressBar>(R.id.loader)

                    optionText.text = option
                    resultText.visibility = View.GONE
                    loader.visibility = View.VISIBLE

                    b.statusContainer.addView(rowView)
                    var res = false
                    ioCtx {
                         res = false//getProxiesStatus(options.find { it == option } ?: "")
                    }

                    loader.visibility = View.GONE

                    if (res) {
                        strength++
                        resultText.text = getString(R.string.lbl_active)
                        resultText.setTextColor(UIUtils.fetchColor(ctx, R.attr.accentGood))
                        resultText.visibility = View.VISIBLE
                    } else {
                        resultText.text = getString(R.string.lbl_inactive)
                        resultText.setTextColor(UIUtils.fetchColor(ctx, R.attr.accentBad))
                        resultText.visibility = View.VISIBLE
                    }

                    Logger.i(Logger.LOG_IAB, "$TAG strength: $strength ($res)")

                    b.strengthProgress.max = 5
                    b.strengthProgress.setProgressCompat(strength, true)
                    b.strengthText.text = "$strength/${b.strengthProgress.max}"
                }
            }
        }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
