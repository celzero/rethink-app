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
/*

import Logger
import Logger.LOG_IAB
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat.startActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.android.billingclient.api.BillingClient.ProductType
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter
import com.celzero.bravedns.adapter.GooglePlaySubsAdapter.SubscriptionChangeListener
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.iab.BillingListener
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.InAppBillingHandler.fetchPurchases
import com.celzero.bravedns.iab.InAppBillingHandler.purchaseSubs
import com.celzero.bravedns.iab.ProductDetail
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.rpnproxy.PipKeyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.subscription.SubscriptionStateMachineV2
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.activity.RpnAvailabilityCheckActivity
import com.celzero.bravedns.util.Constants.Companion.PKG_NAME_PLAY_STORE
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.underline
import com.celzero.bravedns.util.Utilities
import com.facebook.shimmer.Shimmer
import com.google.android.gms.common.GooglePlayServicesUtilLight.isGooglePlayServicesAvailable
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus), SubscriptionChangeListener, BillingListener {
    private val b by viewBinding(FragmentRethinkPlusBinding::bind)
    private var productId = ""
    private var planId = ""
    private var loadingDialog: AlertDialog? = null
    private var errorDialog: AlertDialog? = null
    private var msgDialog: AlertDialog? = null

    private var pollingJob: Job? = null
    private var pollingStartTime = 0L

    private var adapter: GooglePlaySubsAdapter? = null

    //private val subsProducts: MutableList<ProductDetail> = mutableListOf()

    companion object {
        private const val TAG = "R+Ui"
        private const val POLLING_INTERVAL_MS = 1500L // 1.5 seconds
        private const val POLLING_TIMEOUT_MS = 30000L // 60 seconds
        private const val ON_HOLD_PERIOD = 1 * 24 * 60 * 60 * 1000L
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // this should not happen, but just in case, as the caller fragment would have already
        // checked for the subscription status
        if (isRethinkPlusSubscribed()) {
            handlePlusSubscribed(RpnProxyManager.getRpnProductId())
            return
        }
        initView()
        initObservers()
        setupClickListeners()
    }

    override fun onSubscriptionSelected(productId: String, planId: String) {
        this.productId = productId
        this.planId = planId
        Logger.d(LOG_IAB, "Selected product: $productId, $planId")
    }

    private fun initView() {
        // show a loading dialog
        showLoadingDialog()

        io {
            val playAvailable = isGooglePlayServicesAvailable()
            if (!playAvailable) {
                uiCtx { showRethinkNotAvailableUi(requireContext().getString(R.string.play_service_not_available)) }
                return@io
            }

            val works = isRethinkPlusAvailable()

            if (!works.first) {
                uiCtx { showRethinkNotAvailableUi(works.second) }
                return@io
            }

            // initiate the product details query
            queryProductDetail()
        }
    }

    private fun isRethinkPlusSubscribed(): Boolean {
        // check whether the rethink+ is subscribed or not
        return InAppBillingHandler.hasValidSubscription()
    }

    */
/*private fun setBanner() {
        b.shimmerViewBanner.setShimmer(Shimmer.AlphaHighlightBuilder()
            .setDuration(2000)
            .setBaseAlpha(0.85f)
            .setDropoff(1f)
            .setHighlightAlpha(0.35f)
            .build())
        b.shimmerViewBanner.startShimmer()
        // Initialize adapter
        val myPagerAdapter = MyPagerAdapter()

        // Set up ViewPager
        b.viewPager.adapter = myPagerAdapter
        b.viewPager.addOnPageChangeListener(
            object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {}

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                }

                override fun onPageSelected(position: Int) {
                    addBottomDots(position)
                }
            }
        )
    }

    inner class MyPagerAdapter : PagerAdapter() {
        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view == `object`
        }

        override fun getCount(): Int {
            return layouts.count()
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val imageView = ImageView(requireContext()).apply {
                setImageResource(layouts[position])
                scaleType =
                    ImageView.ScaleType.CENTER_CROP  // or FIT_CENTER, CENTER_INSIDE depending on your need
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            container.addView(imageView)
            return imageView
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }
    }*//*


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

    private fun initiateBillingIfNeeded() {
        if (isBillingAvailable()) {
            Logger.i(LOG_IAB, "ensureBillingSetup: billing client already setup")
            return
        }

        InAppBillingHandler.initiate(requireContext().applicationContext, this)
        Logger.i(LOG_IAB, "ensureBillingSetup: billing client initiated")
    }

    private suspend fun queryProductDetail() {
        initiateBillingIfNeeded()
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
            showNotAvailableUi()
            return
        }
        if (!InAppBillingHandler.canMakePurchase()) {
            Logger.e(LOG_IAB, "purchaseSubs: cannot make purchase")
            Utilities.showToastUiCentered(
                requireContext(),
                "Cannot make purchase, please try again later",
                Toast.LENGTH_LONG
            )
            showNotAvailableUi()
            return
        }
        // initiate the payment flow
        io { InAppBillingHandler.purchaseSubs(requireActivity(), productId, planId) }
        Logger.v(LOG_IAB, "purchaseSubs: initiated for $productId, $planId")
    }

    */
