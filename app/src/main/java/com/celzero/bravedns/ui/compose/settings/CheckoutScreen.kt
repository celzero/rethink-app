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
package com.celzero.bravedns.ui.compose.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.TcpProxyHelper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.SectionHeader

enum class CheckoutPlan(val titleRes: Int, val subtitleRes: Int) {
    ONE_MONTH(R.string.checkout_plan_1m_title, R.string.checkout_plan_1m_subtitle),
    THREE_MONTH(R.string.checkout_plan_3m_title, R.string.checkout_plan_3m_subtitle),
    SIX_MONTH(R.string.checkout_plan_6m_title, R.string.checkout_plan_6m_subtitle)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    paymentStatus: TcpProxyHelper.PaymentStatus,
    onStartPayment: () -> Unit,
    onNavigateToProxy: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    var selectedPlan by remember { mutableStateOf(CheckoutPlan.SIX_MONTH) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.checkout_app_name),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (paymentStatus) {
                TcpProxyHelper.PaymentStatus.NOT_PAID -> PaymentContent(
                    selectedPlan = selectedPlan,
                    onPlanSelected = { selectedPlan = it },
                    onStartPayment = onStartPayment,
                    onNavigateToProxy = onNavigateToProxy
                )

                TcpProxyHelper.PaymentStatus.INITIATED -> PaymentAwaiting()
                TcpProxyHelper.PaymentStatus.PAID -> PaymentSuccess(onNavigateToProxy)
                TcpProxyHelper.PaymentStatus.FAILED -> PaymentFailed(onNavigateToProxy)
                else -> PaymentContent(
                    selectedPlan = selectedPlan,
                    onPlanSelected = { selectedPlan = it },
                    onStartPayment = onStartPayment,
                    onNavigateToProxy = onNavigateToProxy
                )
            }
        }
    }
}

@Composable
private fun PaymentContent(
    selectedPlan: CheckoutPlan,
    onPlanSelected: (CheckoutPlan) -> Unit,
    onStartPayment: () -> Unit,
    onNavigateToProxy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Dimensions.spacingLg),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.screenPaddingHorizontal),
            shape = RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            ),
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(Dimensions.spacingLg),
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
            ) {
                Text(
                    text = stringResource(R.string.checkout_app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.checkout_choose_plan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.screenPaddingHorizontal),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            SectionHeader(
                title = stringResource(R.string.checkout_choose_plan),
                modifier = Modifier.padding(horizontal = Dimensions.spacingXs)
            )

            RethinkListGroup {
                val plans = CheckoutPlan.entries
                plans.forEachIndexed { index, plan ->
                    val isSelected = selectedPlan == plan
                    val position = when {
                        plans.size == 1 -> CardPosition.Single
                        index == 0 -> CardPosition.First
                        index == plans.size - 1 -> CardPosition.Last
                        else -> CardPosition.Middle
                    }
                    RethinkListItem(
                        headline = stringResource(plan.titleRes),
                        supporting = stringResource(plan.subtitleRes),
                        position = position,
                        leadingIcon = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                        leadingIconTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { onPlanSelected(plan) },
                        trailing = {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                        }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    onClick = onStartPayment,
                    shape = RoundedCornerShape(Dimensions.buttonCornerRadius)
                ) {
                    Text(
                        text = stringResource(R.string.checkout_purchase),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                androidx.compose.material3.OutlinedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    onClick = onNavigateToProxy,
                    shape = RoundedCornerShape(Dimensions.buttonCornerRadius),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                ) {
                    Text(text = stringResource(R.string.checkout_restore))
                }
            }

            Spacer(modifier = Modifier.height(Dimensions.spacingMd))

            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
                ) {
                    Text(
                        text = stringResource(R.string.checkout_terms_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.checkout_terms_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimensions.spacingLg))
        }
    }
}

@Composable
private fun PaymentSuccess(onNavigateToProxy: () -> Unit) {
    StatusScreen(
        title = stringResource(R.string.checkout_payment_success_title),
        message = stringResource(R.string.checkout_payment_success_message),
        buttonLabel = stringResource(R.string.checkout_payment_success_button),
        onButtonClick = onNavigateToProxy
    )
}

@Composable
private fun PaymentFailed(onNavigateToProxy: () -> Unit) {
    StatusScreen(
        title = stringResource(R.string.checkout_payment_failed_title),
        message = stringResource(R.string.checkout_payment_failed_message),
        buttonLabel = stringResource(R.string.checkout_payment_failed_button),
        onButtonClick = onNavigateToProxy
    )
}

@Composable
private fun PaymentAwaiting() {
    CheckoutStatusCard(
        title = stringResource(R.string.checkout_payment_awaiting_title),
        message = stringResource(R.string.checkout_payment_awaiting_message)
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatusScreen(
    title: String,
    message: String,
    buttonLabel: String,
    onButtonClick: () -> Unit
) {
    CheckoutStatusCard(
        title = title,
        message = message
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onButtonClick,
            shape = RoundedCornerShape(Dimensions.buttonCornerRadius)
        ) {
            Text(text = buttonLabel)
        }
    }
}

@Composable
private fun CheckoutStatusCard(
    title: String,
    message: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal, vertical = Dimensions.spacingXl)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.26f)
            ),
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(Dimensions.spacingXs))
                content()
            }
        }
    }
}
