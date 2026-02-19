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
 package com.celzero.bravedns.ui.fragment

import Logger.LOG_TAG_UI
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.android.billingclient.api.BillingClient
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.databinding.FragmentManageSubscriptionBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManageSubscriptionFragment : Fragment(R.layout.fragment_manage_subscription) {
    private val b by viewBinding(FragmentManageSubscriptionBinding::bind)

    // You can fetch these details dynamically, e.g. from your backend or Play Billing Library
    private val appIconRes = R.drawable.ic_launcher_foreground
    private val benefits = listOf(
        "Unlimited skips",
        "Offline download",
        "No ads"
    )

    companion object {
        private const val TAG = "ManSubFragment"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun initView() {
        val subscriptionData = RpnProxyManager.getSubscriptionData()
        val nextRenewalTs = subscriptionData?.subscriptionStatus?.billingExpiry
        val nextRenewal = if (nextRenewalTs != null) {
            // format the timestamp to a readable date string
            val date = Utilities.convertLongToTime(nextRenewalTs, Constants.TIME_FORMAT_4)
            "Next renewal date: $date"
        } else {
            "No renewal date available"
        }
        // Setup UI with data
        b.ivIcon.setImageResource(appIconRes)
        b.tvAppName.text = getString(R.string.app_name)
        b.tvStatus.text = RpnProxyManager.getSubscriptionState().name
        b.tvPlan.text = subscriptionData?.purchaseDetail?.planTitle
        b.tvNextRenewal.text = nextRenewal

        // underline the google play text
        b.tvManageSubscriptionOnGooglePlay.underline()

        // Benefits - simple way, set text for demo:
        b.tvFeatures.text = benefits.joinToString(separator = "\n") { "• $it" }
        showCancelOrRevokeButton()
    }

    private fun setupClickListeners() {
        b.tvManageSubscriptionOnGooglePlay.setOnClickListener {
            managePlayStoreSubs()
        }

        b.btnRevoke.setOnClickListener {
            showDialogConfirmCancelOrRevoke(isCancel = false)
        }

        b.btnCancel.setOnClickListener {
            showDialogConfirmCancelOrRevoke(isCancel = true)
        }

        b.btnRenew.setOnClickListener {
            // Prepare arguments if needed
            val args = Bundle().apply { putString("ARG_KEY", "Launch_Rethink_Plus") }

            // Create intent using the helper
            val intent = FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusFragment::class.java,
                args = args // or null if none
            )

            // Start the activity
            startActivity(intent)
        }
    }

    private fun showCancelOrRevokeButton() {
        if (!RpnProxyManager.getSubscriptionState().state().isActive) {
            b.btnCancel.visibility = View.GONE
            b.btnRevoke.visibility = View.GONE
            b.btnRenew.visibility = View.VISIBLE
            b.tvCancelNote.visibility = View.GONE
            return
        }

        val purchaseTs = RpnProxyManager.getSubscriptionData()?.subscriptionStatus?.purchaseTime
        val canRevoke = canRevoke(purchaseTs)
        // if the purchase time is within 48 hours, show revoke button
        if (purchaseTs != null && canRevoke) {
            b.btnCancel.visibility = View.GONE
            b.btnRenew.visibility = View.GONE
            b.btnRevoke.visibility = View.VISIBLE
            b.tvCancelNote.visibility = View.VISIBLE
        } else {
            b.btnCancel.visibility = View.VISIBLE
            b.btnRevoke.visibility = View.GONE
            b.btnRenew.visibility = View.GONE
            b.tvCancelNote.visibility = View.GONE
        }
    }

    private fun canRevoke(purchaseTs: Long?): Boolean {
        if (purchaseTs == null) {
            Logger.w(
                LOG_TAG_UI,
                "$TAG purchase time is null, cannot determine revocation eligibility"
            )
            return false
        }
        val currTs = System.currentTimeMillis()
        // in case of DEBUG check for 2 mins else check for 48 hours
        val canRevoke = if (DEBUG) {
            currTs - purchaseTs < 2 * 60 * 1000 // 2 minutes in milliseconds
        } else {
            // in case of RELEASE check for 48 hours
            // this is the time within which the user can revoke the subscription
            // after that, they can only cancel it
            currTs - purchaseTs < 48 * 60 * 60 * 1000 // 48 hours in milliseconds
        }
        Logger.i(LOG_TAG_UI, "$TAG canRevoke: $canRevoke, purchaseTs: $purchaseTs, currTs: $currTs")
        return canRevoke
    }

    private fun managePlayStoreSubs() {
        val productId = RpnProxyManager.getRpnProductId()
        if (productId.isEmpty()) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.error_loading_manage_subscription),
                Toast.LENGTH_SHORT
            )
            return
        }
        // link for the play store which has placeholders for subscription id and package name
        val link = InAppBillingHandler.LINK
        // replace $1 with subscription product id
        // replace $2 with package name
        val linkWithSubs = link.replace("$1", productId)
        val linkWithSubsAndPackage = linkWithSubs.replace("$2", requireContext().packageName)
        openUrl(requireContext(), linkWithSubsAndPackage)
        InAppBillingHandler.fetchPurchases(listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP))
    }

    private fun showDialogConfirmCancelOrRevoke(isCancel: Boolean) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        val title = if (isCancel) {
            "Confirm Cancel Subscription"
        } else {
            "Confirm Revoke Subscription"
        }
        val message = if (isCancel) {
            "Are you sure you want to cancel your subscription? You will lose access to premium features at the end of the current billing period."
        } else {
            "Are you sure you want to revoke your subscription? This action cannot be undone and you will lose access to premium features immediately."
        }

        val positiveBtnText = if (isCancel) {
            "Cancel"
        } else {
            "Revoke"
        }
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(positiveBtnText) { _, _ ->
            if (isCancel) {
                cancelSubscription()
            } else {
                revokeSubscription()
            }
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun cancelSubscription() {
        io {
            val state = RpnProxyManager.getSubscriptionState()
            if (!state.hasValidSubscription) {
                Logger.w(
                    LOG_TAG_UI,
                    "$TAG cancel subscription clicked but no valid subscription found"
                )
                showToastUiCentered(
                    requireContext(),
                    "No valid subscription found",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            /*val curr = subsDb.getCurrentSubscription()
            if (curr == null) {
                Logger.w(LOG_TAG_UI, "$TAG cancel subscription clicked but no active subscription found")
                showToastUiCentered(requireContext(), "No active subscription found", Toast.LENGTH_SHORT)
                return@io
            }
            if (curr.status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
                Logger.w(LOG_TAG_UI, "$TAG cancel subscription clicked but subscription is not active")
                showToastUiCentered(requireContext(), "Subscription is not active", Toast.LENGTH_SHORT)
                return@io
            }*/
            val curr = RpnProxyManager.getCurrentSubscription()
            if (curr == null) {
                Logger.w(
                    LOG_TAG_UI,
                    "$TAG cancel subscription clicked but no active subscription found"
                )
                showToastUiCentered(
                    requireContext(),
                    "No active subscription found",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            if (curr.purchaseDetail == null) {
                Logger.w(LOG_TAG_UI, "$TAG cancel subscription clicked but purchase detail is null")
                showToastUiCentered(
                    requireContext(),
                    "Purchase detail is not available",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            if (curr.purchaseDetail.state != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
                Logger.w(
                    LOG_TAG_UI,
                    "$TAG cancel subscription clicked but subscription is not active"
                )
                showToastUiCentered(
                    requireContext(),
                    "Subscription is not active",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            val accountId = curr.purchaseDetail.accountId
            val purchaseToken = curr.purchaseDetail.purchaseToken
            // returns a pair where first is success and second is message
            val res = InAppBillingHandler.cancelPlaySubscription(accountId, purchaseToken)
            uiCtx {
                if (res.first) {
                    showToastUiCentered(
                        requireContext(),
                        res.second,
                        Toast.LENGTH_SHORT
                    )
                } else {
                    showToastUiCentered(
                        requireContext(),
                        res.second,
                        Toast.LENGTH_SHORT
                    )
                }
                initView()
            }
            Logger.i(LOG_TAG_UI, "$TAG cancel subscription request sent, success? ${res.first}, msg: ${res.second}")
            InAppBillingHandler.fetchPurchases(listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP))
            checkForGracePeriod()
        }
    }

    private fun revokeSubscription() {
        io {
            /*val curr = subsDb.getCurrentSubscription()
            if (curr == null) {
                Logger.w(LOG_TAG_UI, "$TAG revoke subscription clicked but no active subscription found")
                showToastUiCentered(requireContext(), "No active subscription found", Toast.LENGTH_SHORT)
                return@io
            }
            if (curr.status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
                Logger.w(LOG_TAG_UI, "$TAG revoke subscription clicked but subscription is not active")
                showToastUiCentered(requireContext(), "Subscription is not active", Toast.LENGTH_SHORT)
                return@io
            }*/
            val state = RpnProxyManager.getSubscriptionState()
            if (!state.hasValidSubscription) {
                Logger.w(
                    LOG_TAG_UI,
                    "$TAG revoke subscription clicked but no valid subscription found"
                )
                showToastUiCentered(
                    requireContext(),
                    "No valid subscription found",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            /*val curr = subsDb.getCurrentSubscription()
            if (curr == null) {
                Logger.w(LOG_TAG_UI, "$TAG cancel subscription clicked but no active subscription found")
                showToastUiCentered(requireContext(), "No active subscription found", Toast.LENGTH_SHORT)
                return@io
            }
            if (curr.status != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
                Logger.w(LOG_TAG_UI, "$TAG cancel subscription clicked but subscription is not active")
                showToastUiCentered(requireContext(), "Subscription is not active", Toast.LENGTH_SHORT)
                return@io
            }*/
            val curr = RpnProxyManager.getCurrentSubscription()
            if (curr == null) {
                Logger.w(
                    LOG_TAG_UI,
                    "$TAG revoke subscription clicked but no active subscription found"
                )
                showToastUiCentered(
                    requireContext(),
                    "No active subscription found",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            if (curr.purchaseDetail == null) {
                Logger.w(LOG_TAG_UI, "$TAG revoke subscription clicked but purchase detail is null")
                showToastUiCentered(
                    requireContext(),
                    "Purchase detail is not available",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            if (curr.purchaseDetail.state != SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id) {
                Logger.w(
                    LOG_TAG_UI,
                    "$TAG revoke subscription clicked but subscription is not active"
                )
                showToastUiCentered(
                    requireContext(),
                    "Subscription is not active",
                    Toast.LENGTH_SHORT
                )
                return@io
            }
            val accountId = curr.purchaseDetail.accountId
            val purchaseToken = curr.purchaseDetail.purchaseToken
            // returns a Pair<Boolean, String> where first is success and second is message
            val res = InAppBillingHandler.revokeSubscription(accountId, purchaseToken)
            uiCtx {
                if (res.first) {
                    showToastUiCentered(
                        requireContext(),
                        res.second,
                        Toast.LENGTH_SHORT
                    )
                } else {
                    showToastUiCentered(
                        requireContext(),
                        res.second,
                        Toast.LENGTH_SHORT
                    )
                }
                initView()
            }
            Logger.i(LOG_TAG_UI, "$TAG revoke subscription request sent, success: ${res.first}, msg: ${res.second}")
            InAppBillingHandler.fetchPurchases(listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP))
        }
    }

    private fun checkForGracePeriod() {
        // check for grace period if the subscription is cancelled, expires or revoked
        // if so then do the necessary actions
    }


    fun AppCompatTextView.underline() {
        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
