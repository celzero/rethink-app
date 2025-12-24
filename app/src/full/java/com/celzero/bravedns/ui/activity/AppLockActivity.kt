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
import android.app.ComponentCaller
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.LauncherSwitcher
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class AppLockActivity : AppCompatActivity(R.layout.activity_app_lock) {
    private val persistentState by inject<PersistentState>()

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt

    companion object {
        private const val TAG = "AppLockUi"
        const val APP_LOCK_ALIAS = ".ui.activity.LauncherAliasAppLock"
        const val HOME_ALIAS = ".ui.LauncherAliasHome"
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        if (!isBiometricEnabled() || isAppRunningOnTv()) {
            Logger.v(LOG_TAG_UI, "$TAG biometric authentication disabled or running on TV")

            // if the app lock alias is enabled, switch to home alias
            if (!LauncherSwitcher.isAliasEnabled(applicationContext, APP_LOCK_ALIAS)) {
                Logger.v(LOG_TAG_UI, "$TAG switching launcher alias to home")
                startHomeActivity()
                return
            }

            // if the app lock alias is not enabled, switch to home alias
            LauncherSwitcher.switchLauncherAlias(applicationContext, HOME_ALIAS, APP_LOCK_ALIAS)

            startHomeActivity()
            return
        }

        val lastAuthTime = persistentState.biometricAuthTime

        // if the biometric authentication is already done in the last configured mins, then skip
        var delay = MiscSettingsActivity.BioMetricType.fromValue(persistentState.biometricAuthType).mins

        // this is for backward compatibility with older versions
        // if enabled and lastUnlockTime is -1, then set it to 15 mins(maximum value)
        delay = if (delay == -1L) {
                MiscSettingsActivity.BioMetricType.FIFTEEN_MIN.mins
            } else {
                delay
            }

        Logger.d(LOG_TAG_UI, "$TAG timeout: $delay, last auth: $lastAuthTime")
        val timeSinceLastAuth = abs(SystemClock.elapsedRealtime() - lastAuthTime)
        if (timeSinceLastAuth < TimeUnit.MINUTES.toMillis(delay)) {
            Logger.i(LOG_TAG_UI, "$TAG biometric auth skipped, time since last auth: $timeSinceLastAuth")
            startHomeActivity()
            return
        }

        showBiometricPrompt()
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        setIntent(intent)
    }

    private fun showBiometricPrompt() {
        Logger.v(LOG_TAG_UI, "$TAG showing biometric prompt")
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Logger.i(LOG_TAG_UI, "$TAG auth error(code: $errorCode): $errString")
                    Logger.v(LOG_TAG_UI, "$TAG biometric auth err, finishing activity")
                    showToastUiCentered(this@AppLockActivity, errString.toString(), Toast.LENGTH_SHORT)
                    finishAffinity()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
                    startHomeActivity()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Logger.i(LOG_TAG_UI, "$TAG biometric authentication failed")
                }
            })

        val promptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.hs_biometeric_title))
                .setSubtitle(getString(R.string.hs_biometeric_desc))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .setConfirmationRequired(false)
                .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun startHomeActivity() {
        Logger.v(LOG_TAG_UI, "$TAG starting home activity")
        val intent = Intent(this, HomeScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun isBiometricEnabled(): Boolean {
        val type = MiscSettingsActivity.BioMetricType.fromValue(persistentState.biometricAuthType)
        // use the biometricAuth flag for backward compatibility with older version
        return type.enabled()
    }

    // check if app running on TV
    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
    }
}
