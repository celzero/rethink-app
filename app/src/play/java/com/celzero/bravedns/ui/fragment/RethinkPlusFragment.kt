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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_IAB
import Logger.LOG_TAG_PROXY
import Logger.LOG_TAG_UI
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter.SubscriptionClickListener
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.iab.Result.resultState
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.ui.activity.RpnAvailabilityCheckActivity
import com.celzero.bravedns.ui.activity.RethinkPlusDashboardActivity
import com.celzero.bravedns.ui.dialog.SubscriptionAnimDialog
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.UIUtils.underline
import com.celzero.bravedns.util.Utilities
import com.facebook.shimmer.Shimmer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus), SubscriptionClickListener {
    private val b by viewBinding(FragmentRethinkPlusBinding::bind)
    private val persistentState by inject<PersistentState>()
    private var productId = ""
    private var planId = ""
    private lateinit var loadingDialog: AlertDialog
    private lateinit var errorDialog: AlertDialog

    companion object {
        private const val TAG = "PR+Ui"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBillingSetup()
        initView()
        initObservers()
        collectPurchases()
        setupClickListeners()
    }

    override fun onSubscriptionSelected(prodId: String, planId: String) {
        productId = prodId
        this.planId = planId
        Logger.d(LOG_IAB, "Selected product: $productId, $planId")
    }

    private fun initView() {
        // show a loading dialog
        showLoadingDialog()
        io {
            val isRethinkPlusSubscribed = isRethinkPlusSubscribed() // based on persistent state

            if (isRethinkPlusSubscribed) {
                uiCtx { handlePlusSubscribed() }
                return@io
            }

            val works = isRethinkPlusAvailable()

            if (!works.first) {
                uiCtx { showRethinkNotAvailableUi(works.second) }
                return@io
            }

            // perform initial checks whether the proxy is working or not
            // should we do this if rethink+ is already subscribed?
            val isTestOk = isTestOk()

            if (!isTestOk) {
                uiCtx { showRethinkNotAvailableUi("No network connectivity") }
                return@io
            }

            // initiate the product details query
            queryProductDetail()
        }
    }

    private fun initTermsAndPolicy() {
        b.termsText.text = updateHtmlEncodedText(getString(R.string.rethink_terms))
        b.termsText.movementMethod = LinkMovementMethod.getInstance()
        b.termsText.highlightColor = Color.TRANSPARENT
    }

    fun updateHtmlEncodedText(text: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    override fun onResume() {
        super.onResume()
        startShimmer()
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    private fun stopShimmer() {
        if (!b.shimmerViewContainer.isShimmerStarted) return

        b.shimmerViewContainer.stopShimmer()
    }

    private fun startShimmer() {
        if (!b.shimmerViewContainer.isVisible) return

        if (b.shimmerViewContainer.isShimmerStarted) return

        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(2000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        b.shimmerViewContainer.setShimmer(builder.build())
        b.shimmerViewContainer.startShimmer()
    }

    private fun isBillingAvailable(): Boolean {
        return InAppBillingHandler.isBillingClientSetup()
    }

    private fun ensureBillingSetup() {
        if (isBillingAvailable()) {
            Logger.i(LOG_IAB, "ensureBillingSetup: billing client already setup")
            return
        }

        InAppBillingHandler.initiate(requireContext().applicationContext)
        Logger.i(LOG_IAB, "ensureBillingSetup: billing client initiated")
    }

    private suspend fun queryProductDetail() {
        ensureBillingSetup()
        InAppBillingHandler.queryProductDetailsWithTimeout()
        Logger.v(LOG_IAB, "queryProductDetails: initiated")
    }

    private fun purchaseSubs() {
        if (!isBillingAvailable()) {
            Logger.e(LOG_IAB, "purchaseSubs: billing client not available")
            Utilities.showToastUiCentered(
                requireContext(),
                "Billing client not available, please try again later",
                Toast.LENGTH_LONG
            )
            return
        }
        // initiate the payment flow
        InAppBillingHandler.purchaseSubs(requireActivity(), productId, planId)
        Logger.v(LOG_IAB, "purchaseSubs: initiated for $productId, $planId")
    }

    private fun showLoadingDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        // show progress dialog
        builder.setTitle("Loading")
        builder.setMessage("Please wait while we check the availability of Rethink+.")
        builder.setCancelable(false)
        loadingDialog = builder.create()
        loadingDialog.show()
    }

    private fun hideLoadingDialog() {
        Logger.v(LOG_IAB, "hide loading dialog")
        if (this::loadingDialog.isInitialized && loadingDialog.isShowing) {
            loadingDialog.dismiss()
        }
        Logger.v(LOG_IAB, "loading dialog dismissed")
    }

    private suspend fun isRethinkPlusAvailable(): Pair<Boolean, String> {
        // check whether the rethink+ is available for the user or not
        return Pair(true, "Rethink+ is not available for your device")
    }

    private fun showPaymentContainerUi(purc: List<ProductDetail> = emptyList()) {
        initTermsAndPolicy()
        hideLoadingDialog()
        hidePlusSubscribedUi()
        hideNotAvailableUi()

        b.shimmerViewContainer.visibility = View.VISIBLE
        b.paymentContainer.visibility = View.VISIBLE
        b.paymentButtonContainer.visibility = View.VISIBLE
        b.testPingButton.underline()
        setAdapter(purc)
        Logger.i(LOG_IAB, "adapter set")
    }

    private fun hidePaymentContainerUi() {
        b.paymentContainer.visibility = View.GONE
        b.paymentButtonContainer.visibility = View.GONE
        b.shimmerViewContainer.visibility = View.GONE
    }

    private fun hidePlusSubscribedUi() {
        b.subscribedLayout.visibility = View.GONE
    }

    private fun showNotAvailableUi() {
        hideLoadingDialog()
        hidePlusSubscribedUi()
        hidePaymentContainerUi()
        b.notAvailableLayout.visibility = View.VISIBLE
    }

    private fun hideNotAvailableUi() {
        b.notAvailableLayout.visibility = View.GONE
    }

    private fun showPlusSubscribedUi() {
        hideLoadingDialog()
        b.subscribedLayout.visibility = View.VISIBLE
        hidePaymentContainerUi()
        hideNotAvailableUi()
        val state = RpnProxyManager.RpnState.fromId(persistentState.rpnState)
        b.pausePlus.text = if (state.isPaused()) "Resume Rethink+" else "Pause Rethink+"
    }

    private suspend fun isTestOk(): Boolean {
        val warp = VpnController.testRpnProxy(RpnProxyManager.RpnType.WARP)
        val amz = VpnController.testRpnProxy(RpnProxyManager.RpnType.AMZ)
        val proton = VpnController.testRpnProxy(RpnProxyManager.RpnType.PROTON)
        val se = VpnController.testRpnProxy(RpnProxyManager.RpnType.SE)
        val x64 = VpnController.testRpnProxy(RpnProxyManager.RpnType.EXIT_64)
        Logger.i(
            LOG_IAB,
            "$TAG test ok?: warp: $warp, amz: $amz, proton: $proton, se: $se, w64: $x64"
        )

        val works = warp || amz || proton || se || x64
        return works
    }

    private fun showRethinkNotAvailableUi(msg: String) {
        showNotAvailableUi()
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("Plus not available")
        builder.setMessage("Rethink+ is not available for your device.\nreason: $msg")
        builder.setCancelable(false)
        builder.setPositiveButton(requireContext().getString(R.string.dns_info_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            findNavController().navigate(R.id.action_switch_to_homeScreenFragment)
        }
        builder.create().show()
    }

    private fun setAdapter(list: List<ProductDetail>) {
        if (list.isEmpty()) {
            Logger.d(LOG_IAB, "pricing phase list is empty/initialized")
            return
        }
        // set the adapter for the recycler view
        Logger.i(LOG_IAB, "setting adapter for the recycler view: ${list.size}")
        b.subscriptionPlans.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(requireContext())
        b.subscriptionPlans.layoutManager = layoutManager
        b.subscriptionPlans.adapter = GooglePlaySubsAdapter(this, requireContext(), list)
    }

    private fun collectPurchases() {
        InAppBillingHandler.purchasesLiveData.observe(viewLifecycleOwner) { list ->
            Logger.d(LOG_IAB, "collectPurchases: Purchase details: ${list.size}")
            if (list.isEmpty()) {
                Logger.d(LOG_IAB, "No purchases found")
                RpnProxyManager.deactivateRpn() // rethink+ not subscribed
                // initiate the product details query
                io { queryProductDetail() }
                return@observe
            }

            list.forEach { it ->
                if (it.state == Purchase.PurchaseState.PURCHASED && it.productType == ProductType.SUBS) {
                    if (isAdded && isVisible) {
                        showConfettiEffect()
                        handlePlusSubscribed()
                    }

                    // add the purchase details to the databased
                    Logger.d(
                        LOG_IAB,
                        "Purchase details: ${it.state}, ${it.productId}, ${it.purchaseToken}, ${it.productTitle}, ${it.purchaseTime}, ${it.productType}, ${it.planId}"
                    )
                }
            }
        }
    }

    private fun initObservers() {
        resultState.observe(viewLifecycleOwner) { i ->
            Logger.d(LOG_IAB, "res state: ${i.name}, ${i.message};p? ${i.priority}")
            if (i.priority == InAppBillingHandler.Priority.HIGH) {
                Logger.e(LOG_IAB, "res failure: ${i.name}, ${i.message}; p? ${i.priority}")
                if (isAdded && isVisible && this::errorDialog.isInitialized && !errorDialog.isShowing) {
                    hideLoadingDialog()
                    showErrorDialog(i.message)
                }
            }
            if (DEBUG) {
                b.showStatus.visibility = View.VISIBLE
                b.showStatus.text = i.message
            }
        }

        InAppBillingHandler.connectionStateLiveData.observe(viewLifecycleOwner) { i ->

            if (!i.isSuccess) {
                Logger.e(LOG_IAB, "Billing connection failed: ${i.message}")
                ui {
                    if (isAdded && isVisible) {
                        hideLoadingDialog()
                        Utilities.showToastUiCentered(
                            requireContext(),
                            i.message,
                            Toast.LENGTH_SHORT
                        )
                    }
                }
                return@observe
            }
            // check for the subscription status after the connection is established
            val productType = listOf(ProductType.SUBS)
            InAppBillingHandler.fetchPurchases(productType)
        }

        InAppBillingHandler.productDetailsLiveData.observe(viewLifecycleOwner) { list ->
            Logger.d(LOG_IAB, "product details: ${list.size}")
            if (list.isEmpty()) {
                Logger.e(LOG_IAB, "product details is empty")
                ui {
                    if (isAdded && isVisible) {
                        hideLoadingDialog()
                        Utilities.showToastUiCentered(
                            requireContext(),
                            "Error fetching product details",
                            Toast.LENGTH_SHORT
                        )
                    }
                }
                return@observe
            }
            val products = list.filter { it.productType == ProductType.SUBS }
            val first = list.first()
            productId = first.productId
            planId = first.planId
            val product = first.pricingDetails
            Logger.i(
                LOG_IAB,
                "Product details: ${first.productId}, ${first.planId}, ${first.productTitle}, ${first.productType}, ${first.pricingDetails.size}"
            )
            if (isAdded && isVisible) {
                showPaymentContainerUi(products)
            }
        }
    }

    private fun showErrorDialog(msg: String) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("Error")
        builder.setMessage(msg)
        builder.setCancelable(false)
        builder.setPositiveButton(requireContext().getString(R.string.dns_info_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        errorDialog = builder.create()
        errorDialog.show()
    }

    private fun showConfettiEffect() {
        SubscriptionAnimDialog().show(childFragmentManager, "SubscriptionAnimDialog")
    }

    private fun handlePlusSubscribed() {
        showPlusSubscribedUi()
        RpnProxyManager.activateRpn()
    }

    private fun setupClickListeners() {

        b.paymentButton.setOnClickListener {
            purchaseSubs()
        }

        b.testPingButton.setOnClickListener {
            if (!VpnController.hasTunnel()) {
                Logger.i(LOG_IAB, "$TAG; VPN not active, cannot perform tests")
                Utilities.showToastUiCentered(
                    requireContext(),
                    getString(R.string.settings_socks5_vpn_disabled_error),
                    Toast.LENGTH_LONG
                )
                return@setOnClickListener
            }
            val intent = Intent(requireContext(), RpnAvailabilityCheckActivity::class.java)
            startActivity(intent)
        }

        b.manageSubscription.setOnClickListener {
            // open the manage subscription page
            managePlayStoreSubs()
        }

        b.paymentHistory.setOnClickListener {
            openBillingHistory()
        }

        b.dashboard.setOnClickListener {
            val intent = Intent(requireContext(), RethinkPlusDashboardActivity::class.java)
            startActivity(intent)
        }

        b.contactSupport.setOnClickListener {
            // no-op
        }

        b.pausePlus.setOnClickListener {
            val state = RpnProxyManager.RpnState.fromId(persistentState.rpnState)
            if (state.isPaused()) {
                RpnProxyManager.activateRpn()
                b.pausePlus.text = "Pause Rethink+"
            } else {
                RpnProxyManager.pauseRpn()
                b.pausePlus.text = "Resume Rethink+"
            }
        }
    }

    private fun openBillingHistory() {
        val link = InAppBillingHandler.HISTORY_LINK
        openUrl(requireContext(), link)
    }

    private fun managePlayStoreSubs() {
        // link for the play store which has placeholders for subscription id and package name
        val link = InAppBillingHandler.LINK
        // replace $1 with subscription id
        // replace $2 with package name
        val linkWithSubs = link.replace("\$1", InAppBillingHandler.PROD_ID_MONTHLY_TEST)
        val linkWithSubsAndPackage = linkWithSubs.replace("\$2", requireContext().packageName)
        openUrl(requireContext(), linkWithSubsAndPackage)
    }

    private fun isRethinkPlusSubscribed(): Boolean {
        // check whether the user has already subscribed to Rethink+ or not in database
        return RpnProxyManager.isRpnActive() // for now
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(f: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
