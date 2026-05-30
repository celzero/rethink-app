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
package com.celzero.bravedns.ui.activity

import Logger
import Logger.LOG_TAG_UI
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusDao
import com.celzero.bravedns.databinding.ActivityCustomerSupportBinding
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CustomerSupportActivity: lets users submit a support request via email.
 */
class CustomerSupportActivity : BaseActivity(R.layout.activity_customer_support) {

    private val b by viewBinding(ActivityCustomerSupportBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val subscriptionStatusDao by inject<SubscriptionStatusDao>()
    private val subscriptionStateHistoryDao by inject<SubscriptionStateHistoryDao>()

    companion object {
        private const val TAG = "CustomerSupportAct"
        private const val MAX_HISTORY_ENTRIES = 50
        private const val DIAG_FILE_NAME = "rpn_support_diag.txt"
        private const val SUPPORT_ZIP_FILE_NAME = "rpn_support_diagnostics.zip"
        private const val WIRELOG_ATTACH_LIMIT_BYTES = 1 * 1024 * 1024L  // 1 MB
        private const val OTHER_ATTACH_THRESHOLD_BYTES = 1 * 1024 * 1024L // cap wirelog at 1 MB if other attachments exceed this

        fun start(context: Context) {
            context.startActivity(Intent(context, CustomerSupportActivity::class.java))
        }
    }

    private fun Context.isDarkThemeOn() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        setupToolbar()
        loadSubscriptionSummary()
        setupSendButton()
        applyScrollPadding()
    }

