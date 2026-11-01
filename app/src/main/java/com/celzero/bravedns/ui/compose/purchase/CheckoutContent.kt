/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase

import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.controls.OriginalButton
import com.celzero.bravedns.ui.compose.controls.OriginalLabel
import androidx.compose.ui.Alignment
import com.celzero.bravedns.ui.compose.themeColor
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.rpn.RpnControlState
import com.celzero.bravedns.ui.compose.wireguard.wgAnnotatedText
internal class CheckoutScreenState {
    val paymentAwaitingContainer = RpnControlState()
    val paymentContainer = RpnControlState()
    val paymentSuccessContainer = RpnControlState()
    val paymentFailedContainer = RpnControlState()
    val plan1MonthButton = RpnControlState()
    val plan3MonthsButton = RpnControlState()
    val plan6MonthsButton = RpnControlState()
    val paymentSuccessButton = RpnControlState()
    val restoreButton = RpnControlState()
    val paymentFailedButton = RpnControlState()
    val paymentButton = RpnControlState()
}
private object CheckoutCopy {
    val app_name = """TCP Proxy"""
    val proxy_desc = """Fast proxy servers combined with affordability. Ideal solution for individuals. A reliable and secure way to access the internet anonymously."""
    val termsCopy = """Rethink DNS + Firewall are anti-surveillance tools first and foremost: It is absolutely against our ethos, then, to track users of our apps and services, or sell any information whatsoever to any third-party in any form, pseudo-anonymized, de-anonymized, anonymized, aggregated, or otherwise. We do not collect, sell, license, share any data (personally identifiable or otherwise) from the Rethink DNS + Firewall Android app. The code is open source so you may inspect it yourself or deploy to your servers. 
Personally identifiable information: Any data that can be traced back to the user including (but not limited to) a combination of IP addresses, name, device-id, advertisement-id, and location. 

 Non personally identifiable information: Aggregrated (summarized) data and anonymized data scrubbed off any personally identifiable information. 
Collect: Data stored by Rethink. 
Sell: Data sold to third parties. 
License: Restricted access to data collected by Rethink to third parties. 
Share: Data shared with third parties. 
Aggregate: Data combined with other data to form a summary of information. 
Anonymize: Data scrubbed off any personally identifiable information. 
De-anonymize: Data scrubbed off any personally identifiable information. 
Share: Permanent access to data collected by Rethink to third parties. 

We do not collect, sell, license, share any data (personally identifiable or otherwise) from the Rethink DNS + Firewall Android app. The code is open source so you may inspect it yourself or deploy to your servers."""
    val payment_title = """Choose a plan"""
    val plan1MonthButton = """1 Month Plan"""
    val plan3MonthsButton = """3 Month Plan"""
    val plan6MonthsButton = """6 Months Plan"""
    val payment_button = """purchase"""
    val restore_button = """Restore Purchase"""
    val terms_and_conditions = """Terms and Conditions"""
    val payment_success_title = """Payment Successful"""
    val payment_success_message = """Your payment was successful. You can now enjoy the proxy features of Rethink."""
    val payment_success_button = """continue"""
    val payment_awaiting_title = """Awaiting payment"""
    val payment_awaiting_message = """Please wait while we process your payment. This may take a few seconds."""
    val payment_failed_title = """Payment Failed"""
    val payment_failed_message = """Your payment failed. Please try again."""
    val payment_failed_button = """try again"""
}

@Composable internal fun CheckoutContent(state: CheckoutScreenState) {
    var selected by rememberSaveable { mutableIntStateOf(2) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(64.dp))
            Text(CheckoutCopy.app_name, style = MaterialTheme.typography.headlineMedium)
            Text(CheckoutCopy.proxy_desc)
            if (state.paymentContainer.isVisible) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HorizontalDivider(thickness = .5.dp, color = themeColor(R.attr.primaryLightColorText).copy(alpha = .5f))
                    OriginalLabel("activity_checkout_proxy:(anonymous)", Modifier.testTag("checkout_plans_header")) { Text("plans") }
                }
                Text(CheckoutCopy.payment_title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.selectableGroup()) {
                    listOf(state.plan1MonthButton, state.plan3MonthsButton, state.plan6MonthsButton).forEachIndexed { index, plan ->
                        Row(Modifier.fillMaxWidth().selectable(selected == index, onClick = { selected = index }, role = Role.RadioButton).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            OriginalRadioButton("activity_checkout_proxy:" + listOf("plan1MonthButton", "plan3MonthsButton", "plan6MonthsButton")[index], selected == index, onClick = null, indicatorOnly = true)
                            Text(wgAnnotatedText(plan.text), Modifier.padding(start = 12.dp))
                        }
                    }
                }
                OriginalButton("activity_checkout_proxy:payment_button", { state.paymentButton.performClick() }, Modifier.fillMaxWidth().testTag("checkout_payment")) { Text(CheckoutCopy.payment_button) }
                OriginalButton("activity_checkout_proxy:restore_button", { state.restoreButton.performClick() }, Modifier.fillMaxWidth().testTag("checkout_restore")) { Text(CheckoutCopy.restore_button) }
                Text(CheckoutCopy.terms_and_conditions, style = MaterialTheme.typography.titleMedium)
                Text(CheckoutCopy.termsCopy)
            }
            if (state.paymentAwaitingContainer.isVisible) {
                CircularProgressIndicator()
                Text(CheckoutCopy.payment_awaiting_title, style = MaterialTheme.typography.titleLarge)
                Text(CheckoutCopy.payment_awaiting_message)
            }
            if (state.paymentSuccessContainer.isVisible) {
                Text(CheckoutCopy.payment_success_title, style = MaterialTheme.typography.titleLarge)
                Text(CheckoutCopy.payment_success_message)
                OriginalButton("activity_checkout_proxy:payment_success_button", { state.paymentSuccessButton.performClick() }, Modifier.testTag("checkout_continue")) { Text(CheckoutCopy.payment_success_button) }
            }
            if (state.paymentFailedContainer.isVisible) {
                Text(CheckoutCopy.payment_failed_title, style = MaterialTheme.typography.titleLarge)
                Text(CheckoutCopy.payment_failed_message)
                OriginalButton("activity_checkout_proxy:payment_failed_button", { state.paymentFailedButton.performClick() }, Modifier.testTag("checkout_retry")) { Text(CheckoutCopy.payment_failed_button) }
            }
        }
    }
}
