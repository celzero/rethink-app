/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import Logger
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityCheckoutProxyBinding
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.togb
import com.celzero.bravedns.util.Utilities.togs
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID

class CheckoutActivity : AppCompatActivity(R.layout.activity_checkout_proxy) {
    private val b by viewBinding(ActivityCheckoutProxyBinding::bind)
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TOKEN_LENGTH = 32
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        init()
        setupClickListeners()

        /*paymentSheet = PaymentSheet(this, ::onPaymentSheetResult)
        "Your backend endpoint/payment-sheet".httpPost().responseJson { _, _, result ->
            if (result is Result.Success) {
                val responseJson = result.get().obj()
                paymentIntentClientSecret = responseJson.getString("paymentIntent")
                customerConfig =
                    PaymentSheet.CustomerConfiguration(
                        responseJson.getString("customer"),
                        responseJson.getString("ephemeralKey")
                    )
                val publishableKey = responseJson.getString("publishableKey")
                PaymentConfiguration.init(this, publishableKey)
            }
        }*/
    }

    private fun init() {
        handlePaymentStatusUi()

        // create and handle keys if not paid
        if (TcpProxyHelper.getTcpProxyPaymentStatus().isNotPaid()) {
            handleKeys()
        }

        UUID.randomUUID().toString().let { uuid -> Logger.d(Logger.LOG_TAG_PROXY, "UUID: $uuid") }
        generateRandomHexToken(TOKEN_LENGTH).let { token ->
            Logger.d(Logger.LOG_TAG_PROXY, "Token: $token")
        }
        setSpannablePricing(b.plan1MonthButton, "1 Month / 1.99", "( 1.99 / month )")
        setSpannablePricing(b.plan3MonthsButton, "3 Months / 3.99", "( 1.33 / month )")
        setSpannablePricing(b.plan6MonthsButton, "6 Months / 5.99", " ( 0.99 / month )")
    }

    private fun handlePaymentStatusUi() {
        val paymentStatus: TcpProxyHelper.PaymentStatus = TcpProxyHelper.getTcpProxyPaymentStatus()
        Logger.d(Logger.LOG_TAG_PROXY, "Payment Status: $paymentStatus")
        when (paymentStatus) {
            TcpProxyHelper.PaymentStatus.INITIATED -> {
                b.paymentAwaitingContainer.visibility = View.VISIBLE
                b.paymentContainer.visibility = View.GONE
                b.paymentSuccessContainer.visibility = View.GONE
                b.paymentFailedContainer.visibility = View.GONE
            }
            TcpProxyHelper.PaymentStatus.NOT_PAID -> {
                b.paymentAwaitingContainer.visibility = View.GONE
                b.paymentContainer.visibility = View.VISIBLE
                b.paymentSuccessContainer.visibility = View.GONE
                b.paymentFailedContainer.visibility = View.GONE
            }
            TcpProxyHelper.PaymentStatus.PAID -> {
                b.paymentAwaitingContainer.visibility = View.GONE
                b.paymentContainer.visibility = View.GONE
                b.paymentSuccessContainer.visibility = View.VISIBLE
                b.paymentFailedContainer.visibility = View.GONE
            }
            TcpProxyHelper.PaymentStatus.FAILED -> {
                b.paymentAwaitingContainer.visibility = View.GONE
                b.paymentContainer.visibility = View.GONE
                b.paymentSuccessContainer.visibility = View.GONE
                b.paymentFailedContainer.visibility = View.VISIBLE
            }
            else -> {
                b.paymentAwaitingContainer.visibility = View.GONE
                b.paymentContainer.visibility = View.VISIBLE
                b.paymentSuccessContainer.visibility = View.GONE
                b.paymentFailedContainer.visibility = View.GONE
            }
        }
    }

    private fun observePaymentStatus() {
        val workManager = WorkManager.getInstance(this.applicationContext)

        // observer for custom download manager worker
        workManager.getWorkInfosByTagLiveData(TcpProxyHelper.PAYMENT_WORKER_TAG).observe(this) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                Logger.LOG_TAG_PROXY,
                "WorkManager state: ${workInfo.state} for ${TcpProxyHelper.PAYMENT_WORKER_TAG}"
            )
            if (
                WorkInfo.State.ENQUEUED == workInfo.state ||
                    WorkInfo.State.RUNNING == workInfo.state
            ) {
                handlePaymentStatusUi()
            } else if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                handlePaymentStatusUi()
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                    WorkInfo.State.FAILED == workInfo.state
            ) {
                handlePaymentStatusUi()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(TcpProxyHelper.PAYMENT_WORKER_TAG)
            } else { // state == blocked
                // no-op
            }
        }
    }

    private fun handleKeys() {
        io {
            try {
                val key = TcpProxyHelper.getPublicKey()
                Logger.d(Logger.LOG_TAG_PROXY, "Public Key: $key")
                // if there is a key state, the msgOrExistingState (keyState.msg/keyState.v()) should not be empty
                val keyGenerator = Backend.newPipKeyProvider(key.togb(), "".togs())
                val keyState = keyGenerator.blind()
                // id: use 64 chars as account id
                val id = keyState.msg.opaque()?.s ?: ""
                val accountId = id.substring(0, 64)
                // rest of the keyState values will never be used in kotlin

                // keyState.v() should be retrieved from the file system
                Backend.newPipKeyStateFrom(keyState.v()) // retrieve the key state alone

                Logger.d(Logger.LOG_TAG_PROXY, "Blind: $keyState")
                val path =
                    File(
                        this.filesDir.canonicalPath +
                            File.separator +
                            TcpProxyHelper.TCP_FOLDER_NAME +
                            File.separator +
                            TcpProxyHelper.PIP_KEY_FILE_NAME
                    )
                EncryptedFileManager.writeTcpConfig(this, keyState.v().tos() ?: "", TcpProxyHelper.PIP_KEY_FILE_NAME)
                val content = EncryptedFileManager.read(this, path)
                Logger.d(Logger.LOG_TAG_PROXY, "Content: $content")
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_PROXY, "err in handleKeys: ${e.message}", e)
            }
        }
    }

    private fun setupClickListeners() {

        b.paymentSuccessButton.setOnClickListener {
            val intent = Intent(this, TcpProxyMainActivity::class.java)
            startActivity(intent)
            finish()
        }

        b.restoreButton.setOnClickListener {
            val intent = Intent(this, TcpProxyMainActivity::class.java)
            startActivity(intent)
            finish()
        }

        b.paymentFailedButton.setOnClickListener {
            val intent = Intent(this, TcpProxyMainActivity::class.java)
            startActivity(intent)
            finish()
        }

        b.paymentButton.setOnClickListener {
            TcpProxyHelper.initiatePaymentVerification(this)
            observePaymentStatus()
        }
    }

    /*fun presentPaymentSheet() {
        paymentSheet.presentWithPaymentIntent(
            paymentIntentClientSecret,
            PaymentSheet.Configuration(
                merchantDisplayName = "My merchant name",
                customer = customerConfig,
                // Set `allowsDelayedPaymentMethods` to true if your business
                // can handle payment methods that complete payment after a delay, like SEPA Debit
                // and Sofort.
                allowsDelayedPaymentMethods = true
            )
        )
    }

    private fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) {
        when (paymentSheetResult) {
            is PaymentSheetResult.Canceled -> {
                print("Canceled")
            }
            is PaymentSheetResult.Failed -> {
                print("Error: ${paymentSheetResult.error}")
            }
            is PaymentSheetResult.Completed -> {
                // Display for example, an order confirmation screen
                print("Completed")
            }
        }
    }*/

    private fun setSpannablePricing(btn: RadioButton, planType: String, planPrice: String) {

        val spannableStringBuilder = SpannableStringBuilder()

        // Set the plan type with a different color
        val color = fetchColor(this, R.attr.accentGood)
        val planTypeColorSpan = ForegroundColorSpan(color)
        spannableStringBuilder.append(planType)
        spannableStringBuilder.setSpan(
            planTypeColorSpan,
            0,
            planType.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Add a space between plan type and plan price
        spannableStringBuilder.append(" ")

        // Set the plan price with a different color
        val priceColor = fetchColor(this, R.attr.primaryLightColorText)
        val planPriceColorSpan = ForegroundColorSpan(priceColor)
        spannableStringBuilder.append(planPrice)
        spannableStringBuilder.setSpan(
            planPriceColorSpan,
            planType.length + 1,
            planType.length + planPrice.length + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Set the SpannableString to a TextView
        btn.text = spannableStringBuilder
    }

    // https://stackoverflow.com/a/44227131
    private fun generateRandomHexToken(length: Int): String? {
        val secureRandom = SecureRandom()
        val token = ByteArray(length)
        secureRandom.nextBytes(token)
        return BigInteger(1, token).toString(16) // Hexadecimal encoding
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