    private fun applyScrollPadding() {
        b.nestedScroll.post {
            b.nestedScroll.setPadding(
                b.nestedScroll.paddingLeft,
                0,
                b.nestedScroll.paddingRight,
                b.nestedScroll.paddingBottom
            )
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(b.toolbar)
        b.collapsingToolbar.title = getString(R.string.contact_support_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    /** Called after subscription data is loaded to fill the hero subtitle. */
    private fun updateHeroSubtitle(sub: SubscriptionStatus?, deviceId: String) {
        if (sub == null || sub.purchaseToken.isEmpty()) return

        // Purchase token (show first 12 chars)
        var token = sub.purchaseToken
        token = token.length.let { if (it > 12) token.take(12) else token.ifBlank { "" } }
        val accountId = sub.accountId.take(12).ifBlank { return }
        val deviceId = deviceId.take(4).ifBlank { return }
        val id = "$accountId • $deviceId"
        b.tvHeroSubtitle.text = if (token.isNotEmpty()) "$token \u00B7 $id" else id
    }

    /**
     * Loads the current subscription from the DB and populates the summary card.
     * Runs on IO, posts result to Main.
     */
    private fun loadSubscriptionSummary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sub = try {
                subscriptionStatusDao.getCurrentSubscription()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG loadSubscriptionSummary error: ${e.message}", e)
                null
            }
            val deviceId = InAppBillingHandler.getObfuscatedDeviceId()
            withContext(Dispatchers.Main) {
                updateHeroSubtitle(sub, deviceId)
            }
        }
    }

    private fun setupSendButton() {
        b.btnSendEmail.setOnClickListener {
            hideKeyboard()
            val description = b.etDescription.text?.toString()?.trim() ?: ""
            val category = selectedCategory()

            if (description.isEmpty() && category == null) {
                b.tilDescription.error = getString(R.string.support_error_empty_description)
                return@setOnClickListener
            }
            b.tilDescription.error = null
            collectAndSend(description, category)
        }
    }

    private fun selectedCategory(): String? {
        return when {
            b.chipPayment.isChecked -> getString(R.string.support_category_payment)
            b.chipActivation.isChecked -> getString(R.string.support_category_activation)
            b.chipConnectivity.isChecked -> getString(R.string.support_category_connectivity)
            b.chipRefund.isChecked -> getString(R.string.support_category_refund)
            b.chipOther.isChecked -> getString(R.string.category_name_others)
            else -> null
        }
    }

    /**
     * Gathers all requested data on IO, writes the diagnostic file, then launches
     * the email chooser on Main.  Shows a progress bar while working.
     */
    private fun collectAndSend(description: String, category: String?) {
        setLoading(true)
        val includeStatus = b.switchAttachStatus.isChecked
        val includeHistory = b.switchAttachHistory.isChecked
        val includeStats = b.switchAttachStats.isChecked

        lifecycleScope.launch(Dispatchers.IO) {
            try {

                val allStatuses: List<SubscriptionStatus> = if (includeStatus) {
                    runCatching { subscriptionStatusDao.getAllSubscriptions() }.getOrElse { emptyList() }
                } else emptyList()

                val recentHistory = if (includeHistory) {
                    runCatching {
                        subscriptionStateHistoryDao.getRecentHistory(MAX_HISTORY_ENTRIES)
                    }.getOrElse { emptyList() }
                } else emptyList()

                val rpnStats = if (includeStats) {
                    runCatching { RpnProxyManager.stats() }.getOrElse { "unavailable" }
                } else ""

                val diagContent = buildDiagnosticText(
                    description  = description,
                    category     = category,
                    statuses     = allStatuses,
                    history      = recentHistory,
                    rpnStats     = rpnStats
                )

                val diagFile = writeDiagFile(diagContent)

                val bugZip = EnhancedBugReport.getTombstoneZipFile(this@CustomerSupportActivity)
                val wirelogBytes = prepareWirelogAttachment(diagFile?.length() ?: 0L)
                val supportZip = buildSupportZip(diagFile, wirelogBytes, bugZip)

                val emailBody = buildEmailBody(description, category)

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    launchEmailIntent(emailBody, supportZip, category)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG collectAndSend error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(
                        this@CustomerSupportActivity,
                        getString(R.string.support_error_collect),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun buildDiagnosticText(
        description: String,
        category: String?,
        statuses: List<SubscriptionStatus>,
        history: List<com.celzero.bravedns.database.SubscriptionStateHistory>,
        rpnStats: String
    ): String {
        // Use plain ASCII separators only: Unicode box-drawing characters (===, ---, arrows)
        // get mangled by email clients and devices that mis-detect encoding.
        val heavy = "===========================================================\n"
        val light = "-----------------------------------------------------------\n"
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
        val versionName = appVersionName()

        sb.append(heavy)
        sb.append("  RETHINK PLUS SUPPORT DIAGNOSTIC REPORT\n")
        sb.append(heavy)
        sb.append("Generated   : $now\n")
        sb.append("App version : $versionName\n")
        sb.append("Device      : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Android     : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Package     : $packageName\n")
        sb.append("\n")

        sb.append(light)
        sb.append("  USER DESCRIPTION\n")
        sb.append(light)
        if (category != null) sb.append("Category : $category\n")
        sb.append(description.ifEmpty { "(no description provided)" })
        sb.append("\n\n")

        sb.append(light)
        sb.append("  SUBSCRIPTION STATUS (${statuses.size} row(s))\n")
        sb.append(light)
        if (statuses.isEmpty()) {
            sb.append("  No subscription records found.\n")
        } else {
            statuses.forEachIndexed { idx, s ->
                sb.append("\n  -- Row ${idx + 1} ----------------------------------\n")
                sb.append("  id               : ${s.id}\n")
                sb.append("  status           : ${SubscriptionStatus.SubscriptionState.fromId(s.status).name} (${s.status})\n")
                sb.append("  productId        : ${s.productId}\n")
                sb.append("  planId           : ${s.planId}\n")
                sb.append("  productTitle     : ${s.productTitle}\n")
                sb.append("  purchaseToken    : ${s.purchaseToken}\n")
                sb.append("  orderId          : ${s.orderId.ifEmpty { "N/A" }}\n")
                // accountId: max 12 chars visible, rest redacted
                sb.append("  accountId        : ${redactId(s.accountId)}\n")
                sb.append("  purchaseTime     : ${formatTs(s.purchaseTime)}\n")
                sb.append("  billingExpiry    : ${formatTs(s.billingExpiry)}\n")
                sb.append("  accountExpiry    : ${formatTs(s.accountExpiry)}\n")
                sb.append("  lastUpdatedTs    : ${formatTs(s.lastUpdatedTs)}\n")
                sb.append("  previousProductId: ${s.previousProductId.ifEmpty { "N/A" }}\n")
                sb.append("  previousToken    : ${s.previousPurchaseToken.ifEmpty { "N/A" }}\n")
                sb.append("  replacedAt       : ${if (s.replacedAt > 0) formatTs(s.replacedAt) else "N/A"}\n")
                // sessionToken truncated to 32 chars - it can be very long
                val tokenDisplay = if (s.sessionToken.length > 32)
                    s.sessionToken.take(32) + "..." else s.sessionToken
                sb.append("  sessionToken     : $tokenDisplay\n")
            }
        }
        sb.append("\n")

        sb.append(light)
        sb.append("  STATE HISTORY (${history.size} of last $MAX_HISTORY_ENTRIES)\n")
        sb.append(light)
        if (history.isEmpty()) {
            sb.append("  No history records found.\n")
        } else {
            history.forEach { h ->
                val from = SubscriptionStatus.SubscriptionState.fromId(h.fromState).name.removePrefix("STATE_")
                val to   = SubscriptionStatus.SubscriptionState.fromId(h.toState).name.removePrefix("STATE_")
                sb.append("  ${formatTs(h.timestamp)}  $from -> $to")
                if (!h.reason.isNullOrBlank()) sb.append("  [${h.reason}]")
                sb.append("\n")
            }
        }
        sb.append("\n")

        if (rpnStats.isNotEmpty()) {
            sb.append(light)
            sb.append("  RPN PROXY STATS\n")
            sb.append(light)
            sb.append(rpnStats)
            sb.append("\n")
        }

        sb.append(heavy)
        sb.append("  END OF REPORT\n")
        sb.append(heavy)

        return sb.toString()
    }

    /**
     * All subscription data, stats, and history are in the attached diagnostic file.
     */
    private fun buildEmailBody(description: String, category: String?): String {
        val sb = StringBuilder()

        sb.append("Hello Rethink Support,\n\n")

        if (category != null) {
            sb.append("Issue category: $category\n\n")
        }

        sb.append(
            description.ifEmpty { "(No description provided, please see the attached diagnostic file for details.)" }
        )

        sb.append("\n\n")
        sb.append("--- Device info ---\n")
        sb.append("App version : ${appVersionName()}\n")
        sb.append("Device      : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Android     : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("\n")
        sb.append("All subscription status, state history, and proxy statistics\n")
        sb.append("are included in the attached diagnostic file.\n\n")
        sb.append("--\nSent via Rethink Plus Support")

        return sb.toString()
    }

    private fun prepareWirelogAttachment(otherAttachmentsSize: Long): ByteArray? {
        val srcFile = File(filesDir, "${Logger.WIRELOG_FOLDER_NAME}/${Logger.WIRELOG_FILE_NAME}")
        if (!srcFile.exists() || srcFile.length() == 0L) return null
        return try {
            val maxBytes = if (otherAttachmentsSize > OTHER_ATTACH_THRESHOLD_BYTES) {
                WIRELOG_ATTACH_LIMIT_BYTES
            } else {
                Logger.WIRELOG_MAX_SIZE_BYTES
            }
            readTailBytes(srcFile, maxBytes)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG prepareWirelogAttachment error: ${e.message}", e)
            null
        }
    }

    private fun readTailBytes(file: File, maxBytes: Long): ByteArray {
        val size = file.length()
        val start = maxOf(0L, size - maxBytes)
        return java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val toRead = (size - start).toInt()
            val buf = ByteArray(toRead)
            raf.readFully(buf)
            buf
        }
    }

    private fun buildSupportZip(diagFile: File?, wirelogBytes: ByteArray?, bugZip: File?): File? {
        if (diagFile == null && wirelogBytes == null && bugZip == null) return null
        return try {
            val outFile = File(File(filesDir, "support").also { it.mkdirs() }, SUPPORT_ZIP_FILE_NAME)
            java.util.zip.ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                diagFile?.takeIf { it.exists() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry("diagnostic_report.txt"))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
                wirelogBytes?.takeIf { it.isNotEmpty() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry("wirelogs.txt"))
                    zos.write(it)
                    zos.closeEntry()
                }
                bugZip?.takeIf { it.exists() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry("bugreport.zip"))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            outFile
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG buildSupportZip error: ${e.message}", e)
            null
        }
    }

    private fun writeDiagFile(content: String): File? {
        return try {
            val dir = File(filesDir, "support").also { it.mkdirs() }
            val file = File(dir, DIAG_FILE_NAME)
            file.writeText(content, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG writeDiagFile error: ${e.message}", e)
            null
        }
    }

    private fun getFileUri(file: File?): Uri? {
        file ?: return null
        return try {
            if (file.exists()) {
                FileProvider.getUriForFile(
                    applicationContext,
                    BugReportZipper.FILE_PROVIDER_NAME,
                    file
                )
            } else null
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG getFileUri error: ${e.message}", e)
            null
        }
    }

    private fun launchEmailIntent(body: String, supportZip: File?, category: String?) {
        try {
            val subject = buildSubject(category)
            val email = getString(R.string.about_mail_to)
            val zipUri = getFileUri(supportZip)

            val intent = if (zipUri != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    putExtra(Intent.EXTRA_STREAM, zipUri)
                    clipData = ClipData.newUri(contentResolver, "Support Diagnostics", zipUri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
            }

            startActivity(
                Intent.createChooser(intent, getString(R.string.support_email_chooser_title))
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG launchEmailIntent error: ${e.message}", e)
            Toast.makeText(this, getString(R.string.support_error_email), Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSubject(category: String?): String {
        // Subject is kept minimal: no user data or subscription details in the subject line.
        // All diagnostic data lives in the attachment.
        val catPart = if (category != null) " [$category]" else ""
        return "Rethink Plus Support Request: $catPart"
    }

    /** Returns the app version name, falling back to "?" on error. */
    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }
            .getOrElse { "?" }

    /**
     * Redacts a sensitive ID (accountId, cid, etc.) so that at most the first
     * 12 characters are visible and the rest is replaced with "***".
     *
     * Examples:
     *   "37e3d67bc99b89eb4ac600f7ffdb4019" → "37e3d67bc99b***"
     *   "short123" → "short123"  (≤12 chars shown as-is)
     *   "" → "-"
     */
    private fun redactId(id: String): String {
        if (id.isEmpty()) return "N/A"
        return if (id.length > 12) "${id.take(12)}***" else id
    }

    private fun setLoading(loading: Boolean) {
        b.btnSendEmail.isEnabled = !loading
        b.btnSendEmail.text = if (loading)
            getString(R.string.support_btn_sending)
        else
            getString(R.string.support_btn_send)
        b.layoutLoading.isVisible = loading
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun formatTs(ms: Long): String {
        if (ms <= 0L || ms == Long.MAX_VALUE) return "N/A"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }
}
