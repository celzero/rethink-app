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
package com.celzero.bravedns.ui.compose.settings

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.util.BioMetricType
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import io.github.aakira.napier.Napier
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "AppLockScreen"

/**
 * Result of the app lock authentication check.
 */
sealed interface AppLockResult {
    /** Authentication succeeded - proceed to home */
    data object Success : AppLockResult
    /** Authentication failed or was cancelled - finish the app */
    data object Failure : AppLockResult
    /** Biometric authentication is not needed (disabled, TV, or within timeout) */
    data object NotRequired : AppLockResult
    /** Waiting for user to complete biometric prompt */
    data object Pending : AppLockResult
}

/**
 * App lock screen that handles biometric/PIN authentication before allowing access to the app.
 *
 * This screen displays the app logo while the biometric prompt is shown. It handles:
 * - Checking if biometric authentication is enabled
 * - Checking if the app is running on TV (biometric not supported)
 * - Checking if the authentication timeout has not expired
 * - Showing the biometric prompt and handling callbacks
 *
 * @param persistentState The persistent state containing biometric settings
 * @param onAuthResult Callback invoked when authentication completes with the result
 */
@Composable
fun AppLockScreen(
    persistentState: PersistentState,
    onAuthResult: (AppLockResult) -> Unit
) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val biometricTitle = stringResource(R.string.hs_biometeric_title)
    val biometricDesc = stringResource(R.string.hs_biometeric_desc)

    var authState by remember { mutableStateOf<AppLockResult>(AppLockResult.Pending) }
    var biometricPrompt by remember { mutableStateOf<BiometricPrompt?>(null) }

    // Check if authentication is required
    LaunchedEffect(Unit) {
        val isRequired = checkBiometricRequired(context, persistentState)
        if (!isRequired) {
            Napier.v("$TAG biometric authentication not required")
            authState = AppLockResult.NotRequired
            onAuthResult(AppLockResult.NotRequired)
        }
    }

    // Set up biometric prompt when the composable is first composed
    DisposableEffect(context) {
        val activity = context as? FragmentActivity
        if (activity != null && authState == AppLockResult.Pending) {
            val shouldShow = checkBiometricRequired(context, persistentState)
            if (shouldShow) {
                val executor = ContextCompat.getMainExecutor(context)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Napier.i("$TAG auth error(code: $errorCode): $errString")
                        showToastUiCentered(context, errString.toString(), Toast.LENGTH_SHORT)
                        authState = AppLockResult.Failure
                        onAuthResult(AppLockResult.Failure)
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Napier.v("$TAG biometric authentication succeeded")
                        persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
                        authState = AppLockResult.Success
                        onAuthResult(AppLockResult.Success)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Napier.i("$TAG biometric authentication failed")
                        // Don't change state here - user can retry
                    }
                }

                biometricPrompt = BiometricPrompt(activity, executor, callback)

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(biometricTitle)
                    .setSubtitle(biometricDesc)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .setConfirmationRequired(false)
                    .build()

                Napier.v("$TAG showing biometric prompt")
                biometricPrompt?.authenticate(promptInfo)
            }
        }

        onDispose {
            biometricPrompt?.cancelAuthentication()
        }
    }

    // UI: Simple screen with app logo
    AppLockContent(
        appName = appName,
        title = biometricTitle,
        subtitle = biometricDesc
    )
}

/**
 * The visual content of the app lock screen - displays the app logo centered on a background.
 */
@Composable
private fun AppLockContent(
    appName: String,
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(
                        horizontal = Dimensions.screenPaddingHorizontal,
                        vertical = Dimensions.spacingMd
                    )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Dimensions.screenPaddingHorizontal)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimensions.screenPaddingHorizontal),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimensions.cornerRadius5xl),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = Dimensions.spacing2xl, vertical = Dimensions.spacing2xl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher),
                            contentDescription = appName,
                            modifier = Modifier.size(120.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
    }
}

/**
 * Checks if biometric authentication is required.
 *
 * Authentication is NOT required if:
 * - Biometric is disabled in settings
 * - App is running on TV
 * - The configured timeout has not expired since last authentication
 *
 * @return true if biometric prompt should be shown, false otherwise
 */
private fun checkBiometricRequired(
    context: Context,
    persistentState: PersistentState
): Boolean {
    // Check if biometric is enabled
    val bioMetricType = BioMetricType.fromValue(persistentState.biometricAuthType)
    if (!bioMetricType.enabled()) {
        Napier.v("$TAG biometric authentication disabled")
        return false
    }

    // Check if running on TV
    if (isAppRunningOnTv(context)) {
        Napier.v("$TAG running on TV, biometric not supported")
        return false
    }

    // Check timeout
    val lastAuthTime = persistentState.biometricAuthTime
    var delay = bioMetricType.mins

    // Default to 15 minutes if delay is invalid
    delay = if (delay == -1L) {
        BioMetricType.FIFTEEN_MIN.mins
    } else {
        delay
    }

    Napier.d("$TAG timeout: $delay, last auth: $lastAuthTime")
    val timeSinceLastAuth = abs(SystemClock.elapsedRealtime() - lastAuthTime)
    if (timeSinceLastAuth < TimeUnit.MINUTES.toMillis(delay)) {
        Napier.i("$TAG biometric auth skipped, time since last auth: $timeSinceLastAuth")
        return false
    }

    return true
}

/**
 * Checks if the app is running on a TV device.
 */
private fun isAppRunningOnTv(context: Context): Boolean {
    return try {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    } catch (_: Exception) {
        false
    }
}
