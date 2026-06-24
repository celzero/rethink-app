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
package com.celzero.bravedns.ui.compose.bubble

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import io.github.aakira.napier.Napier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun BubbleScreen(
    vpnOn: Boolean,
    allowedItems: LazyPagingItems<AllowedAppInfo>,
    blockedItems: LazyPagingItems<BlockedAppInfo>,
    onAllowApp: (BlockedAppInfo, () -> Unit) -> Unit,
    onRemoveAllowed: (AllowedAppInfo, () -> Unit) -> Unit
) {
    val allowedLoaded = allowedItems.loadState.refresh is LoadState.NotLoading
    val allowedCount = allowedItems.itemCount
    val showAllowedSection = vpnOn && allowedLoaded && allowedCount > 0

    val blockedLoading = blockedItems.loadState.refresh is LoadState.Loading
    val blockedError = blockedItems.loadState.refresh is LoadState.Error
    val blockedLoaded = blockedItems.loadState.refresh is LoadState.NotLoading
    val blockedEmpty = blockedLoaded && blockedItems.itemCount == 0

    val showEmptyState = !vpnOn || blockedError || blockedEmpty

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            top = Dimensions.spacingMd,
            bottom = Dimensions.spacingXl
        ),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
    ) {
        item {
            HeaderSection()
        }

        if (showAllowedSection) {
            item {
                AllowedHeader(count = allowedCount)
            }
            itemsIndexed(
                items = List(allowedItems.itemCount) { it },
                key = { index, _ -> allowedItems[index]?.uid ?: index }
            ) { index, _ ->
                val app = allowedItems[index] ?: return@itemsIndexed
                AllowedAppRow(
                    app = app,
                    onRemove = {
                        onRemoveAllowed(app) {
                            allowedItems.refresh()
                            blockedItems.refresh()
                        }
                    }
                )
            }
        }

        item {
            BlockedHeader()
        }

        when {
            blockedLoading -> {
                item { LoadingCard() }
            }
            showEmptyState -> {
                item { EmptyState() }
            }
            else -> {
                itemsIndexed(
                    items = List(blockedItems.itemCount) { it },
                    key = { index, _ -> blockedItems[index]?.uid ?: index }
                ) { index, _ ->
                    val app = blockedItems[index] ?: return@itemsIndexed
                    BlockedAppRow(
                        app = app,
                        onAllow = {
                            onAllowApp(app) {
                                blockedItems.refresh()
                                allowedItems.refresh()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal),
        shape = RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimensions.spacingLg),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_firewall_bubble),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.firewall_bubble_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.firewall_bubble_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AllowedHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.bubble_allowed_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun BlockedHeader() {
    SectionHeader(
        title = stringResource(R.string.bubble_activity_title),
        modifier = Modifier.padding(horizontal = Dimensions.screenPaddingHorizontal)
    )
}

@Composable
private fun LoadingCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal),
        shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.bubble_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal),
        shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_firewall_shield),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.bubble_empty_state_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bubble_empty_state_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Visible,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun AllowedAppRow(app: AllowedAppInfo, onRemove: () -> Unit) {
    BubbleAppRow(
        packageName = app.packageName,
        appName = app.appName,
        details = {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = allowedTimeRemaining(app),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailing = {
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(R.string.lbl_remove),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun BubbleAppRow(
    packageName: String,
    appName: String,
    details: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    BubbleListCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = packageName)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                details()
            }
            trailing()
        }
    }
}

@Composable
fun BlockedAppRow(app: BlockedAppInfo, onAllow: () -> Unit) {
    val context = LocalContext.current
    val bubbleTimeJustNow = stringResource(R.string.bubble_time_just_now)
    val bubbleTimeMinutesAgoTemplate = stringResource(R.string.bubble_time_minutes_ago)
    val bubbleTimeHoursAgoTemplate = stringResource(R.string.bubble_time_hours_ago)

    BubbleAppRow(
        packageName = app.packageName,
        appName = app.appName,
        details = {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bubble_blocked_count, app.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeAgo(
                        timestamp = app.lastBlocked,
                        justNowText = bubbleTimeJustNow,
                        minutesAgoText = {
                            String.format(Locale.getDefault(), bubbleTimeMinutesAgoTemplate, it)
                        },
                        hoursAgoText = {
                            String.format(Locale.getDefault(), bubbleTimeHoursAgoTemplate, it)
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailing = {
            Button(
                onClick = onAllow,
                shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(text = stringResource(R.string.bubble_allow_btn))
            }
        }
    )
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon = remember(packageName) { loadAppIcon(context, packageName) }
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg)
            ),
        contentAlignment = Alignment.Center
    ) {
        val painter = rememberDrawablePainter(icon)
        painter?.let {
            Image(
                painter = it,
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun BubbleListCard(content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal),
        shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        tonalElevation = 1.dp
    ) {
        Row(content = content)
    }
}

private fun loadAppIcon(context: android.content.Context, packageName: String): Drawable {
    return try {
        if (packageName != "Unknown") {
            context.packageManager.getApplicationIcon(packageName)
        } else {
            ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
        }
    } catch (_: Exception) {
        Napier.e("App icon not found for $packageName")
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
    }
}

private fun allowedTimeRemaining(app: AllowedAppInfo): String {
    val now = System.currentTimeMillis()
    val expiresAt = app.allowedAt + (15 * 60 * 1000)
    val remaining = (expiresAt - now) / 1000 / 60
    return if (remaining > 0) {
        "$remaining min${if (remaining != 1L) "s" else ""} remaining"
    } else {
        "Expired"
    }
}

private fun timeAgo(
    timestamp: Long,
    justNowText: String,
    minutesAgoText: (Long) -> String,
    hoursAgoText: (Long) -> String
): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> justNowText
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
            minutesAgoText(minutes)
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            hoursAgoText(hours)
        }
        else -> {
            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            dateFormat.format(Date(timestamp))
        }
    }
}
