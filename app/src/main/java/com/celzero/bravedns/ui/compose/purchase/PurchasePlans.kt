/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import kotlinx.coroutines.launch

internal data class PurchasePlan(val id: String, val title: String, val price: String, val billing: String, val savings: String)
@Composable internal fun PurchasePlanCard(plan: PurchasePlan, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "selectedBorder").animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "borderRotation")
    val accent = themeColor(R.attr.accentGood)
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }.clip(RoundedCornerShape(18.dp)).drawBehind {
        if (selected) rotate(angle) { drawRect(Brush.sweepGradient(listOf(Color.Transparent, accent, Color.Transparent)), topLeft = androidx.compose.ui.geometry.Offset(-size.width, -size.height), size = size * 3f) }
    }.padding(1.5.dp)) {
        Card(Modifier.fillMaxWidth().selectable(selected, onClick = {
            onSelect()
            scope.launch { scale.snapTo(1f); scale.animateTo(.96f, tween(60)); scale.animateTo(1f, tween(60)) }
        }, role = Role.RadioButton).testTag("purchase_plan_${plan.id}"), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = if (selected) themeColor(com.google.android.material.R.attr.colorSurface) else themeColor(R.attr.background)),
            elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 1.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plan.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (plan.savings.isNotEmpty()) Text(plan.savings, style = MaterialTheme.typography.labelSmall)
                Text(plan.price, style = MaterialTheme.typography.headlineSmall)
                Text(plan.billing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable internal fun PurchaseShimmer(modifier: Modifier = Modifier, running: Boolean = true, duration: Int = 2000) {
    val phase = if (running) {
        val value by rememberInfiniteTransition(label = "purchaseShimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(duration, easing = LinearEasing)), label = "shimmerSweep")
        value
    } else 0f
    Column(modifier.fillMaxWidth().padding(16.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val start = (phase * 3f - 1f) * size.width
        drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = .85f), Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .85f)), start = androidx.compose.ui.geometry.Offset(start, 0f), end = androidx.compose.ui.geometry.Offset(start + size.width, 0f)), blendMode = BlendMode.DstIn)
    }) {
        // shimmer_subscription_loading: hero, title, four features, plans title, two plan blocks.
        SubscriptionPlaceholder(Modifier.fillMaxWidth().height(200.dp))
        Spacer(Modifier.height(24.dp))
        SubscriptionPlaceholder(Modifier.width(150.dp).height(24.dp))
        Spacer(Modifier.height(16.dp))
        repeat(4) { index ->
            SubscriptionPlaceholder(Modifier.fillMaxWidth().height(80.dp))
            Spacer(Modifier.height(if (index == 3) 24.dp else 8.dp))
        }
        SubscriptionPlaceholder(Modifier.width(120.dp).height(24.dp))
        Spacer(Modifier.height(16.dp))
        repeat(2) {
            SubscriptionPlaceholder(Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun SubscriptionPlaceholder(modifier: Modifier) {
    Box(modifier.background(themeColor(R.attr.primaryTextColor), RoundedCornerShape(12.dp)))
}
