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
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.iab.stripe.CustomerCreateParams
import com.celzero.bravedns.iab.stripe.PaymentIntentResponse
import com.celzero.bravedns.iab.stripe.PricesAdapter
import com.celzero.bravedns.iab.stripe.RetrofitInstance
import com.celzero.bravedns.iab.stripe.RetrofitInstance.CustomerResponse
import com.celzero.bravedns.iab.stripe.SubscriptionViewModel
import com.celzero.bravedns.rpnproxy.RpnProxyManager
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
    private lateinit var loadingDialog: AlertDialog
    private lateinit var errorDialog: AlertDialog

    // not injecting the viewmodel as it is used based on build variant
    private val stripeViewModel: SubscriptionViewModel by lazy {
        SubscriptionViewModel()
    }

    companion object {
        private const val TAG = "RPlusWFUi"
        // added for testing purpose, will be removed later
        private const val PUBLISHABLE_KEY = ""
        private const val SECRET_KEY = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaymentConfiguration.init(requireContext().applicationContext, PUBLISHABLE_KEY)
        paymentSheet = PaymentSheet(this, ::onPaymentSheetResult)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
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

            val works = isRethinkPlusAvailable() // returns <res, msg>

            if (!works.first) {
                uiCtx { showRethinkNotAvailableUi(works.second) }
                return@io
            }

            // perform initial checks whether the proxy is working or not
            if (!isTestOk()) {
                uiCtx { showTestContainerUi() }
                return@io
            }

            // initiate the product details query
            uiCtx{ showPaymentContainerUi() }
        }
    }

    private fun purchaseStripe() {
        createPaymentIntent()
    }

    private lateinit var paymentSheet: PaymentSheet
    private var clientSecret: String? = null

    private fun createPaymentIntent() {
        val stripeApi = RetrofitInstance.paymentApi

        createCustomerIfNeeded(SECRET_KEY)

        val formBody: RequestBody = FormBody.Builder()
            .add("amount", "100")
            .add("currency", "usd")
            .add("description", "Test Rethink Plus")
            .add("customer", "")
            .build()

        stripeApi.createPaymentIntent(SECRET_KEY, formBody)
            .enqueue(object : Callback<PaymentIntentResponse> {
                override fun onResponse(
                    call: Call<PaymentIntentResponse>,
                    response: Response<PaymentIntentResponse>
                ) {
                    Logger.i(LOG_IAB, "$TAG cp onResponse, ${response.body()}")
                    if (response.isSuccessful) {
                        clientSecret = response.body()?.client_secret
                        presentPaymentSheet()
                        Logger.i(LOG_IAB, "$TAG cp presenting payment sheet, res success")
                    } else {
                        // Handle errors
                        Logger.i(LOG_IAB, "$TAG cp onResponse, ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<PaymentIntentResponse>, t: Throwable) {
                    // Handle failure
                    Logger.i(LOG_IAB, "$TAG cp onFailure, ${t.message}")
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
                        Logger.i(LOG_IAB, "$TAG cc customer created: $customerResponse")
                        customerResponse?.let {
                            // Retrieve the customer ID
                            val customerId = it.id
                            Logger.i(LOG_IAB, "$TAG cc customer ID: $customerId")
                        } ?: run {
                            Logger.i(LOG_IAB, "$TAG cc res body is null")
                        }
                    } else {
                        Logger.i(LOG_IAB, "$TAG cc err res: ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<CustomerResponse>, t: Throwable) {
                    Logger.w(LOG_IAB, "$TAG cc failure: ${t.message}", Exception(t))
                }
            })
    }

    private fun presentPaymentSheet() {
        val paymentSheetConfiguration = PaymentSheet.Configuration(
            merchantDisplayName = "Rethink Plus",
            allowsDelayedPaymentMethods = true
        )
        Logger.i(LOG_IAB, "$TAG presentWithPaymentIntent, $paymentSheetConfiguration")
        paymentSheet.presentWithPaymentIntent(clientSecret!!, paymentSheetConfiguration)
    }

    private fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) {
        when (paymentSheetResult) {
            is PaymentSheetResult.Completed -> {
                // Payment successful
                persistentState.useRpn = true
                Logger.e(LOG_IAB, "$TAG payment successful")
            }

            is PaymentSheetResult.Failed -> {
                // Handle failure
                persistentState.useRpn = false
                Logger.e(LOG_IAB, "$TAG payment failed, ${paymentSheetResult.error}")
            }

            is PaymentSheetResult.Canceled -> {
                // Handle cancellation
                persistentState.useRpn = false
                Logger.e(LOG_IAB, "$TAG payment cancelled")
            }
        }
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
        Logger.v(LOG_IAB, "$TAG hide loading dialog")
        if (this::loadingDialog.isInitialized && loadingDialog.isShowing) {
            loadingDialog.dismiss()
        }
        Logger.v(LOG_IAB, "$TAG loading dialog dismissed")
    }

    private suspend fun isRethinkPlusAvailable(): Pair<Boolean, String> {
        val warpWorks = RpnProxyManager.isWarpWorking()
        Logger.i(LOG_IAB, "$TAG warp works: $warpWorks")
        return warpWorks
    }

    private fun showPaymentContainerUi() {
        hideLoadingDialog()
        hidePlusSubscribedUi()
        hideNotAvailableUi()
        hideTestLayoutUi()
        b.paymentContainer.visibility = View.VISIBLE
        b.testPingButton.underline()
        setStripeAdapter()
        Logger.i(LOG_IAB, "$TAG adapter set")
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
        val res = if (persistentState.useRpn) "Enable" else "Disable"
        b.troubleshoot.text = "Troubleshoot: $res"
        b.pausePlus.text = if (persistentState.useRpn) "Pause Rethink+" else "Resume Rethink+" // for testing purpose
    }

    private suspend fun isTestOk(): Boolean {
        val warp = VpnController.testWarp()
        val amz = VpnController.testAmz()
        val proton = VpnController.testProton()
        val se = VpnController.testSE()
        val x64 = VpnController.testExit64()
        Logger.i(LOG_IAB, "$TAG test ok?: warp: $warp, amz: $amz, proton: $proton, se: $se, w64: $x64")

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
        persistentState.useRpn = true
    }

    private fun setupClickListeners() {
        b.termsAndConditions.setOnClickListener {
            b.termsAndConditionsText.visibility =
                if (b.termsAndConditionsText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        b.paymentButton.setOnClickListener {
            purchaseStripe()
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
            // show the subscription details from database
        }

        b.paymentHistory.setOnClickListener {
            // show the payment history from database
        }

        b.troubleshoot.setOnClickListener {
            val intent = Intent(requireContext(), TroubleshootActivity::class.java)
            startActivity(intent)
        }

        b.contactSupport.setOnClickListener {
            io { isTestOk() }
        }

        b.pausePlus.setOnClickListener {
            if (persistentState.useRpn) {
                persistentState.useRpn = false
                val warp = WireguardManager.getConfigFilesById(WARP_ID)
                if (warp == null) {
                    Logger.e(LOG_IAB, "$TAG err getting warp config")
                    return@setOnClickListener
                }
                WireguardManager.disableConfig(warp)
            } else {
                persistentState.useRpn = true
            }
            b.pausePlus.text = if (persistentState.useRpn) "Pause Rethink+" else "Resume Rethink+"
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

    private fun isRethinkPlusSubscribed(): Boolean {
        // check whether the user has already subscribed to Rethink+ or not in database
        return persistentState.useRpn // for now
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
