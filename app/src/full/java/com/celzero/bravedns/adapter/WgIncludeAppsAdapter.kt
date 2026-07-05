/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter


import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun IncludeAppRow(
    mapping: ProxyApplicationMapping,
    proxyId: String,
    position: CardPosition = CardPosition.Single,
    onInterfaceUpdate: (ProxyApplicationMapping, Boolean) -> Unit
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var isProxyExcluded by remember(mapping.uid, mapping.packageName) { mutableStateOf(false) }
    var hasInternetPerm by remember(mapping.uid, mapping.packageName) { mutableStateOf(true) }
    var iconDrawable by remember(mapping.uid, mapping.packageName) { mutableStateOf<Drawable?>(null) }
    var isIncluded by
        remember(mapping.uid, mapping.packageName, mapping.proxyId) {
            mutableStateOf(false)
        }

    LaunchedEffect(mapping.uid, mapping.proxyId, mapping.packageName) {
        isProxyExcluded = withContext(Dispatchers.IO) {
            FirewallManager.isAppExcludedFromProxy(mapping.uid)
        }
        hasInternetPerm = mapping.hasInternetPermission(packageManager)
        iconDrawable = withContext(Dispatchers.IO) {
            getIcon(context, mapping.packageName, mapping.appName)
        }

        isIncluded = mapping.proxyId == proxyId && mapping.proxyId.isNotEmpty() && !isProxyExcluded
    }

    val isClickable = !isProxyExcluded
    val containerColor =
        when {
            isProxyExcluded -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            isIncluded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "includeRowScale"
    )
    val contentAlpha = if (hasInternetPerm && !isProxyExcluded) 1f else 0.5f
    val titleColor =
        when {
            isIncluded -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    val shape = shapeFor(position)

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(shape)
                .padding(
                    top = if (position == CardPosition.Middle || position == CardPosition.Last) 2.dp else 0.dp
                ),
        shape = shape,
        color = containerColor
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isIncluded,
                        enabled = isClickable,
                        role = Role.Checkbox,
                        interactionSource = interactionSource,
                        indication = null
                    ) { checked ->
                        if (checked == isIncluded || !isClickable) return@toggleable
                        isIncluded = checked
                        onInterfaceUpdate(mapping, checked)
                    }
                    .padding(horizontal = Dimensions.spacingMd, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val iconPainter =
                rememberDrawablePainter(iconDrawable)
                    ?: rememberDrawablePainter(getDefaultIcon(context))
            iconPainter?.let { painter ->
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                )
            }
            Text(
                text = mapping.appName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = titleColor.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = isIncluded,
                enabled = isClickable,
                onCheckedChange = null
            )
        }
    }
}

private fun shapeFor(position: CardPosition): RoundedCornerShape {
    return when (position) {
        CardPosition.Single -> RoundedCornerShape(18.dp)
        CardPosition.First -> RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = 10.dp,
            bottomEnd = 10.dp
        )
        CardPosition.Middle -> RoundedCornerShape(10.dp)
        CardPosition.Last -> RoundedCornerShape(
            topStart = 10.dp,
            topEnd = 10.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp
        )
    }
}