/*private lateinit var dots: Array<TextView?>
    private val layouts: IntArray = intArrayOf(
        R.drawable.rethink_plus_home_banner,
        R.drawable.rethink_plus_banner_anti_censorship,
        R.drawable.rethink_plus_hide_ip_banner
    )

    private fun addBottomDots(currentPage: Int) {
        dots = arrayOfNulls(layouts.size)

        val colorActive = fetchColor(requireContext(), R.attr.primaryColor)
        val colorInActive = fetchColor(requireContext(), R.attr.primaryDarkColor)

        b.layoutDots.removeAllViews()

        for (i in dots.indices) {
            dots[i] = TextView(requireContext())
            dots[i]?.text = updateHtmlEncodedText("&#8226;")
            dots[i]?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30F)
            dots[i]?.setTextColor(colorInActive)
            b.layoutDots.addView(dots[i])
        }

        if (dots.isNotEmpty()) {
            dots[currentPage]?.setTextColor(colorActive)
        }
    }*//*


    private fun showLoadingDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        // show progress dialog
        builder.setTitle(requireContext().getString(R.string.loading_dialog_title))
        builder.setMessage(requireContext().getString(R.string.rethink_plus_loading_dialog_desc))
        builder.setCancelable(true)
        loadingDialog = builder.create()
        loadingDialog?.show()
        loadingDialog?.setOnCancelListener {
            Logger.v(LOG_IAB, "loading dialog cancelled")
            // if the user cancels the dialog, stop the pending purchase polling
            stopPendingPurchasePolling()
            // navigate to home screen
            //navigateToHomeScreen()
        }
    }

    private fun hideLoadingDialog() {
        Logger.v(LOG_IAB, "hide loading dialog")
        if (isAdded && loadingDialog?.isShowing == true) {
            loadingDialog?.dismiss()
        }
        Logger.v(LOG_IAB, "loading dialog dismissed")
    }

    private suspend fun isRethinkPlusAvailable(): Pair<Boolean, String> {
        // TODO: added for testing, remove later
        // check whether the rethink+ is available for the user or not
        val res = PipKeyManager.isRethinkPlusActive(requireContext())
        // added as default text, maybe pass relevant message from the server/calling function
        // the message part is used only when the result is false
        Logger.i(LOG_IAB, "isRethinkPlusAvailable? $res")
        return res
    }

    private fun showPendingPurchaseUi() {
        if (!isAdded) return
        hideLoadingDialog()
        hidePaymentContainerUi()
        hideNotAvailableUi()
        hideErrorDialog()
        hideMsgDialog()

        b.topBanner.visibility = View.VISIBLE
        b.shimmerViewContainer.visibility = View.GONE
        b.pendingPurchaseLayout.visibility = View.VISIBLE
    }

    private fun showPaymentContainerUi() {
        Logger.v(LOG_IAB, "showPaymentContainerUi: showing payment container UI")
        initTermsAndPolicy()
        hideLoadingDialog()
        hideErrorDialog()
        hideMsgDialog()
        hideNotAvailableUi()

        b.topBanner.visibility = View.VISIBLE
        b.shimmerViewContainer.visibility = View.VISIBLE
        b.paymentContainer.visibility = View.VISIBLE
        b.paymentButtonContainer.visibility = View.VISIBLE
        b.testPingButton.underline()
        setAdapter(emptyList())
        Logger.i(LOG_IAB, "adapter set")
    }

    private fun setAdapterData(subsProduct: List<ProductDetail>) {
        hideLoadingDialog()
        hideErrorDialog()
        hideMsgDialog()
        if (b.paymentContainer.visibility != View.VISIBLE) {
            showPaymentContainerUi()
        }
        if (adapter == null) {
            Logger.d(LOG_IAB, "Adapter is null, initializing it")
            setAdapter(subsProduct)
        } else {
            Logger.d(LOG_IAB, "Adapter is not null, updating data")
            adapter?.setData(subsProduct)
        }
    }

    private fun hidePaymentContainerUi() {
        if (!isAdded) return

        b.paymentContainer.visibility = View.GONE
        b.paymentButtonContainer.visibility = View.GONE
        b.shimmerViewContainer.visibility = View.GONE
    }

    private fun showNotAvailableUi() {
        hideLoadingDialog()
        hidePaymentContainerUi()
        b.topBanner.visibility = View.GONE

        b.notAvailableLayout.visibility = View.VISIBLE
    }

    private fun hideNotAvailableUi() {
        b.notAvailableLayout.visibility = View.GONE
    }

    private fun showRethinkNotAvailableUi(msg: String) {
        showNotAvailableUi()
        hideLoadingDialog()
        hideMsgDialog()
        hideErrorDialog()
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_transaction_error, null)

        dialogView.findViewById<AppCompatTextView>(R.id.dialog_title).text = requireContext().getString(R.string.rpn_availablity)
        dialogView.findViewById<AppCompatTextView>(R.id.dialog_message).text = msg

        msgDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<AppCompatButton>(R.id.button_ok).apply {
            text = requireContext().getString(R.string.dns_info_positive)
            setOnClickListener {
                msgDialog?.dismiss()
            }
        }
        msgDialog?.show()
    }

    private fun setAdapter(productDetails: List<ProductDetail>) {
        // set the adapter for the recycler view
        Logger.i(LOG_IAB, "setting adapter for the recycler view: ${productDetails.size}")
        b.subscriptionPlans.setHasFixedSize(true)
        val layoutManager = LinearLayoutManager(requireContext())
        b.subscriptionPlans.layoutManager = layoutManager
        adapter = GooglePlaySubsAdapter(this, requireContext(), productDetails)
        b.subscriptionPlans.adapter = adapter
    }

    fun startPendingPurchasePolling(scope: CoroutineScope) {
        if (pollingJob != null) return

        pollingStartTime = System.currentTimeMillis()
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val elapsedTime = System.currentTimeMillis() - pollingStartTime
                if (elapsedTime > POLLING_TIMEOUT_MS) {
                    Logger.i(LOG_IAB, "Polling timeout reached, stopping pending purchase polling, elapsed: $elapsedTime ms")
                    stopPendingPurchasePolling()
                    //navigateToHomeScreen()
                    break
                }

                Logger.d(LOG_IAB, "Polling pending purchase status, elapsed: $elapsedTime ms")
                fetchPurchases(listOf(ProductType.SUBS))
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    fun stopPendingPurchasePolling() {
        pollingJob?.cancel()
        pollingJob = null
        Logger.i(LOG_IAB, "Pending purchase polling stopped")
    }

    private fun hidePendingPurchaseUi() {
        if (!isAdded) return

        b.pendingPurchaseLayout.visibility = View.GONE
        b.topBanner.visibility = View.GONE
        b.shimmerViewContainer.visibility = View.GONE
    }

    private fun navigateToHomeScreen() {
        ui {
            if (!isAdded) return@ui
            try {
                val btmNavView = activity?.findViewById<BottomNavigationView>(R.id.nav_view)
                val homeId = R.id.homeScreenFragment
                btmNavView?.selectedItemId = homeId
                findNavController().navigate(R.id.action_switch_to_homeScreenFragment)
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "Navigation failed: ${e.message}")
            }
        }
    }

    private fun initObservers() {
        */
