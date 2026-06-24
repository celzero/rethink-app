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
package com.celzero.bravedns.ui.compose.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PauseTimer.PAUSE_VPN_EXTRA_MILLIS
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseScreen(onFinish: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var timerText by remember { mutableStateOf("00:00:00") }
    var timerDesc by remember { mutableStateOf("") }
    var autoOp by remember { mutableStateOf(AutoOp.NONE) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(lifecycleOwner) {
        val connectionObserver = Observer<BraveVPNService.State?> { state ->
            if (state != BraveVPNService.State.PAUSED) onFinish()
        }
        val timerObserver = Observer<Long> { millis ->
            val ss = (TimeUnit.MILLISECONDS.toSeconds(millis) % 60).toString().padStart(2, '0')
            val mm = (TimeUnit.MILLISECONDS.toMinutes(millis) % 60).toString().padStart(2, '0')
            val hh = TimeUnit.MILLISECONDS.toHours(millis).toString().padStart(2, '0')
            timerText = "$hh:$mm:$ss"
        }
        val appListObserver = Observer<Collection<AppInfo>> { list ->
            timerDesc = list.count { a -> a.connectionStatus != FirewallManager.ConnectionStatus.ALLOW.id }.toString()
        }

        VpnController.connectionStatus.observe(lifecycleOwner, connectionObserver)
        VpnController.getPauseCountDownObserver()?.observe(lifecycleOwner, timerObserver)
        FirewallManager.getApplistObserver().observe(lifecycleOwner, appListObserver)
        if (!VpnController.isAppPaused()) onFinish()

        onDispose {
            VpnController.connectionStatus.removeObserver(connectionObserver)
            VpnController.getPauseCountDownObserver()?.removeObserver(timerObserver)
            FirewallManager.getApplistObserver().removeObserver(appListObserver)
            longPressJob?.cancel()
        }
    }

    fun handleLongPress() {
        if (longPressJob?.isActive == true) return
        longPressJob = scope.launch(Dispatchers.Main) {
            while (autoOp != AutoOp.NONE) {
                when (autoOp) {
                    AutoOp.INCREASE -> {
                        delay(200); VpnController.increasePauseDuration(PAUSE_VPN_EXTRA_MILLIS)
                    }

                    AutoOp.DECREASE -> {
                        delay(200); VpnController.decreasePauseDuration(PAUSE_VPN_EXTRA_MILLIS)
                    }

                    else -> {}
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.pause_text),
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Dimensions.screenPaddingHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            Spacer(modifier = Modifier.height(Dimensions.spacingMd))

            // ── Timer card ────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimensions.cornerRadius5xl),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Label chip
                    Surface(
                        shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.pause_text).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(
                                1.2f,
                                androidx.compose.ui.unit.TextUnitType.Sp
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Monospace countdown timer — big, bold, beautiful
                    Text(
                        text = timerText,
                        style = MaterialTheme.typography.displayLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Blocked apps count
                    if (timerDesc.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.pause_desc, timerDesc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── Controls ──────────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PauseControlButton(
                            icon = R.drawable.ic_minus,
                            size = 52.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onClick = { VpnController.decreasePauseDuration(PAUSE_VPN_EXTRA_MILLIS) },
                            onLongClick = { autoOp = AutoOp.DECREASE; handleLongPress() },
                            onRelease = { autoOp = AutoOp.NONE }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        // Resume button — large, prominent, error tint
                        PauseControlButton(
                            icon = R.drawable.ic_stop,
                            size = 72.dp,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            iconTintIsOnError = true,
                            onClick = { VpnController.resumeApp(); onFinish() },
                            onLongClick = {},
                            onRelease = {}
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        PauseControlButton(
                            icon = R.drawable.ic_plus,
                            size = 52.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onClick = { VpnController.increasePauseDuration(PAUSE_VPN_EXTRA_MILLIS) },
                            onLongClick = { autoOp = AutoOp.INCREASE; handleLongPress() },
                            onRelease = { autoOp = AutoOp.NONE }
                        )
                    }
                }
            }

            // Resume bottom button — full width
            ElevatedButton(
                onClick = { VpnController.resumeApp(); onFinish() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.notif_dialog_pause_dialog_positive),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PauseControlButton(
    icon: Int,
    size: Dp,
    containerColor: androidx.compose.ui.graphics.Color,
    iconTintIsOnError: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRelease: () -> Unit
) {
    val iconTint = if (iconTintIsOnError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(size / 3),
        color = containerColor,
        tonalElevation = 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() },
                        onPress = { tryAwaitRelease(); onRelease() }
                    )
                }
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(size * 0.44f)
            )
        }
    }
}

private enum class AutoOp { INCREASE, DECREASE, NONE }
