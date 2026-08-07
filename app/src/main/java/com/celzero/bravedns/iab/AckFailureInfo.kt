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
package com.celzero.bravedns.iab

/**
 * Sticky, process-wide record of the last unresolved acknowledgement / verification failure
 * (ServerAckPending, Error after a real purchase flow, PendingTimeout, AcknowledgementFailed).
 * Published by [InAppBillingHandler.setAckFailureState] and observed by the dashboard
 * (RethinkPlusDashboardFragment) so the acknowledgement-failure banner
 * survives the destruction of RethinkPlusFragment and is visible regardless of ViewModel instance.
 */
data class AckFailureInfo(
    val title: String,
    val message: String,
    val canRetry: Boolean,
    val refundInitiated: Boolean = false
)
