/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.iab.AckFailureInfo
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver.PurchaseUiModel
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSizes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status-chip tint kinds shared by the dashboard / manage heroes. */
internal enum class RpnStatusKind { GOOD, BAD, DIM }

/**
 * One D-pad-focusable action row (the TV equivalent of the phone
 * screens' Material list rows). Disabled rows keep their place but dim
 * and refuse focus, mirroring the phone's alpha-0.38 gating.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RpnActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    support: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = if (destructive) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = if (destructive) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TvSpacing.ContentHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(TvSizes.LeadingIcon),
                )
                Spacer(Modifier.width(TvSpacing.IconTextGap))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (support != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = support,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(TvSpacing.IconTextGap))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Small status pill used in the dashboard / manage heroes. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RpnStatusChip(text: String, kind: RpnStatusKind) {
    val container = when (kind) {
        RpnStatusKind.GOOD -> TvColors.Success
        RpnStatusKind.BAD -> MaterialTheme.colorScheme.error
        RpnStatusKind.DIM -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (kind) {
        RpnStatusKind.GOOD -> TvColors.OnSuccess
        RpnStatusKind.BAD -> MaterialTheme.colorScheme.onError
        RpnStatusKind.DIM -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.tv.material3.Surface(
        shape = TvShapes.Pill,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Basic TV dialog: icon (optional) + title + message + up to two
 * actions. Built on plain Compose [Dialog] with tv-material surfaces
 * because tv-material 1.1.0 ships no dialog component.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: (() -> Unit)?,
    dismissLabel: String? = null,
    icon: ImageVector? = null,
    spinner: Boolean = false,
    destructive: Boolean = false,
) {
    Dialog(onDismissRequest = { if (!spinner) onDismiss() }) {
        androidx.tv.material3.Surface(
            shape = TvShapes.Medium,
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.CardGap),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (spinner) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    } else if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (destructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    if (spinner || icon != null) Spacer(Modifier.width(TvSpacing.CardGap))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onConfirm != null || dismissLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.CardGap)) {
                        if (onConfirm != null) {
                            TvDialogButton(
                                label = confirmLabel,
                                onClick = onConfirm,
                                destructive = destructive,
                            )
                        }
                        if (dismissLabel != null) {
                            TvDialogButton(
                                label = dismissLabel,
                                onClick = onDismiss,
                                subtle = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDialogButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    subtle: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.ExtraSmall),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                destructive -> MaterialTheme.colorScheme.error
                subtle -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primary
            },
            contentColor = when {
                destructive -> MaterialTheme.colorScheme.onError
                subtle -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onPrimary
            },
            focusedContainerColor = when {
                destructive -> MaterialTheme.colorScheme.error
                subtle -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            focusedContentColor = when {
                destructive -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            },
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = TvSpacing.ContentHorizontal, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Persistent acknowledgement-failure banner. Mirrors the phone
 * dashboard: any non-null [AckFailureInfo] surfaces a highly visible
 * card with a support escape hatch.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AckFailureBanner(info: AckFailureInfo, onSupport: () -> Unit) {
    Surface(
        onClick = onSupport,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            contentColor = MaterialTheme.colorScheme.onError,
            focusedContainerColor = MaterialTheme.colorScheme.error,
            focusedContentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TvSpacing.ContentHorizontal,
                vertical = 14.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = info.message,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(14.dp))
            Icon(
                imageVector = Icons.Filled.SupportAgent,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Success dialog shown after a purchase (or extend) completes — the TV
 * equivalent of `SubscriptionAnimDialog` + processing sheet success
 * state, minus the confetti.
 */
@Composable
internal fun RpnSuccessDialog(title: String, message: String) {
    TvMessageDialog(
        title = title,
        message = message,
        onDismiss = {},
        confirmLabel = "",
        onConfirm = null,
        icon = Icons.Filled.CheckCircle,
    )
}

/**
 * Same identity line the phone dashboard / manage / support screens show
 * as the hero's last row: purchase token (first 12) · accountId (first
 * 12) • deviceId (first 4) · expiry-hex.
 */
