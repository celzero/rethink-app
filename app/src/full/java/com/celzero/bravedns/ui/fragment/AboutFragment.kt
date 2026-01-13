/*
 * Copyright 2020 RethinkDNS and its authors
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
import Logger.LOG_TAG_UI
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.text.method.LinkMovementMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.databinding.DialogWhatsnewBinding
import com.celzero.bravedns.databinding.FragmentAboutBinding
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.scheduler.BugReportZipper.getZipFileName
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.bottomsheet.BugReportFilesBottomSheet
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_4
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.KernelProc
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.UIUtils.openAppInfo
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.UIUtils.sendEmailIntent
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getPackageMetadata
import com.celzero.bravedns.util.Utilities.getRandomString
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.disableFrostTemporarily
import com.celzero.bravedns.util.restoreFrost
import com.celzero.firestack.intra.Intra
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.jvm.java

class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener, KoinComponent {
    private val b by viewBinding(FragmentAboutBinding::bind)

    private var lastAppExitInfoDialogInvokeTime = INIT_TIME_MS
    private val workScheduler by inject<WorkScheduler>()
    private val appDatabase by inject<AppDatabase>()
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val SCHEME_PACKAGE = "package"

        // Version string constants
        private const val VERSION_SLICE_END_INDEX = 6

        // Time calculation constants (same as HomeScreenFragment for consistency)
        private const val MILLISECONDS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_MONTH = 30.0

        // Sponsorship calculation constants
        private const val BASE_AMOUNT_PER_MONTH = 0.60
        private const val ADDITIONAL_AMOUNT_PER_MONTH = 0.20
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    override fun onResume() {
        super.onResume()
        val themeId = Themes.getTheme(persistentState.theme)
        restoreFrost(themeId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        if (isFdroidFlavour()) {
            b.aboutAppUpdate.visibility = View.GONE
        }
        updateVersionInfo()
        updateSponsorInfo()
        updateTokenUi(persistentState.firebaseUserToken)

        b.titleStats.text = getString(R.string.title_statistics).lowercase()
        b.aboutStats.text = getString(R.string.settings_general_header).replaceFirstChar(Char::titlecase)

        if (DEBUG) {
            b.aboutFlightRecord.visibility = View.VISIBLE
        } else {
            b.aboutFlightRecord.visibility = View.GONE
        }

        b.aboutSponsor.setOnClickListener(this)
        b.aboutWebsite.setOnClickListener(this)
        b.aboutTwitter.setOnClickListener(this)
        b.aboutGithub.setOnClickListener(this)
        b.aboutBlog.setOnClickListener(this)
        b.aboutPrivacyPolicy.setOnClickListener(this)
        b.aboutTermsOfService.setOnClickListener(this)
        b.aboutLicense.setOnClickListener(this)
        b.aboutMail.setOnClickListener(this)
        b.aboutTelegram.setOnClickListener(this)
        b.aboutReddit.setOnClickListener(this)
        b.aboutMastodon.setOnClickListener(this)
        b.aboutElement.setOnClickListener(this)
        b.aboutFaq.setOnClickListener(this)
        b.mozillaImg.setOnClickListener(this)
        b.fossImg.setOnClickListener(this)
        b.aboutAppUpdate.setOnClickListener(this)
        b.aboutWhatsNew.setOnClickListener(this)
        b.aboutAppInfo.setOnClickListener(this)
        b.aboutAppNotification.setOnClickListener(this)
        b.aboutVpnProfile.setOnClickListener(this)
        b.aboutCrashLog.setOnClickListener(this)
        b.aboutAppVersion.setOnClickListener(this)
        b.aboutAppContributors.setOnClickListener(this)
        b.aboutAppTranslate.setOnClickListener(this)
        b.aboutStats.setOnClickListener(this)
        b.aboutProc.setOnClickListener(this)
        b.aboutDbStats.setOnClickListener(this)
        b.tokenTextView.setOnClickListener(this)
        b.aboutFlightRecord.setOnClickListener(this)
        b.aboutEventLogs.setOnClickListener(this)

        val gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val text = persistentState.firebaseUserToken
                    val clipboard =
                        getSystemService(requireContext(), ClipboardManager::class.java)
                    val clip = ClipData.newPlainText("token", text)
                    clipboard?.setPrimaryClip(clip)

                    Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT)
                        .show()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isFdroidFlavour()) return true

                    val newToken = generateNewToken()
                    b.tokenTextView.text = newToken
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.config_add_success_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
            })

        // suppress the warning about setting a touch listener
        b.tokenTextView.setOnTouchListener { v, event ->
            if (gestureDetector.onTouchEvent(event)) {
                v.performClick() // important to call this
                true
            } else {
                false // allow text selection
            }
        }
    }

    private fun updateVersionInfo() {
        try {
            val version = getVersionName()
            // take first 7 characters of the version name, as the version has build number
            // appended to it, which is not required for the user to see.
            val slicedVersion = version.slice(0..VERSION_SLICE_END_INDEX)
            b.aboutWhatsNew.text = getString(R.string.about_whats_new, slicedVersion)

            // complete version name along with the source of installation
            val v = getString(R.string.about_version_install_source, version, getDownloadSource())

            val build = Intra.build(false)
            val updatedTs = getLastUpdatedTs()
            b.aboutAppVersion.text = "$v\n$build\n$updatedTs"

        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_UI, "err-version-info; pkg name not found: ${e.message}", e)
        }
    }

    fun updateTokenUi(token: String) {
        if (isFdroidFlavour() || !persistentState.firebaseErrorReportingEnabled) {
            b.tokenTextView.visibility = View.GONE
            return
        }
        b.tokenTextView.text = token
    }

    private fun getLastUpdatedTs(): String {
        val pInfo: PackageInfo? = getPackageMetadata(requireContext().packageManager, requireContext().packageName)
        // TODO: modify this to use the latest version code api
        val updatedTs = pInfo?.lastUpdateTime ?: return ""
        return if (updatedTs > 0) {
            val updatedDate = Utilities.convertLongToTime(updatedTs, TIME_FORMAT_4)
            updatedDate
        } else {
            ""
        }
    }

    private fun updateSponsorInfo() {
        /*if (RpnProxyManager.isRpnEnabled()) {
            b.sponsorInfoUsage.visibility = View.GONE
            b.aboutSponsor.visibility = View.GONE
            return
        }*/

        b.sponsorInfoUsage.text = getSponsorInfo()
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(
                requireContext().packageManager,
                requireContext().packageName
            )
        return pInfo?.versionName ?: ""
    }

    private fun getSponsorInfo(): String {
        val installTime = requireContext().packageManager.getPackageInfo(
            requireContext().packageName,
            0
        ).firstInstallTime
        val timeDiff = System.currentTimeMillis() - installTime
        val days = (timeDiff / (MILLISECONDS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR * HOURS_PER_DAY)).toDouble()
        val month = days / DAYS_PER_MONTH
        val amount = month * (BASE_AMOUNT_PER_MONTH + ADDITIONAL_AMOUNT_PER_MONTH)
        val msg = getString(
            R.string.sponser_dialog_usage_msg,
            days.toInt().toString(),
            "%.2f".format(amount)
        )
        return msg
    }

    private fun getDownloadSource(): String {
        if (isFdroidFlavour()) return getString(R.string.build__flavor_fdroid)

        if (isPlayStoreFlavour()) return getString(R.string.build__flavor_play_store)

        return getString(R.string.build__flavor_website)
    }

    override fun onClick(view: View?) {
        when (view) {
            b.aboutTelegram -> {
                openUrl(requireContext(), getString(R.string.about_telegram_link))
            }
            b.aboutBlog -> {
                openUrl(requireContext(), getString(R.string.about_docs_link))
            }
            b.aboutFaq -> {
                openUrl(requireContext(), getString(R.string.about_faq_link))
            }
            b.aboutGithub -> {
                openUrl(requireContext(), getString(R.string.about_github_link))
            }
            b.aboutCrashLog -> {
                if (isAtleastO()) {
                    handleShowAppExitInfo()
                } else {
                    if (hasAnyLogsAvailable()) {
                        promptCrashLogAction()
                    } else {
                        showNoLogDialog()
                    }
                }
            }
            b.aboutMail -> {
                disableFrostTemporarily()
                sendEmailIntent(requireContext())
            }
            b.aboutTwitter -> {
                openUrl(requireContext(), getString(R.string.about_twitter_handle))
            }
            b.aboutWebsite -> {
                openUrl(requireContext(), getString(R.string.about_website_link))
            }
            b.aboutSponsor -> {
                openUrl(requireContext(), RETHINKDNS_SPONSOR_LINK)
            }
            b.mozillaImg -> {
                // no-link, no action
            }
            b.fossImg -> {
                openUrl(requireContext(), getString(R.string.about_foss_link))
            }
            b.flossFundsImg -> {
                openUrl(requireContext(), getString(R.string.about_floss_fund_link))
            }
            b.aboutAppUpdate -> {
                (requireContext() as HomeScreenActivity).checkForUpdate(
                    AppUpdater.UserPresent.INTERACTIVE
                )
            }
            b.aboutWhatsNew -> {
                showNewFeaturesDialog()
            }
            b.aboutAppInfo -> {
                openAppInfo(requireContext())
            }
            b.aboutVpnProfile -> {
                openVpnProfile(requireContext())
            }
            b.aboutAppNotification -> {
                openNotificationSettings()
            }
            b.aboutAppContributors -> {
                showContributors()
            }
            b.aboutAppTranslate -> {
                openUrl(requireContext(), getString(R.string.about_translate_link))
            }
            b.aboutPrivacyPolicy -> {
                openUrl(requireContext(), getString(R.string.about_privacy_policy_link))
            }
            b.aboutTermsOfService -> {
                openUrl(requireContext(), getString(R.string.about_terms_link))
            }
            b.aboutLicense -> {
                openUrl(requireContext(), getString(R.string.about_license_link))
            }
            b.aboutReddit -> {
                openUrl(requireContext(), getString(R.string.about_reddit_handle))
            }
            b.aboutMastodon -> {
                openUrl(requireContext(), getString(R.string.about_mastodom_handle))
            }
            b.aboutElement -> {
                openUrl(requireContext(), getString(R.string.about_matrix_handle))
            }
            b.aboutStats -> {
                openStatsDialog()
            }
            b.aboutProc -> {
                openProcDialog()
            }
            b.aboutDbStats -> {
                openDatabaseDumpDialog()
            }
            b.tokenTextView -> {
                // click is handled in gesture detector
            }
            b.aboutFlightRecord -> {
                initiateFlightRecord()
            }
            b.aboutEventLogs -> {
                openEventLogs()
            }
        }
    }

    private fun initiateFlightRecord() {
        io { VpnController.performFlightRecording() }
        Toast.makeText(requireContext(), "Flight recording started", Toast.LENGTH_SHORT).show()
    }

    private fun openEventLogs() {
        val intent = Intent(requireContext(), com.celzero.bravedns.ui.activity.EventsActivity::class.java)
        startActivity(intent)
    }

    private fun generateNewToken(): String {
        if (isFdroidFlavour()) return ""

        val newToken = getRandomString(TOKEN_LENGTH)
        persistentState.firebaseUserToken = newToken
        persistentState.firebaseUserTokenTimestamp = System.currentTimeMillis()
        updateTokenUi(newToken)
        return newToken
    }

    private fun openStatsDialog() {
        io {
            val stat = VpnController.getNetStat()
            val formatedStat = UIUtils.formatNetStat(stat)
            val vpnStats = VpnController.vpnStats()
            val stats = formatedStat + vpnStats
            uiCtx {
                if (!isAdded) return@uiCtx
                val tv = android.widget.TextView(requireContext())
                val pad = resources.getDimensionPixelSize(R.dimen.dots_margin_bottom)
                tv.setPadding(pad, pad, pad, pad)
                if (formatedStat == null) {
                    tv.text = "No Stats"
                } else {
                    tv.text = stats
                }
                tv.setTextIsSelectable(true)
                tv.typeface = android.graphics.Typeface.MONOSPACE
                val scroll = android.widget.ScrollView(requireContext())
                scroll.addView(tv)
                MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                    .setTitle(getString(R.string.title_statistics))
                    .setView(scroll)
                    .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
                    .setNeutralButton(R.string.dns_info_neutral) { _, _ ->
                        copyToClipboard("stats_dump", stats)
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.copied_clipboard),
                            Toast.LENGTH_SHORT
                        )
                    }.create()
                    .show()
            }
        }
    }

    private fun openProcDialog() {
        fun gatherProcDump(force: Boolean): String {
            val stat = KernelProc.getStats(force)
            val status = KernelProc.getStatus(force)
            val mem = KernelProc.getMem(force)
            val maps = KernelProc.getMaps(force)
            val smaps = KernelProc.getSmaps(force)
            val net = KernelProc.getNet(force)
            val ns = KernelProc.getNs(force)
            val sched = KernelProc.getSched(force)
            val task = KernelProc.getTask(force)
            return buildString {
                append(stat)
                append("\n\n")
                append(status)
                append("\n\n")
                append(mem)
                append("\n\n")
                append(maps)
                append("\n\n")
                append(smaps)
                append("\n\n")
                append(net)
                append("\n\n")
                append(ns)
                append("\n\n")
                append(sched)
                append("\n\n")
                append(task)
            }
        }

        io {
            val initial = gatherProcDump(force = false)
            uiCtx {
                if (!isAdded) return@uiCtx
                val tv = android.widget.TextView(requireContext())
                val pad = resources.getDimensionPixelSize(R.dimen.dots_margin_bottom)
                tv.setPadding(pad, pad, pad, pad)
                tv.text = initial.ifEmpty { "No Proc stats" }
                tv.setTextIsSelectable(true)
                tv.typeface = android.graphics.Typeface.MONOSPACE
                val scroll = android.widget.ScrollView(requireContext())
                scroll.addView(tv)

                val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                    .setTitle(getString(R.string.title_proc))
                    .setView(scroll)
                    .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
                    .setNegativeButton(R.string.dns_info_neutral) { _, _ ->
                        copyToClipboard("proc_dump", tv.text.toString())
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.copied_clipboard),
                            Toast.LENGTH_SHORT
                        )
                    }
                    .setNeutralButton("Refresh", null)
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        io {
                            val refreshed = gatherProcDump(force = true)
                            uiCtx {
                                if (isAdded) tv.text = refreshed
                                showToastUiCentered(requireContext(), getString(R.string.config_add_success_toast),
                                    Toast.LENGTH_SHORT)
                            }
                        }
                    }
                }

                dialog.show()
            }
        }
    }

    private fun copyToClipboard(label: String, text: String): ClipboardManager? {
        val cb = getSystemService(requireContext(), ClipboardManager::class.java)
        cb?.setPrimaryClip(ClipData.newPlainText(label, text))
        return cb
    }

    private fun openDatabaseDumpDialog() {
        io {
            val tables = getDatabaseTables()
            uiCtx {
                if (!isAdded) return@uiCtx
                if (tables.isEmpty()) {
                    showToastUiCentered(requireContext(), getString(R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                    return@uiCtx
                }
                val appended = mutableSetOf<String>()
                val ctx = requireContext()
                val pad = resources.getDimensionPixelSize(R.dimen.dots_margin_bottom)
                val tv = android.widget.TextView(ctx)
                tv.setPadding(pad, pad, pad, pad)
                tv.text = "Select a table to load its dump"
                tv.setTextIsSelectable(true)
                tv.typeface = android.graphics.Typeface.MONOSPACE
                val scroll = android.widget.ScrollView(ctx)
                scroll.addView(tv)

                val listView = android.widget.ListView(ctx)
                val listHeight = (resources.displayMetrics.heightPixels * 0.30).toInt()
                listView.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    listHeight
                )
                val adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, tables)
                listView.adapter = adapter

                // load + append dump when a table is tapped
                listView.onItemClickListener =
                    android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                        val table = tables[position]
                        if (appended.contains(table)) {
                            showToastUiCentered(
                                ctx,
                                getString(R.string.config_add_success_toast),
                                Toast.LENGTH_SHORT
                            )
                            return@OnItemClickListener
                        }
                        appended.add(table)
                        tv.append("\nLoading $table ...\n")
                        io {
                            val dump = buildTableDump(table)
                            uiCtx {
                                if (!isAdded) return@uiCtx
                                // replace the temporary loading line (not strictly necessary)
                                tv.text = tv.text.toString().replace("Loading $table ...", "")
                                tv.append("\n===== TABLE: $table =====\n")
                                tv.append(dump)
                            }
                        }
                    }

                val container = android.widget.LinearLayout(ctx)
                container.orientation = android.widget.LinearLayout.VERTICAL
                container.addView(listView)
                container.addView(scroll)

                MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
                    .setTitle(getString(R.string.title_database_dump))
                    .setView(container)
                    .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
                    .setNeutralButton(R.string.dns_info_neutral) { _, _ ->
                        copyToClipboard("db_dump", tv.text.toString())
                        showToastUiCentered(
                            ctx,
                            getString(R.string.copied_clipboard),
                            Toast.LENGTH_SHORT
                        )
                    }.create()
                    .show()
            }
        }
    }

    private fun getDatabaseTables(): List<String> {
        val db = appDatabase.openHelper.readableDatabase
        val cursor =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        val tablesToSkip = setOf(
            "android_metadata",
            "sqlite_sequence",
            "room_master_table",
            "TcpProxyEndpoint",
            "RpnProxy",
            "SubscriptionStatus",
            "SubscriptionStateHistory"
        )
        val tables = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)
                if (!tablesToSkip.contains(name)) tables.add(name)
            }
        }
        return tables
    }

    private fun buildTableDump(table: String): String {
        val db = appDatabase.openHelper.readableDatabase
        val sb = StringBuilder()
        return try {
            val pragma = db.query("PRAGMA table_info($table)")
            val columns = mutableListOf<String>()
            pragma.use { p ->
                while (p.moveToNext()) {
                    val colNameIdx = p.getColumnIndexOrThrow("name")
                    columns.add(p.getString(colNameIdx))
                }
            }
            sb.append(columns.joinToString(" | ")).append('\n')
            val maxRowsPerTable = 500
            val dataCursor = db.query("SELECT * FROM $table LIMIT $maxRowsPerTable")
            var rowCount = 0
            dataCursor.use { dc ->
                while (dc.moveToNext()) {
                    val row = buildString {
                        columns.forEachIndexed { idx, col ->
                            if (idx > 0) append(" | ")
                            val colIndex = dc.getColumnIndex(col)
                            if (colIndex >= 0) {
                                when (dc.getType(colIndex)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> append("NULL")
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> append(
                                        dc.getLong(
                                            colIndex
                                        )
                                    )

                                    android.database.Cursor.FIELD_TYPE_FLOAT -> append(
                                        dc.getDouble(
                                            colIndex
                                        )
                                    )

                                    android.database.Cursor.FIELD_TYPE_STRING -> {
                                        var v = dc.getString(colIndex)
                                        if (v.length > 200) v = v.substring(0, 200) + "…"
                                        append(v.replace('\n', ' '))
                                    }

                                    android.database.Cursor.FIELD_TYPE_BLOB -> append("<BLOB>")
                                    else -> append("?")
                                }
                            } else append("?")
                        }
                    }
                    sb.append(row).append('\n')
                    rowCount++
                }
            }
            val countCursor = db.query("SELECT COUNT(1) FROM $table")
            var total = rowCount
            countCursor.use { cc -> if (cc.moveToFirst()) total = cc.getInt(0) }
            if (total > rowCount) {
                sb.append("[shown ").append(rowCount).append(" of ").append(total)
                    .append(" rows]\n")
            } else {
                sb.append("[rows: ").append(total).append("]\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error dumping $table: ${e.message}\n"
        }
    }

    /**
     * Checks if any bug report logs are available (bug report zip or tombstone files).
     * @return true if at least one log file exists, false otherwise
     */
    private fun hasAnyLogsAvailable(): Boolean {
        val dir = requireContext().filesDir

        val bugReportZip = File(getZipFileName(dir))
        if (bugReportZip.exists() && bugReportZip.length() > 0) {
            return true
        }

        if (isAtleastO()) {
            val tombstoneZip = EnhancedBugReport.getTombstoneZipFile(requireContext())
            if (tombstoneZip != null && tombstoneZip.exists() && tombstoneZip.length() > 0) {
                return true
            }

            val tombstoneDir = File(dir, EnhancedBugReport.TOMBSTONE_DIR_NAME)
            if (tombstoneDir.exists() && tombstoneDir.isDirectory) {
                val tombstoneFiles = tombstoneDir.listFiles()
                if (tombstoneFiles != null && tombstoneFiles.any { it.isFile && it.length() > 0 }) {
                    return true
                }
            }
        }

        val bugReportDir = File(dir, BugReportZipper.BUG_REPORT_DIR_NAME)
        if (bugReportDir.exists() && bugReportDir.isDirectory) {
            val bugReportFiles = bugReportDir.listFiles()
            if (bugReportFiles != null && bugReportFiles.any { it.isFile && it.length() > 0 }) {
                return true
            }
        }

        return false
    }

    private fun showNoLogDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.about_bug_no_log_dialog_title)
        builder.setMessage(R.string.about_bug_no_log_dialog_message)
        builder.setPositiveButton(getString(R.string.about_bug_no_log_dialog_positive_btn)) { _, _ ->
            sendEmailIntent(requireContext())
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ -> dialog.dismiss() }
        builder.create().show()
    }

    private fun openNotificationSettings() {
        val packageName = requireContext().packageName
        try {
            val intent = Intent()
            if (isAtleastO()) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = "$SCHEME_PACKAGE:$packageName".toUri()
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.notification_screen_error),
                Toast.LENGTH_SHORT
            )
            Logger.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    private fun showNewFeaturesDialog() {
        val binding =
            DialogWhatsnewBinding.inflate(LayoutInflater.from(requireContext()), null, false)
        binding.desc.movementMethod = LinkMovementMethod.getInstance()
        binding.desc.text = htmlToSpannedText(getString(R.string.whats_new_version_update))
        // replace the version name in the title
        val v = getVersionName().slice(0..6)
        val title = getString(R.string.about_whats_new, v)
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setView(binding.root)
            .setTitle(title)
            .setPositiveButton(getString(R.string.about_dialog_positive_button)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .setNeutralButton(getString(R.string.about_dialog_neutral_button)) { _: DialogInterface, _: Int ->
                sendEmailIntent(requireContext())
            }
            .setCancelable(true)
            .create()
            .show()
    }

    private fun showContributors() {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        val heading = dialogBinding.infoRulesDialogRulesTitle
        val okBtn = dialogBinding.infoRulesDialogCancelImg
        val descText = dialogBinding.infoRulesDialogRulesDesc
        dialogBinding.infoRulesDialogRulesIcon.visibility = View.GONE

        heading.text = getString(R.string.contributors_dialog_title)
        heading.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_authors),
            null,
            null,
            null
        )

        heading.gravity = Gravity.CENTER
        descText.gravity = Gravity.CENTER

        descText.movementMethod = LinkMovementMethod.getInstance()
        descText.text = htmlToSpannedText(getString(R.string.contributors_list))

        okBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun promptCrashLogAction() {
        // ensure tombstone logs are added to zip if available
        if (isAtleastO()) {
            io {
                try {
                    EnhancedBugReport.addLogsToZipFile(requireContext())
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "err adding tombstone to zip: ${e.message}", e)
                }
            }
        }

        // see if bug report files exist
        val dir = requireContext().filesDir
        val zipPath = getZipFileName(dir)
        val zipFile = File(zipPath)

        if (!zipFile.exists() || zipFile.length() <= 0) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.log_file_not_available),
                Toast.LENGTH_SHORT
            )
            return
        }

        // show btmsht with file list
        val bottomSheet = BugReportFilesBottomSheet()
        bottomSheet.show(parentFragmentManager, "BugReportFilesBottomSheet")
    }

    private fun handleShowAppExitInfo() {
        if (WorkScheduler.isWorkRunning(requireContext(), WorkScheduler.APP_EXIT_INFO_JOB_TAG))
            return

        workScheduler.scheduleOneTimeWorkForAppExitInfo()
        showBugReportProgressUi()

        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        workManager.getWorkInfosByTagLiveData(WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG).observe(
            viewLifecycleOwner
        ) { workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                Logger.LOG_TAG_SCHEDULER,
                "WorkManager state: ${workInfo.state} for ${WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                onAppExitInfoSuccess()
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                WorkInfo.State.FAILED == workInfo.state
            ) {
                onAppExitInfoFailure()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(WorkScheduler.APP_EXIT_INFO_ONE_TIME_JOB_TAG)
            } else { // state == blocked, queued, or running
                // no-op
            }
        }
    }

    private fun onAppExitInfoFailure() {
        showToastUiCentered(
            requireContext(),
            getString(R.string.log_file_not_available),
            Toast.LENGTH_SHORT
        )
        hideBugReportProgressUi()
    }

    private fun showBugReportProgressUi() {
        b.progressLayout.visibility = View.VISIBLE
        b.aboutCrashLog.visibility = View.GONE
    }

    private fun hideBugReportProgressUi() {
        b.progressLayout.visibility = View.GONE
        b.aboutCrashLog.visibility = View.VISIBLE
    }

    private fun onAppExitInfoSuccess() {
        // refrain from calling promptCrashLogAction multiple times
        if (
            SystemClock.elapsedRealtime() - lastAppExitInfoDialogInvokeTime <
            TimeUnit.SECONDS.toMillis(1L)
        ) {
            return
        }

        lastAppExitInfoDialogInvokeTime = SystemClock.elapsedRealtime()
        hideBugReportProgressUi()
        promptCrashLogAction()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