/*io {
            Result.getResultStateFlow().collect { i ->
                Logger.d(LOG_IAB, "res state: ${i.name}, ${i.message};p? ${i.priority}")
                if (i.priority == InAppBillingHandler.Priority.HIGH) {
                    ui {
                        Logger.e(LOG_IAB, "res failure: ${i.name}, ${i.message}; p? ${i.priority}")

                        if (isAdded && isVisible) {
                            hideLoadingDialog()
                            showErrorDialog(requireContext().getString(R.string.settings_gologger_dialog_option_5), i.message)
                        }
                    }
                }
            }
        }*//*


        InAppBillingHandler.connectionResultLiveData.distinctUntilChanged().observe(viewLifecycleOwner) { i ->
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
                        showNotAvailableUi()
                    }
                }
                return@observe
            }
            // check for the subscription status after the connection is established
            val productType = listOf(ProductType.SUBS)
            io {
                fetchPurchases(productType)
            }
        }

        io {
            RpnProxyManager.collectSubscriptionState().collect { state ->
                Logger.d(LOG_IAB, "Subscription state changed: ${state.name}")
                // Handle state changes if needed
                handleStateChange(state)
            }
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
                            requireContext().getString(R.string.product_details_error),
                            Toast.LENGTH_SHORT
                        )
                        showNotAvailableUi()
                        showRethinkNotAvailableUi(
                            requireContext().getString(R.string.product_details_error)
                        )
                        return@ui
                    }
                }
                return@observe
            }
            val subsProducts = mutableListOf<ProductDetail>()
            subsProducts.addAll(list.filter { it.productType == ProductType.SUBS })
            // set the first product as the default selected product
            val first = subsProducts.first()
            productId = first.productId
            planId = first.planId

            val currState = RpnProxyManager.getSubscriptionState()
            if (!currState.canMakePurchase) {
                // if the user has a valid subscription, handle it
                Logger.i(LOG_IAB, "canMakePurchase is false, no purchase allowed, current state: ${currState.name}")
                return@observe
            }

            if (subsProducts.isEmpty()) {
                Logger.e(LOG_IAB, "subscription product details is empty")
                ui {
                    if (isAdded && isVisible) {
                        hideLoadingDialog()
                        Utilities.showToastUiCentered(
                            requireContext(),
                            requireContext().getString(R.string.product_details_error),
                            Toast.LENGTH_SHORT
                        )
                        showNotAvailableUi()
                        showRethinkNotAvailableUi(
                            requireContext().getString(R.string.product_details_error)
                        )
                        return@ui
                    }
                }
                return@observe
            }
            if (isAdded && isVisible) {
                setAdapterData(subsProducts)
                Logger.i(LOG_IAB, "product details fetched, size: ${subsProducts.size}")
            }
        }

        */