internal fun heroIdentityLine(token: String, accountId: String, deviceId: String, expiry: String): String {
    val t = token.take(12)
    val a = accountId.take(12)
    val d = deviceId.take(4)
    val idPart = listOf(a, d).filter { it.isNotBlank() }.joinToString(" · ")
    return listOf(t, idPart, expiry).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Plan-name resolution shared by the dashboard and manage heroes. */
internal fun resolveTvPlanName(
    ctx: Context,
    productId: String,
    planId: String,
    fallbackTitle: String = "",
): String {
    when (planId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> return ctx.getString(R.string.plan_2yr)
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> return ctx.getString(R.string.plan_5yr)
        InAppBillingHandler.SUBS_PRODUCT_YEARLY -> return ctx.getString(R.string.billing_yearly)
        InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> return ctx.getString(R.string.monthly_plan)
    }
    return when (productId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> ctx.getString(R.string.plan_2yr)
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> ctx.getString(R.string.plan_5yr)
        InAppBillingHandler.SUBS_PRODUCT_YEARLY -> ctx.getString(R.string.billing_yearly)
        InAppBillingHandler.SUBS_PRODUCT_MONTHLY -> ctx.getString(R.string.monthly_plan)
        else -> fallbackTitle.ifEmpty { productId }
    }
}

/** Whether the product ids identify a one-time (INAPP) purchase. */
internal fun isOneTimeProduct(productId: String, planId: String): Boolean {
    val inAppIds = setOf(
        InAppBillingHandler.ONE_TIME_PRODUCT_ID,
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS,
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS,
        InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID,
    )
    return productId in inAppIds || planId in inAppIds
}

/**
 * Whether the purchase is still inside its revoke/refund window; mirrors
 * `RethinkPlusManagePurchaseFragment.canRevoke`.
 */
internal fun canRevokePurchase(
    subscriptionData: SubscriptionStateMachineV2.SubscriptionData?,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    val purchaseTs = subscriptionData?.subscriptionStatus?.purchaseTime ?: return false
    if (purchaseTs <= 0) return false
    if (subscriptionData.subscriptionStatus.status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
        return false
    }
    val detail = subscriptionData.purchaseDetail
    val status = subscriptionData.subscriptionStatus
    // purchaseDetail first, persisted row as fallback when blank/absent
    val planId = detail?.planId?.takeIf { it.isNotBlank() } ?: status.planId
    val productId = detail?.productId?.takeIf { it.isNotBlank() } ?: status.productId
    val dayMs = 24L * 60 * 60 * 1000
    val revokeWindowMs = when {
        productId == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS ||
            planId == InAppBillingHandler.ONE_TIME_PRODUCT_2YRS ->
            InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS * dayMs
        productId == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS ||
            planId == InAppBillingHandler.ONE_TIME_PRODUCT_5YRS ->
            InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS * dayMs
        productId == InAppBillingHandler.SUBS_PRODUCT_YEARLY ||
            planId == InAppBillingHandler.SUBS_PRODUCT_YEARLY ->
            InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS * dayMs
        productId == InAppBillingHandler.SUBS_PRODUCT_MONTHLY ||
            planId == InAppBillingHandler.SUBS_PRODUCT_MONTHLY ->
            InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS * dayMs
        isOneTimeProduct(productId, planId) -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS * dayMs
        else -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS * dayMs
    }
    return (nowMs - purchaseTs) < revokeWindowMs
}

/**
 * Resolves the status chip shown by the dashboard / manage heroes:
 * text + tint kind, honoring the effectively-cancelled guard (a
 * CANCELLED DB row that Play no longer reports as auto-renewing must
 * not render as Active).
 */
internal fun resolveStatusChip(
    ctx: Context,
    state: SubscriptionStateMachineV2.SubscriptionState,
    model: PurchaseUiModel,
    effectivelyCancelled: Boolean,
): Pair<String, RpnStatusKind> {
    return when (model) {
        is PurchaseUiModel.Loading -> ctx.getString(R.string.rpn_status_syncing) to RpnStatusKind.DIM
        is PurchaseUiModel.NoPurchase -> ctx.getString(R.string.rpn_status_no_plan) to RpnStatusKind.DIM
        else -> when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Active ->
                if (effectivelyCancelled) ctx.getString(R.string.lbl_cancelled) to RpnStatusKind.BAD
                else ctx.getString(R.string.lbl_active) to RpnStatusKind.GOOD
            is SubscriptionStateMachineV2.SubscriptionState.Grace ->
                ctx.getString(R.string.lbl_grace_period) to RpnStatusKind.GOOD
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled ->
                ctx.getString(R.string.lbl_cancelled) to RpnStatusKind.BAD
            is SubscriptionStateMachineV2.SubscriptionState.Expired ->
                ctx.getString(R.string.lbl_expired) to RpnStatusKind.BAD
            is SubscriptionStateMachineV2.SubscriptionState.Revoked ->
                ctx.getString(R.string.status_revoked) to RpnStatusKind.BAD
            is SubscriptionStateMachineV2.SubscriptionState.Paused ->
                ctx.getString(R.string.lbl_paused) to RpnStatusKind.DIM
            is SubscriptionStateMachineV2.SubscriptionState.OnHold ->
                ctx.getString(R.string.lbl_paused) to RpnStatusKind.DIM
            else -> ctx.getString(R.string.placeholder_dash) to RpnStatusKind.DIM
        }
    }
}

/** yyyy-MM-dd shared hero date format (phone screens use MMM d, yyyy). */
internal fun heroDateFormat(): SimpleDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

internal fun formatHeroDate(fmt: SimpleDateFormat, epochMs: Long): String? =
    if (epochMs > 0) fmt.format(Date(epochMs)) else null
