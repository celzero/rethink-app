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
 *//*

package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_IAB
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import backend.Backend
import by.kirich1409.viewbindingdelegate.viewBinding
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PricingPhase
import com.celzero.bravedns.iab.stripe.CustomerCreateParams
import com.celzero.bravedns.iab.stripe.PaymentIntentResponse
import com.celzero.bravedns.iab.stripe.PricesAdapter
import com.celzero.bravedns.iab.stripe.RetrofitInstance
import com.celzero.bravedns.iab.stripe.RetrofitInstance.CustomerResponse
import com.celzero.bravedns.iab.stripe.SubscriptionViewModel
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.RPN_AMZ_ID
import com.celzero.bravedns.rpnproxy.RpnProxyManager.WARP_ID
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.activity.PingTestActivity
import com.celzero.bravedns.ui.activity.TroubleshootActivity
import com.celzero.bravedns.ui.dialog.SubscriptionAnimDialog
import com.celzero.bravedns.util.UIUtils.underline
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.RequestBody
import org.koin.android.ext.android.inject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus) {
    private val b by viewBinding(FragmentRethinkPlusBinding::bind)
    private val persistentState by inject<PersistentState>()
    private var productId = ""
    private var planId = ""
    private lateinit var loadingDialog: AlertDialog
    private lateinit var errorDialog: AlertDialog
    private val stripeViewModel by inject<SubscriptionViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaymentConfiguration.init(requireContext().applicationContext, "")
        paymentSheet = PaymentSheet(this, ::onPaymentSheetResult)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBillingSetup()
        initView()
        initObservers()
        collectPurchases()
        setupClickListeners()
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

            addWarpSEToTunnel()

            // perform initial checks whether the proxy is working or not
            // should we do this if rethink+ is already subscribed?
            val isTestOk = isTestOk()

            if (!isTestOk) {
                uiCtx { showTestContainerUi() }
                return@io
            }

            // initiate the product details query
            queryProductDetail()
        }
    }

    private fun isBillingAvailable(): Boolean {
        return InAppBillingHandler.isBillingClientSetup()
    }

    private fun ensureBillingSetup() {
        if (isBillingAvailable()) {
            Logger.i(LOG_IAB, "ensureBillingSetup: billing client already setup")
            return
        }

        InAppBillingHandler.initiate(requireContext(), null)
        Logger.i(LOG_IAB, "ensureBillingSetup: billing client initiated")
    }

    private suspend fun queryProductDetail() {
        ensureBillingSetup()
        InAppBillingHandler.queryProductDetailsWithTimeout()
        Logger.v(LOG_IAB, "queryProductDetails: initiated")
    }

    private fun purchaseStripe() {
        createPaymentIntent()
    }

    private lateinit var paymentSheet: PaymentSheet
    private var clientSecret: String? = null

    private fun createPaymentIntent() {
        val stripeApi = RetrofitInstance.paymentApi
        val secretKey = ""

        createCustomerIfNeeded(secretKey)

        val formBody: RequestBody = FormBody.Builder()
            .add("amount", "100")
            .add("currency", "usd")
            .add("description", "Test Rethink Plus")
            .add("customer", "")
            .build()

        stripeApi.createPaymentIntent(secretKey, formBody)
            .enqueue(object : Callback<PaymentIntentResponse> {
                override fun onResponse(
                    call: Call<PaymentIntentResponse>,
                    response: Response<PaymentIntentResponse>
                ) {
                    Logger.i("StripeApi", "onResponse, ${response.body()}")
                    if (response.isSuccessful) {
                        clientSecret = response.body()?.client_secret
                        presentPaymentSheet()
                        Logger.i("StripeApi", "presenting payment sheet, res success")
                    } else {
                        // Handle errors
                        Logger.i("StripeApi", "onResponse, ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<PaymentIntentResponse>, t: Throwable) {
                    // Handle failure
                    Logger.i("StripeApi", "onFailure, ${t.message}")
                }
            })
    }

    private fun createCustomerIfNeeded(secretKey: String) {
        val stripeCustomerService = RetrofitInstance.stripeCustomerService

        val customerParams = CustomerCreateParams(
            email = "customer@example.com",
            name = "John Doe",
            description = "Test customer",
            city = "San Francisco",
            country = "US"
        )

        stripeCustomerService.createCustomer(secretKey, customerParams)
            .enqueue(object : Callback<CustomerResponse> {
                override fun onResponse(call: Call<CustomerResponse>, response: Response<CustomerResponse>) {
                    if (response.isSuccessful) {
                        val customerResponse = response.body()
                        println("Customer Created: $customerResponse")
                        customerResponse?.let {
                            // Retrieve the customer ID
                            val customerId = it.id
                            println("Customer ID: $customerId")
                        } ?: run {
                            println("Response body is null")
                        }
                    } else {
                        println("Error Response: ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<CustomerResponse>, t: Throwable) {
                    println("API Call Failed: ${t.message}")
                }
            })
    }

    private fun presentPaymentSheet() {
        val paymentSheetConfiguration = PaymentSheet.Configuration(
            merchantDisplayName = "Rethink Plus",
            allowsDelayedPaymentMethods = true
        )
        Logger.i("StripeApi", "presentWithPaymentIntent, $paymentSheetConfiguration")
        paymentSheet.presentWithPaymentIntent(clientSecret!!, paymentSheetConfiguration)
    }

    private fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) {
        when (paymentSheetResult) {
            is PaymentSheetResult.Completed -> {
                // Payment successful
                Logger.e("StripeApi", "Payment successful")
            }

            is PaymentSheetResult.Failed -> {
                // Handle failure
                Logger.e("StripeApi", "Payment failed")
            }

            is PaymentSheetResult.Canceled -> {
                // Handle cancellation
                Logger.e("StripeApi", "Payment cancelled")
            }
        }
    }

    private fun purchaseSubs() {
        if (!isBillingAvailable()) {
            Logger.e(LOG_IAB, "purchaseSubs: billing client not available")
            Utilities.showToastUiCentered(requireContext(), "Billing client not available, please try again later", Toast.LENGTH_LONG)
            return
        }
        // initiate the payment flow
        InAppBillingHandler.purchaseSubs(requireActivity(), productId, planId)
        Logger.v(LOG_IAB, "purchaseSubs: initiated")
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
        val warpWorks = RpnProxyManager.isWarpWorking()
        Logger.i(LOG_IAB, "warp works: $warpWorks")
        return warpWorks
    }

    private suspend fun handleWarp(): Boolean {
        // see if the warp conf is available, if not create a new one
        val cf = RpnProxyManager.getWarpConfig()
        if (cf == null) {
            return createWarpConfig()
        } else {
            Logger.i(LOG_IAB, "warp config already exists")
        }
        return true
    }

    private suspend fun handleAmnezia(): Boolean {
        // see if the amnezia conf is available, if not create a new one
        val cf = RpnProxyManager.getAmneziaConfig()
        if (cf == null) {
            return createAmneziaConfig()
        } else {
            Logger.i(LOG_IAB, "amz config already exists")
        }
        return true
    }

    private suspend fun createWarpConfig(): Boolean {
        // create a new warp config
        val config = RpnProxyManager.getNewWarpConfig(true, WARP_ID, 0)
        if (config == null) {
            Logger.e(LOG_IAB, "err creating warp config")
            showConfigCreationError(getString(R.string.new_warp_error_toast))
            return false
        }
        return true
    }

    private suspend fun createAmneziaConfig(): Boolean {
        // create a new amnezia config
        val config = RpnProxyManager.getNewAmneziaConfig(RPN_AMZ_ID)
        if (config == null) {
            Logger.e(LOG_IAB, "err creating amz config")
            showConfigCreationError("Error creating Amnezia config")
            return false
        }
        return true
    }

    private suspend fun addAmneziaToTunnel() {
        if (!handleAmnezia()) {
            Logger.e(LOG_IAB, "err handling amz")
            return
        }
        val c = RpnProxyManager.getAmneziaConfig()
        val config = RpnProxyManager.getAmneziaConfig().first
        if (config == null) {
            Logger.e(LOG_IAB, "err adding amz to tunnel")
            showConfigCreationError("Error adding amz to tunnel")
            return
        }
        if (c.second) {
            Logger.i(LOG_IAB, "amz already active")
            return
        }
        Logger.i(LOG_IAB, "enabling amnezia(amz) config")
        RpnProxyManager.enableConfig(config.getId())
    }

    private suspend fun addWarpToTunnel() {
        if (!handleWarp()) {
            Logger.e(LOG_IAB, "err handling warp")
            return
        }
        val cf = RpnProxyManager.getWarpConfig()
        val config = RpnProxyManager.getWarpConfig().first
        if (config == null) {
            Logger.e(LOG_IAB, "err adding warp to tunnel")
            showConfigCreationError(getString(R.string.new_warp_error_toast))
            return
        }
        if (cf.second) {
            Logger.i(LOG_IAB, "warp already active")
            return
        }
        Logger.i(LOG_IAB, "enabling warp config")
        RpnProxyManager.enableConfig(config.getId())
    }

    private suspend fun registerSEToTunnel() {
        // add the SE to the tunnel
        val isRegistered = VpnController.registerSEToTunnel()
        if (!isRegistered) {
            Logger.e(LOG_IAB, "err registering SE to tunnel")
            showConfigCreationError("Error registering SE to tunnel")
        }
        Logger.i(LOG_IAB, "SE registered to tunnel")
    }

    private fun showPaymentContainerUi(purc: List<PricingPhase> = emptyList()) {
        hideLoadingDialog()
        hidePlusSubscribedUi()
        hideNotAvailableUi()
        hideTestLayoutUi()
        b.paymentContainer.visibility = View.VISIBLE
        b.testPingButton.underline()
        // set adapter for the recycler view, create a new adapter in this file
        if (Utilities.isFdroidFlavour() || Utilities.isWebsiteFlavour()) {
            setStripeAdapter()
        } else {
            setAdapter(purc)
        }
        Logger.i(LOG_IAB, "adapter set")
    }

    private fun setStripeAdapter() {
        val adapter = PricesAdapter()
        b.subscriptionPlans.layoutManager = LinearLayoutManager(context)
        b.subscriptionPlans.adapter = adapter

        stripeViewModel.pricesLiveData.observe(viewLifecycleOwner) { prices ->
            adapter.submitList(prices)
        }

        stripeViewModel.fetchPrices()
    }

    private fun hidePaymentContainerUi() {
        b.paymentContainer.visibility = View.GONE
    }

    private fun hidePlusSubscribedUi() {
        b.subscribedLayout.visibility = View.GONE
    }

    private fun showNotAvailableUi() {
        hideLoadingDialog()
        hidePlusSubscribedUi()
        hidePaymentContainerUi()
        hideTestLayoutUi()
        b.notAvailableLayout.visibility = View.VISIBLE
    }

    private fun hideNotAvailableUi() {
        b.notAvailableLayout.visibility = View.GONE
    }

    private fun hideTestLayoutUi() {
        b.testLayout.visibility = View.GONE
    }

    private fun showPlusSubscribedUi() {
        hideLoadingDialog()
        b.subscribedLayout.visibility = View.VISIBLE
        hidePaymentContainerUi()
        hideTestLayoutUi()
        hideNotAvailableUi()
        val res = if (persistentState.enableWarp) "Enable" else "Disable"
        b.troubleshoot.text = "Troubleshoot: $res"
        b.pausePlus.text = if (persistentState.enableWarp) "Pause Rethink+" else "Resume Rethink+" // for testing purpose
    }

    private suspend fun isTestOk(): Boolean {
        val warp = VpnController.testWarp()
        val amz = VpnController.testAmz()
        val proton = VpnController.testProton()
        val se = VpnController.testSE()
        val x64 = VpnController.testExit64()
        Logger.i(LOG_IAB, "test ok?: warp: $warp, amz: $amz, proton: $proton, se: $se, w64: $x64")

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

    private fun showTestContainerUi() {
        hideLoadingDialog()
        b.testLayout.visibility = View.VISIBLE
    }

    private fun setAdapter(list: List<PricingPhase>) {
        if (list.isEmpty()) {
            Logger.d(LOG_IAB, "pricing phase list is empty/initialized")
            return
        }
        // set the adapter for the recycler view
        Logger.i(LOG_IAB, "setting adapter for the recycler view: ${list.size}")
        b.subscriptionPlans.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(requireContext())
        b.subscriptionPlans.layoutManager = layoutManager
        b.subscriptionPlans.adapter = GooglePlaySubsAdapter(list)
    }

    private fun collectPurchases() {
        InAppBillingHandler.purchasesLiveData.observe(viewLifecycleOwner) { list ->
            Logger.d(LOG_IAB, "collectPurchases: Purchase details: ${list.size}")
            if (list.isEmpty()) {
                Logger.d(LOG_IAB, "No purchases found")
                persistentState.enableWarp = false // rethink+ not subscribed
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
        Result.getResultStateLiveData().distinctUntilChanged().observe(viewLifecycleOwner) { i ->
            Logger.d(LOG_IAB, "res state: ${i.name}, ${i.message};p? ${i.priority}")
            if (i.priority == InAppBillingHandler.Priority.HIGH) {
                Logger.e(LOG_IAB, "res failure: ${i.name}, ${i.message}; p? ${i.priority}")
                if (isAdded && isVisible && this::errorDialog.isInitialized && !errorDialog.isShowing) {
                    hideLoadingDialog()
                    showErrorDialog(i.message)
                }
            }
            b.showStatus.text = i.message
        }

        InAppBillingHandler.connectionStateLiveData.observe(viewLifecycleOwner) { i ->
            Logger.d(LOG_IAB, "onConnectionResult: isSuccess: ${i.isSuccess}, message: ${i.message}")
            if (!i.isSuccess) {
                Logger.e(LOG_IAB, "Billing connection failed: ${i.message}")
                ui {
                    if (isAdded && isVisible) {
                        hideLoadingDialog()
                        Utilities.showToastUiCentered(requireContext(), i.message, Toast.LENGTH_SHORT)
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
            val first = list.first()
            productId = first.productId
            planId = first.planId
            val product = first.pricingDetails
            Logger.i(LOG_IAB, "Product details: ${first.productId}, ${first.planId}, ${first.productTitle}, ${first.productType}, ${first.pricingDetails.size}")
            if (isAdded && isVisible) {
                showPaymentContainerUi(product)
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
        io { addWarpSEToTunnel() }
        persistentState.enableWarp = true
    }

    private suspend fun addWarpSEToTunnel() {
        addWarpToTunnel()
        addAmneziaToTunnel()
        registerSEToTunnel()
    }

    private fun setupClickListeners() {
        b.termsAndConditions.setOnClickListener {
            b.termsAndConditionsText.visibility =
                if (b.termsAndConditionsText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        b.paymentButton.setOnClickListener {
            if (Utilities.isWebsiteFlavour() || Utilities.isFdroidFlavour()) {
                purchaseStripe()
            } else {
                purchaseSubs()
            }
        }

        b.testPingButton.setOnClickListener {
            val intent = Intent(requireContext(), PingTestActivity::class.java)
            startActivity(intent)
        }

        b.testPing.setOnClickListener {
            val intent = Intent(requireContext(), PingTestActivity::class.java)
            startActivity(intent)
        }

        b.manageSubscription.setOnClickListener {
            // open the manage subscription page
            managePlayStoreSubs()
        }

        b.paymentHistory.setOnClickListener {
            openBillingHistory()
        }

        b.troubleshoot.setOnClickListener {
            val intent = Intent(requireContext(), TroubleshootActivity::class.java)
            startActivity(intent)
        }

        b.refreshWarp.setOnClickListener {
            io {
                createWarpConfig()
                addWarpToTunnel()
            }
        }

        b.contactSupport.setOnClickListener {
            io { isTestOk() }
        }

        b.pausePlus.setOnClickListener {
            if (persistentState.enableWarp) {
                persistentState.enableWarp = false
                val warp = WireguardManager.getConfigFilesById(WARP_ID)
                if (warp == null) {
                    Logger.e(LOG_IAB, "err getting warp config")
                    return@setOnClickListener
                }
                WireguardManager.disableConfig(warp)
            } else {
                persistentState.enableWarp = true
                io {
                    addWarpToTunnel()
                    addAmneziaToTunnel()
                    registerSEToTunnel()
                }
            }
            b.pausePlus.text = if (persistentState.enableWarp) "Pause Rethink+" else "Resume Rethink+"
        }
    }

    private fun openBillingHistory() {
        try {
            val link = InAppBillingHandler.HISTORY_LINK
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(
                requireContext(),
                "play store not found",
                Toast.LENGTH_SHORT
            )
            Logger.e(LOG_IAB, "Play store not found", e)
        }
    }

    private fun managePlayStoreSubs() {
        try {
            // link for the play store which has placeholders for subscription id and package name
            val link = InAppBillingHandler.LINK
            // replace $1 with subscription id
            // replace $2 with package name
            val linkWithSubs = link.replace("\$1", InAppBillingHandler.PRODUCT_ID_TEST)
            val linkWithSubsAndPackage = linkWithSubs.replace("\$2", requireContext().packageName)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkWithSubsAndPackage)))
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(
                requireContext(),
                "Play store not found",
                Toast.LENGTH_SHORT
            )
            Logger.e(LOG_IAB, "Play store not found", e)
        }
    }

    private suspend fun showConfigCreationError(msg: String) {
        uiCtx {
            if (isAdded && isVisible) {
                Utilities.showToastUiCentered(
                    requireContext(),
                    msg,
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    */
/*private val billingListener = object : BillingListener {
        override fun onConnectionResult(isSuccess: Boolean, message: String) {
            *//*
*/
/*Logger.d(LOG_IAB, "onConnectionResult: isSuccess: $isSuccess, message: $message")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "Billing connection failed: $message")
                ui {
                    if (isAdded && isVisible) {
                        Utilities.showToastUiCentered(requireContext(), message, Toast.LENGTH_SHORT)
                    }
                }
                return
            }
            // check for the subscription status after the connection is established
            val productType = listOf(ProductType.SUBS)
            InAppBillingHandler.fetchPurchases(productType)*//*
*/
/*
        }

        override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
            *//*
*/
/*if (!isSuccess) {
                Logger.e(LOG_IAB, "query purchase details failed")
                // TODO: should we show a toast here / retry the query?
                return
            }

            if (purchaseDetailList.isEmpty()) {
                Logger.d(LOG_IAB, "No purchases found")
                persistentState.enableWarp = false // rethink+ not subscribed
                // initiate the product details query
                io { queryProductDetail() }
                return
            }

            Logger.d(LOG_IAB, "purchasesResult: Purchase details: ${purchaseDetailList.size}")
            purchaseDetailList.forEach { it ->
                if (it.state == Purchase.PurchaseState.PURCHASED && it.productType == ProductType.SUBS) {
                    io {
                        uiCtx {
                            showConfettiEffect()
                            handlePlusSubscribed()
                        }
                    }

                    // add the purchase details to the databased
                    Logger.d(
                        LOG_IAB,
                        "Purchase details: ${it.state}, ${it.productId}, ${it.purchaseToken}, ${it.productTitle}, ${it.purchaseTime}, ${it.productType}, ${it.planId}"
                    )
                }
            }*//*
*/
/*
        }

        override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
            *//*
*/
/*Logger.d(LOG_IAB, "productResult: Product details: ${productList.size}, $isSuccess")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "Product details failed")
                io {
                    uiCtx {
                        if (isAdded && isVisible) {
                            Utilities.showToastUiCentered(
                                requireContext(),
                                "Error fetching product details",
                                Toast.LENGTH_LONG
                            )
                        }
                    }
                }
                return
            }
            if (persistentState.enableWarp) {
                Logger.i(LOG_IAB, "User already subscribed to Rethink+")
                return
            }
            val first = productList.firstOrNull() // use the first product details for now
            if (first == null) {
                Logger.e(LOG_IAB, "Product details is null")
                return
            }
            productId = first.productId
            planId = first.planId
            val product = first.pricingDetails
            Logger.i(LOG_IAB, "Product details: ${first.productId}, ${first.planId}, ${first.productTitle}, ${first.productType}, ${first.pricingDetails.size}")
            io {
                uiCtx {
                    if (isAdded && isVisible) {
                        showPaymentContainerUi(product)
                    }
                }
            }*//*
*/
/*
        }
    }*//*


    private fun isRethinkPlusSubscribed(): Boolean {
        // check whether the user has already subscribed to Rethink+ or not in database
        return persistentState.enableWarp // for now
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
*/