/*InAppBillingHandler.transactionErrorLiveData.observe(viewLifecycleOwner) { billingResult ->
            if (isAdded && isVisible) {
                hideLoadingDialog()
                val error = getTransactionError(billingResult)
                showErrorDialog(error.title, error.message)
            }
        }*//*

    }

    private fun handleStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        Logger.d(LOG_IAB, "$TAG handleStateChange: ${state.name}")
        when (state) {
            SubscriptionStateMachineV2.SubscriptionState.PurchasePending -> {
                // show the pending purchase UI
                ui { showPendingPurchaseUi() }
                startPendingPurchasePolling(this.lifecycleScope)
            }
            SubscriptionStateMachineV2.SubscriptionState.Active -> {
                // handle the active state
                // hide the loading dialog and pending purchase UI
                ui {
                    if (!isAdded) return@ui
                    hideLoadingDialog()
                    hidePendingPurchaseUi()
                    hidePaymentContainerUi()
                    // navigate to the rethink+ dashboard
                    handlePlusSubscribed(RpnProxyManager.getRpnProductId())
                }

            }
            SubscriptionStateMachineV2.SubscriptionState.Error -> {
                // handle the error state
                ui { showErrorDialog("Subscription Error", "An error occurred while processing your subscription.") }
            }
            SubscriptionStateMachineV2.SubscriptionState.Initial -> {
                // do nothing for initial state, as it is handled when product details are fetched
            }
            SubscriptionStateMachineV2.SubscriptionState.Cancelled,
            SubscriptionStateMachineV2.SubscriptionState.Revoked,
            SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                // show the products  UI  with the option to resubscribe
                // edit the button text to "Resubscribe"
                ui {
                    if (!isAdded) return@ui
                    hideLoadingDialog()
                    hidePendingPurchaseUi()
                    showPaymentContainerUi()

                    val data = RpnProxyManager.getSubscriptionData()
                    if (data == null) {
                        Logger.e(LOG_IAB, "Subscription data is null, cannot show resubscribe UI")
                        b.paymentButton.text = getString(R.string.subscribe_title)
                        return@ui
                    }
                    val billingExpiry = data.purchaseDetail?.expiryTime ?: 0L
                    // if expiry time is greater than 60 days do not show the resubscribe option
                    Logger.v(LOG_IAB, "billingExpiry: $billingExpiry, current time: ${System.currentTimeMillis()}, on-hold period: $ON_HOLD_PERIOD, debug: $DEBUG, resubscribe? ${billingExpiry > 0L && (System.currentTimeMillis() - billingExpiry < ON_HOLD_PERIOD)}")
                    if (billingExpiry <= 0L || (System.currentTimeMillis() - billingExpiry < ON_HOLD_PERIOD || DEBUG)) {
                        // if the subscription is cancelled or revoked, show the resubscribe option
                        b.paymentButton.text = getString(R.string.resubscribe_title)
                    } else {
                        // subscription is expired and not in the on-hold period, show the subscribe option
                        b.paymentButton.text = getString(R.string.subscribe_title)
                    }
                }
            }
            else -> {
                // do nothing for other states
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        if (!isAdded) return

        // hide all the existing dialogs
        hideLoadingDialog()
        hideMsgDialog()
        hideErrorDialog()

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_transaction_error, null)

        dialogView.findViewById<AppCompatTextView>(R.id.dialog_title).text = title
        dialogView.findViewById<AppCompatTextView>(R.id.dialog_message).text = message

        errorDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<AppCompatButton>(R.id.button_ok).apply {
            setOnClickListener {
                if (!isAdded || !isVisible) {
                    errorDialog?.dismiss()
                    return@setOnClickListener
                }
                //navigateToHomeScreen()
                errorDialog?.dismiss()
            }
        }
        errorDialog?.setOnCancelListener {
            errorDialog?.dismiss()
        }
        errorDialog?.show()
    }

    private fun hideErrorDialog() {
        if (isAdded && errorDialog?.isShowing == true) {
            errorDialog?.dismiss()
        }
    }

    private fun hideMsgDialog() {
        if (isAdded && msgDialog?.isShowing == true) {
            msgDialog?.dismiss()
        }
    }

    private fun handlePlusSubscribed(productId: String) {
        if (!isAdded) return
        // finish this fragment and navigate to the rethink+ dashboard
        hideLoadingDialog()
        // close any error/message dialog if it is showing
        hideErrorDialog()
        hideMsgDialog()
        Logger.i(LOG_IAB, "R+ subscribed, productId: $productId, navigating to dashboard")
        if (!isAdded) {
            Logger.w(LOG_IAB, "Fragment not added, cannot navigate")
            return
        }
        try {
            findNavController().navigate(R.id.rethinkPlusDashboardFragment)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "Navigation failed: ${e.message}")
            launchRethinkPlusDashboardInFragmentHost()
        }
    }

    private fun launchRethinkPlusDashboardInFragmentHost() {
        // Prepare arguments if needed
        val args = Bundle().apply { putString("ARG_KEY", "Launch_Manage_Subscriptions") }

        // Create intent using the helper
        val intent = FragmentHostActivity.createIntent(
            context = requireContext(),
            fragmentClass = RethinkPlusDashboardFragment::class.java,
            args = args // or null if none
        )

        // Start the activity
        startActivity(intent)
    }

    private fun setupClickListeners() {

        b.paymentButton.setOnClickListener { purchaseSubs() }

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
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        // applicationInfo.enabled - When false, indicates that all components within
        // this application are considered disabled, regardless of their individually set enabled
        // status.
        // TODO: prompt dialog to user that Play service is disabled, so switch to update
        // check for website
        return Utilities.getApplicationInfo(requireContext(), PKG_NAME_PLAY_STORE)?.enabled == true
    }

    override fun onDetach() {
        super.onDetach()
        // cancel any pending purchase polling job
        stopPendingPurchasePolling()
        Logger.v(LOG_IAB, "onDetach: pending purchase polling job cancelled")
        // hide any dialogs
        hideLoadingDialog()
        hideErrorDialog()
        hideMsgDialog()
        Logger.v(LOG_IAB, "onDetach: dialogs hidden")
        // reset the productId and planId
        productId = ""
        planId = ""
        Logger.v(LOG_IAB, "onDetach: productId and planId reset")
        // reset the polling start time
        pollingStartTime = 0L
        Logger.v(LOG_IAB, "onDetach: polling start time reset")
        // reset the polling job
        pollingJob = null
        Logger.v(LOG_IAB, "onDetach: polling job reset")
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(f: () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(SupervisorJob() + Dispatchers.IO) { f() }
    }

    override fun onConnectionResult(isSuccess: Boolean, message: String) {
        if (!isSuccess) {
            Logger.e(LOG_IAB, "Billing connection failed: $message")
            ui {
                if (isAdded && isVisible) {
                    hideLoadingDialog()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        message,
                        Toast.LENGTH_SHORT
                    )
                    showNotAvailableUi()
                }
            }
            return
        }
    }

    override fun purchasesResult(
        isSuccess: Boolean,
        purchaseDetailList: List<PurchaseDetail>
    ) {
        if (!isSuccess) {
            Logger.e(LOG_IAB, "purchasesResult: failed to fetch purchases")
            return
        }
    }

    override fun productResult(
        isSuccess: Boolean,
        productList: List<ProductDetail>
    ) {
        if (!isSuccess) {
            Logger.e(LOG_IAB, "productResult: failed to fetch product details")
            ui {
                if (isAdded && isVisible) {
                    hideLoadingDialog()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        requireContext().getString(R.string.product_details_error),
                        Toast.LENGTH_SHORT
                    )
                    showNotAvailableUi()
                    showRethinkNotAvailableUi(
                        requireContext().getString(R.string.product_details_error)
                    )
                    return@ui
                }
            }
            return
        }
    }
}
*/
