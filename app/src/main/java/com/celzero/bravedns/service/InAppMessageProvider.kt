/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import android.app.Activity

/**
 * Flavor-scoped hook for showing Play Billing in-app messages.
 *
 * Google's Play Billing documentation recommends calling
 * BillingClient.showInAppMessages() in every Activity's onResume() so that
 * billing-related messages (payment-declined recovery, grace-period notices,
 * subscription-canceled alerts, etc.) are surfaced to the user at the right
 * moment without requiring them to navigate to the subscription screen.
 *
 * The `play` flavor provides PlayInAppMessageProvider which delegates to
 * InAppBillingHandler.enableInAppMessaging().
 * All other flavors use NoOpInAppMessageProvider.
 */
interface InAppMessageProvider {
    /**
     * Show any pending in-app billing messages for the given [activity].
     * Safe to call even when billing is not set up, implementations must
     * handle that case gracefully.
     */
    fun showMessages(activity: Activity)
}

/** Default (non-Play) implementation, does nothing. */
class NoOpInAppMessageProvider : InAppMessageProvider {
    override fun showMessages(activity: Activity) { /* no-op */ }
}


