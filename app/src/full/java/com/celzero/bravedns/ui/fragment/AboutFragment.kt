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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.system.Os
import android.system.OsConstants
import android.text.method.LinkMovementMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.net.toUri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.databinding.DialogWhatsnewBinding
import com.celzero.bravedns.databinding.FragmentAboutBinding
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.BugReportZipper
import com.celzero.bravedns.scheduler.BugReportZipper.getZipFileName
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.activity.ConsoleLogActivity
import com.celzero.bravedns.ui.activity.EventsActivity
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.ui.bottomsheet.BugReportFilesBottomSheet
import com.celzero.bravedns.sponsor.provider.SponsorProvider
import com.celzero.bravedns.sponsor.repository.SponsorRepository
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_4
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.FirebaseErrorReporting.TOKEN_LENGTH
import com.celzero.bravedns.util.KernelProc
import com.celzero.bravedns.util.MemoryUtils
import com.celzero.bravedns.util.MemoryProfiler
import com.celzero.bravedns.util.GoMemoryProfiler
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
import com.celzero.bravedns.util.Utilities.isWebsiteDegoogledFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.disableFrostTemporarily
import com.celzero.bravedns.util.restoreFrost
import com.celzero.firestack.intra.Intra
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener, KoinComponent {
    private val b by viewBinding(FragmentAboutBinding::bind)

    private var lastAppExitInfoDialogInvokeTime = INIT_TIME_MS
    private val workScheduler by inject<WorkScheduler>()
    private val appDatabase by inject<AppDatabase>()
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private val sponsorProvider by inject<SponsorProvider>()
    private val sponsorRepository by inject<SponsorRepository>()

    // Scope that survives fragment destruction so profiling work completes.
    // Canceled in onDestroyView.
    private var profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Storage Access Framework launcher that lets the user pick a directory where heap
    // dumps are stored. Requires NO storage permissions: the picker grants a persistable
    // URI permission which we retain across launches. Initialized in onCreate (must be
    // registered before the fragment reaches STARTED, per ActivityResult API contract).
    private lateinit var memoryProfileDirLauncher: ActivityResultLauncher<Uri?>

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

        private const val TAP_THRESHOLD_MS = 2000L // reset if too slow
        private const val REQUIRED_TAPS = 7

        // MIME type used when creating heap-dump documents via SAF. octet-stream keeps the
        // profiler's file extension (.pprof) intact without SAF appending its own.
        private const val HEAP_DUMP_MIME = "application/octet-stream"
    }

    private var tapCount = 0
    private var lastTapTime = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Re-create the scope in case the fragment view was destroyed and re-created.
        if (!profileScope.isActive) {
            profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        initView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the SAF directory picker before the fragment is STARTED so it survives
        // configuration changes (matches the pattern used by other fragments).
        memoryProfileDirLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                val ctx = context
                if (uri == null) {
                    if (ctx != null) {
                        showToastUiCentered(ctx, "No location selected", Toast.LENGTH_SHORT)
                    }
                    return@registerForActivityResult
                }
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Logger.w(LOG_TAG_UI, "Could not persist URI permission for $uri", e)
                }
                persistentState.memoryProfileDirUri = uri.toString()
                performMemoryProfileCapture(uri)
            }
    }

    override fun onResume() {
        super.onResume()
        val themeId = Themes.getTheme(persistentState.theme)
        restoreFrost(themeId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        profileScope.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        if (isFdroidFlavour()) {
            b.aboutAppUpdate.visibility = View.GONE
        }

        // Show "α" badge next to the app name when running an alpha build so testers
        // can immediately identify they are on a pre-release version.
        if (Utilities.isAlphaBuild()) {
            b.fhsTitleRethink.setText(R.string.app_name_alpha)
            b.fhsTitleRethink.isAllCaps = false
        }

        if (DEBUG) {
            b.aboutMemoryProfile.visibility = View.VISIBLE
        } else {
            b.aboutMemoryProfile.visibility = View.GONE
        }

        updateVersionInfo()
        updateSponsorInfo()
        updateTokenUi(persistentState.firebaseUserToken)
        
        b.aboutStats.text = getString(R.string.settings_general_header).replaceFirstChar(Char::titlecase)

        b.fhsTitleRethink.setOnClickListener(this)
        b.aboutSponsor.setOnClickListener(this)
        b.aboutSponsorAgain.setOnClickListener(this)
        b.aboutManageRpn.setOnClickListener(this)
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
        b.flossFundsImg.setOnClickListener(this)
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
        b.aboutStackTrace.setOnClickListener(this)
        b.aboutMemoryProfile.setOnClickListener(this)
        b.aboutDbStats.setOnClickListener(this)
        b.tokenTextView.setOnClickListener(this)
        b.aboutConsoleLogs.setOnClickListener(this)
        b.aboutEventLogs.setOnClickListener(this)

        val gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val text = persistentState.firebaseUserToken
                    val ctx = context ?: return false
                    val clipboard =
                        getSystemService(ctx, ClipboardManager::class.java)
                    val clip = ClipData.newPlainText("token", text)
                    clipboard?.setPrimaryClip(clip)

                    Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT)
                        .show()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isFdroidFlavour()) return true

                    val newToken = generateNewToken()
                    b.tokenTextView.text = newToken
                    val ctx = context ?: return true
                    Toast.makeText(
                        ctx,
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
        val ctx = context ?: return ""
        val pInfo: PackageInfo? = getPackageMetadata(ctx.packageManager, ctx.packageName)
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
        if (RpnProxyManager.isRpnEnabled()) {
            b.aboutSponsor.visibility = View.GONE
            b.aboutManageRpn.visibility = View.VISIBLE
            b.sponsorInfoUsage.visibility = View.GONE
            b.aboutCommunitySponsor.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Seed immediately from a one-shot DB read so the sponsor CTA reflects the
            // persisted sponsorship state before the reactive flow emits. Without this,
            // the "Sponsor" button (visible by default in the layout) is shown briefly
            // even for already-sponsored users on the initial launch.
            applySponsorState(sponsorRepository.isCurrentlySponsored())
            sponsorRepository.isSponsored.collect { isSponsored ->
                applySponsorState(isSponsored)
            }
        }
    }

    private suspend fun applySponsorState(isSponsored: Boolean) {
        if (isSponsored) {
            b.aboutSponsor.visibility = View.GONE
            b.aboutManageRpn.visibility = View.GONE
            b.sponsorInfoUsage.visibility = View.GONE
            b.aboutCommunitySponsor.visibility = View.VISIBLE

            val sponsorSince = sponsorRepository.getSponsorSince()
            if (sponsorSince != null && sponsorSince > 0) {
                val dateStr = Utilities.convertLongToTime(sponsorSince, TIME_FORMAT_4)
                b.aboutSponsorSince.text = getString(R.string.sponsor_about_since, dateStr)
            }
        } else {
            b.aboutSponsor.visibility = View.VISIBLE
            b.aboutManageRpn.visibility = View.GONE
            b.sponsorInfoUsage.visibility = View.VISIBLE
            b.aboutCommunitySponsor.visibility = View.GONE
            b.sponsorInfoUsage.text = getSponsorInfo()
        }
    }

    private fun openRpnDashboardScreen() {
        startActivity(
            FragmentHostActivity.createIntent(
                context = requireContext(),
                fragmentClass = RethinkPlusDashboardFragment::class.java,
                args = Bundle()
            )
        )
    }

    private fun getVersionName(): String {
        val ctx = context ?: return ""
        val pInfo: PackageInfo? =
            getPackageMetadata(ctx.packageManager, ctx.packageName)
        return pInfo?.versionName ?: ""
    }

    private fun getSponsorInfo(): String {
        val ctx = context ?: return ""
        val installTime = ctx.packageManager.getPackageInfo(
            ctx.packageName,
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
        if (isWebsiteDegoogledFlavour()) return getString(R.string.build_flavor_website_degoogled)

        if (isFdroidFlavour()) return getString(R.string.build__flavor_fdroid)

        if (isPlayStoreFlavour()) return getString(R.string.build__flavor_play_store)

        return getString(R.string.build__flavor_website)
    }

    override fun onClick(view: View?) {
        when (view) {
            b.fhsTitleRethink -> {
                handleTitleClick()
            }
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
                sponsorProvider.openSponsor(requireContext())
            }
            b.aboutSponsorAgain -> {
                sponsorProvider.openSponsor(requireContext())
            }
            b.aboutManageRpn -> {
                openRpnDashboardScreen()
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
            b.aboutStackTrace -> {
                openStackTraceDialog()
            }
            b.aboutMemoryProfile -> {
                openMemoryProfile()
            }
            b.aboutDbStats -> {
                openDatabaseDumpDialog()
            }
            b.tokenTextView -> {
                // click is handled in gesture detector
            }
            b.aboutConsoleLogs -> {
                openConsoleLogs()
            }
            b.aboutEventLogs -> {
                openEventLogs()
            }
        }
    }

    private fun handleTitleClick() {
        val currentTime = System.currentTimeMillis()

        // Reset if taps are too far apart
        if (currentTime - lastTapTime > TAP_THRESHOLD_MS) {
            tapCount = 0
        }

        tapCount++
        lastTapTime = currentTime

        if (tapCount == REQUIRED_TAPS) {
            enableTestMode()
            tapCount = 0
        }
    }

    private fun enableTestMode() {
        persistentState.appTestMode = true
        val ctx = context ?: return
        showToastUiCentered(ctx, "Test mode enabled", Toast.LENGTH_SHORT)
        Logger.i(LOG_TAG_UI, "Test mode enabled")
        logEvent(EventType.UI_TOGGLE, "Test mode enabled", "User enabled test mode")
    }

    private fun openStackTraceDialog() {
        io {
            val goStackTrace = GoVpnAdapter.printStack()
            val jvmStackTrace = captureJVMStackTraces()
            uiCtx {
                if (!isAdded) return@uiCtx
                showStackTraceDialog(goStackTrace, jvmStackTrace)
            }
        }
    }

    private fun openMemoryProfile() {
        if (!DEBUG) return
        val ctx = context ?: return

        // If the user already chose a directory (and it is still reachable), reuse it.
        val stored = persistentState.memoryProfileDirUri
        if (stored.isNotEmpty()) {
            val uri = stored.toUri()
            if (isTreeUriAccessible(ctx, uri)) {
                performMemoryProfileCapture(uri)
                return
            }
            // Persisted URI is no longer accessible (revoked / deleted); forget it and
            // prompt the user to pick again.
            persistentState.memoryProfileDirUri = ""
        }

        launchMemoryProfileDirPicker(ctx)
    }

    /**
     * Opens the system directory picker (Storage Access Framework). No storage permission
     * is required: the picker grants a persistable URI permission that we retain across
     * launches. The result is handled by [memoryProfileDirLauncher].
     */
    private fun launchMemoryProfileDirPicker(ctx: android.content.Context) {
        try {
            // OpenDocumentTree builds its own Intent; pass null to start at the default
            // location (optionally a previously-known URI could be passed as the initial dir).
            memoryProfileDirLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            Logger.e(LOG_TAG_UI, "No activity found to handle OPEN_DOCUMENT_TREE: ${e.message}")
            showToastUiCentered(ctx, "No file picker available", Toast.LENGTH_LONG)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening directory picker: ${e.message}")
            showToastUiCentered(ctx, "Could not open file picker", Toast.LENGTH_LONG)
        }
    }

    /**
     * Captures both heap dumps (into internal staging files), copies them into the
     * user-selected SAF directory [destTreeUri], deletes the staging files, and fires a
     * notification + toast with the outcome.
     */
    private fun performMemoryProfileCapture(destTreeUri: Uri) {
        val ctx = context ?: return
        // Use the application context so ContentResolver operations remain valid even if
        // the fragment view is torn down mid-capture.
        val appCtx = ctx.applicationContext
        val theme = UIUtils.getAccentColor(persistentState.theme)
        showToastUiCentered(ctx, "Capturing memory profiles...", Toast.LENGTH_SHORT)
        profileScope.launch {
            // Stage the dumps in the internal mem_profile dir (unavoidable: both
            // Debug.dumpHprofData and the Go memProfile API require a file path, not a stream).
            val jvmResult = MemoryProfiler.captureHeapDump(appCtx)
            val goResult = GoMemoryProfiler.captureGoHeapDump(appCtx)

            // Copy each staging file into the user-chosen directory and clean up the staging copy.
            val jvmOutcome = publishStagedProfile(
                appCtx, "JVM", jvmResult.success, jvmResult.file,
                jvmResult.errorMessage, destTreeUri
            )
            val goOutcome = publishStagedProfile(
                appCtx, "Go", goResult.success, goResult.file,
                goResult.errorMessage, destTreeUri
            )

            withContext(NonCancellable + Dispatchers.Main) {
                // Notification always fires uses app context, not fragment.
                showMemoryProfileNotification(appCtx, theme, jvmOutcome, goOutcome)
                // Toast only if the fragment is still attached.
                if (isAdded) {
                    val msg = buildProfileResultMessage(jvmOutcome, goOutcome)
                    showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
                }
            }
        }
    }

    /**
     * Final outcome of a single heap dump after it has been (attempted to be) written to
     * the user-selected directory.
     */
    private data class ProfileOutcome(
        val label: String,
        val success: Boolean,
        val fileName: String?,
        val sizeBytes: Long,
        val errorMessage: String?
    )

    /**
     * Copies [stagingFile] (the internal dump produced by a profiler) into the SAF tree
     * [destTreeUri] and deletes the staging file. Returns a [ProfileOutcome] describing the
     * final result visible to the user.
     */
    private fun publishStagedProfile(
        appCtx: android.content.Context,
        label: String,
        captureOk: Boolean,
        stagingFile: File,
        captureError: String?,
        destTreeUri: Uri
    ): ProfileOutcome {
        // If capture failed, there is nothing to copy. Still best-effort clean any partial file.
        if (!captureOk) {
            runCatching { if (stagingFile.exists()) stagingFile.delete() }
            return ProfileOutcome(label, false, null, 0L, captureError)
        }
        return try {
            val destName = copyFileToTree(
                appCtx.contentResolver, destTreeUri, stagingFile, stagingFile.name
            )
            ProfileOutcome(
                label = label,
                success = destName != null,
                fileName = destName ?: stagingFile.name,
                sizeBytes = stagingFile.length(),
                errorMessage = if (destName == null) "Failed to save to selected folder" else null
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err copying $label heap dump to selected dir: ${e.message}", e)
            ProfileOutcome(label, false, stagingFile.name, stagingFile.length(), "Copy failed: ${e.message}")
        } finally {
            // The staging file is always a transient artifact once the copy has been attempted.
            runCatching { if (stagingFile.exists()) stagingFile.delete() }
        }
    }

    /**
     * Creates a new document named [displayName] inside the SAF tree [treeUri] and copies the
     * contents of [src] into it. Returns the (possibly renamed) display name, or null on failure.
     * Uses [DocumentsContract] directly so no extra androidx.documentfile dependency is needed.
     */
    private fun copyFileToTree(
        contentResolver: android.content.ContentResolver,
        treeUri: Uri,
        src: File,
        displayName: String
    ): String? {
        if (!src.exists() || src.length() <= 0) return null
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        // createDocument may rename the file on name collision (e.g. "name (1).pprof").
        val childUri = DocumentsContract.createDocument(
            contentResolver, treeDocUri, HEAP_DUMP_MIME, displayName
        ) ?: return null
        // createDocument has already materialized a (zero-byte) document in the user's
        // chosen directory. If the write below fails for any reason, delete that orphan
        // so the user is not left with empty or truncated .pprof files in their folder.
        var written = false
        try {
            contentResolver.openOutputStream(childUri).use { out ->
                if (out == null) return null
                src.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            }
            written = true
        } finally {
            if (!written) {
                runCatching { DocumentsContract.deleteDocument(contentResolver, childUri) }
                    .onFailure {
                        Logger.w(
                            LOG_TAG_UI,
                            "could not delete orphaned profile doc: $childUri",
                            it as? Exception
                        )
                    }
            }
        }
        if (!written) return null
        // Resolve the final display name (SAF may have changed it) for reporting.
        var name = displayName
        contentResolver.query(
            childUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: displayName
            }
        }
        return name
    }

    /**
     * Returns true if the SAF tree [uri] can still be queried (i.e. permission is held and the
     * document still exists). Used to validate a persisted directory before reusing it.
     */
    private fun isTreeUriAccessible(ctx: android.content.Context, uri: Uri): Boolean {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            ctx.contentResolver.query(
                treeDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c -> c.moveToFirst() } ?: false
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "memory profile dir uri not accessible: $uri", e)
            false
        }
    }

    private fun buildProfileResultMessage(
        jvm: ProfileOutcome,
        go: ProfileOutcome
    ): String {
        val parts = mutableListOf<String>()
        parts.add(if (jvm.success) "JVM: ${jvm.fileName}" else "JVM failed")
        parts.add(if (go.success) "Go: ${go.fileName}" else "Go failed")
        return parts.joinToString(" | ")
    }

    private fun showMemoryProfileNotification(
        context: android.content.Context,
        theme: Int,
        jvm: ProfileOutcome,
        go: ProfileOutcome
    ) {
        val jvmOk = jvm.success
        val goOk = go.success
        val bothOk = jvmOk && goOk
        val bothFailed = !jvmOk && !goOk

        val title = when {
            bothOk -> "Memory profiles captured"
            bothFailed -> "Memory profiles failed"
            else -> "Memory profile partial"
        }

        val contentText = buildString {
            if (jvmOk) {
                appendLine("JVM: ${jvm.fileName} (${formatFileSize(jvm.sizeBytes)})")
            } else {
                appendLine("JVM: ${jvm.errorMessage ?: "failed"}")
            }
            if (goOk) {
                appendLine("Go: ${go.fileName} (${formatFileSize(go.sizeBytes)})")
            } else {
                appendLine("Go: ${go.errorMessage ?: "failed"}")
            }
        }.trimEnd()

        val builder = NotificationCompat.Builder(context, "MEM_PROFILE_CHANNEL")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setColor(ContextCompat.getColor(context, theme))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setAutoCancel(true)

        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

        if (!isAtleastO()) {
            manager.notify("MEM_PROFILE", 210, builder.build())
            return
        }

        val channel = android.app.NotificationChannel(
            "MEM_PROFILE_CHANNEL",
            "Memory Profile",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Memory profiling results"
        }
        manager.createNotificationChannel(channel)
        manager.notify("MEM_PROFILE", 210, builder.build())
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val base = 1024.0
        val digitGroups = kotlin.math.min(
            (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(base)).toInt(),
            units.size - 1
        )
        var divisor = 1.0
        repeat(digitGroups) { divisor *= base }
        return "%.1f %s".format(bytes / divisor, units[digitGroups])
    }

    /** Captures the current stack trace of every live JVM/Kotlin thread off the main thread. */
    private fun captureJVMStackTraces(): String = buildString {
        Thread.getAllStackTraces().entries
            .sortedBy { it.key.name }
            .forEach { (thread, frames) ->
                appendLine(
                    "Thread: ${thread.name}" +
                    "  [id=${thread.id}" +
                    "  state=${thread.state}" +
                    "  daemon=${thread.isDaemon}" +
                    "  priority=${thread.priority}]"
                )
                if (frames.isEmpty()) {
                    appendLine("  (no stack frames)")
                } else {
                    frames.forEach { frame -> appendLine("  at $frame") }
                }
                appendLine()
            }
    }

    private fun showStackTraceDialog(
        goStackTrace: String,
        jvmStackTrace: String
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        val pad = resources.getDimensionPixelSize(R.dimen.dots_margin_bottom)

        val clipText = buildString {
            appendLine("=== JVM STACK ===")
            appendLine(jvmStackTrace.ifBlank { ctx.getString(R.string.lbl_not_available_short) })
            appendLine()
            appendLine("=== GO STACK ===")
            appendLine(goStackTrace.ifBlank { ctx.getString(R.string.lbl_not_available_short) })
        }

        fun makeTabButton(text: String): android.widget.Button {
            return android.widget.Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                this.text = text
                textSize = 12f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isAllCaps = false
            }
        }

        // use recycler as using textview with large stack traces causes OOM and ANR issues
        fun makeLineRecyclerView(content: String): androidx.recyclerview.widget.RecyclerView {
            val lines = content.ifBlank { ctx.getString(R.string.lbl_not_available_short) }
                .split('\n')
            return androidx.recyclerview.widget.RecyclerView(ctx).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                setHasFixedSize(false)
                adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<
                        androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

                    override fun getItemCount() = lines.size

                    override fun onCreateViewHolder(
                        parent: android.view.ViewGroup,
                        viewType: Int
                    ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                        val tv = android.widget.TextView(ctx).apply {
                            setPadding(pad, 1, pad, 1)
                            typeface = android.graphics.Typeface.MONOSPACE
                            textSize = 11.5f
                            // deliberately not selectable — avoids ActionMode/touch conflicts
                            isFocusable = false
                        }
                        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {}
                    }

                    override fun onBindViewHolder(
                        holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                        position: Int
                    ) {
                        (holder.itemView as android.widget.TextView).text = lines[position]
                    }
                }
            }
        }

        val jvmRv = makeLineRecyclerView(jvmStackTrace)
        val goRv     = makeLineRecyclerView(goStackTrace)

        val tabJvm = makeTabButton("JVM Stack")
        val tabGo     = makeTabButton("Go Stack")

        fun selectTab(showJvm: Boolean) {
            jvmRv.visibility = if (showJvm)  View.VISIBLE else View.GONE
            goRv.visibility     = if (!showJvm) View.VISIBLE else View.GONE
            tabJvm.alpha = if (showJvm)  1f else 0.45f
            tabGo.alpha     = if (!showJvm) 1f else 0.45f
            if (showJvm) jvmRv.scrollToPosition(0)
            else            goRv.scrollToPosition(0)
        }

        tabJvm.setOnClickListener { selectTab(true) }
        tabGo.setOnClickListener     { selectTab(false) }

        val tabRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(tabJvm)
            addView(tabGo)
        }

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tabRow)
            addView(jvmRv, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(goRv, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // Start on JVM Stack tab
        selectTab(true)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setTitle("Stacktrace")
            .setView(container)
            .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
            .setNegativeButton(R.string.dns_info_neutral) { _, _ ->
                copyToClipboard("stack_trace", clipText)
                showToastUiCentered(ctx, getString(R.string.copied_clipboard), Toast.LENGTH_SHORT)
            }
            .setNeutralButton("Refresh", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss()
                openStackTraceDialog()
            }
        }

        dialog.show()
    }

    private fun openEventLogs() {
        val intent = Intent(requireContext(), EventsActivity::class.java)
        startActivity(intent)
    }

    private fun openConsoleLogs() {
        val intent = Intent(requireContext(), ConsoleLogActivity::class.java)
        startActivity(intent)
    }

    private fun generateNewToken(): String {
        if (isFdroidFlavour()) return ""

        val newToken = getRandomString(TOKEN_LENGTH)
        persistentState.firebaseUserToken = newToken
        persistentState.firebaseUserTokenTimestamp = System.currentTimeMillis()
        updateTokenUi(newToken)
        setFirebaseUserId(newToken)
        logEvent(EventType.SYSTEM_EVENT, "Stability Program Token", "User regenerated new token for stability program")
        return newToken
    }

    fun setFirebaseUserId(token: String) {
        try {
            FirebaseErrorReporting.setUserId(token)
        } catch (_: Exception) { }
    }

    private fun openStatsDialog() {
        if (!isAdded) return
        val ctx = requireContext()
        val pad = resources.getDimensionPixelSize(R.dimen.dots_margin_bottom)
        val notAvailable = ctx.getString(R.string.lbl_not_available_short)

        val progressDialog = MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.title_statistics))
            .setView(android.widget.ProgressBar(ctx).apply { isIndeterminate = true })
            .setCancelable(true)
            .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
            .create()
        progressDialog.show()

        io {
            val (stats, timedOut) = try {
                val result = withTimeout(5000L.milliseconds) {
                    val stat = VpnController.getNetStat()
                    val formatedStat = UIUtils.formatNetStat(stat) ?: ""
                    val vpnStats = VpnController.vpnStats() ?: ""
                    formatedStat + vpnStats
                }
                result to false
            } catch (_: TimeoutCancellationException) {
                ctx.getString(R.string.lbl_not_available_short) to true
            }

            val lines = if (stats.isBlank()) {
                listOf(notAvailable)
            } else {
                if (timedOut) listOf("Stats collection timed out, partial results:\n") + stats.split('\n')
                else stats.split('\n')
            }
            val clipText = if (timedOut) "TIMED OUT\n$stats" else stats.ifEmpty { notAvailable }

            uiCtx {
                progressDialog.dismiss()
                if (!isAdded) return@uiCtx

                val selectedPositions = mutableSetOf<Int>()
                val highlightColor = UIUtils.fetchColor(ctx, android.R.attr.colorControlHighlight)

                // use recycler as using textview with large stats causes OOM and ANR issues
                val recyclerView = androidx.recyclerview.widget.RecyclerView(ctx).apply {
                    layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                    setHasFixedSize(true)
                    adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<
                            androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                        override fun getItemCount() = lines.size
                        override fun onCreateViewHolder(
                            parent: android.view.ViewGroup,
                            viewType: Int
                        ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                            val tv = android.widget.TextView(ctx).apply {
                                setPadding(pad, 1, pad, 1)
                                typeface = android.graphics.Typeface.MONOSPACE
                                textSize = 11.5f
                            }
                            return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {}
                        }
                        override fun onBindViewHolder(
                            holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                            position: Int
                        ) {
                            val tv = holder.itemView as android.widget.TextView
                            tv.text = lines[position]
                            if (selectedPositions.contains(position)) {
                                tv.setBackgroundColor(highlightColor)
                            } else {
                                tv.background = null
                            }

                            tv.setOnClickListener {
                                if (selectedPositions.contains(position)) {
                                    selectedPositions.remove(position)
                                } else {
                                    selectedPositions.add(position)
                                }
                                notifyItemChanged(position)
                            }
                        }
                    }
                }

                val container = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(recyclerView, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }

                MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
                    .setTitle(getString(R.string.title_statistics))
                    .setView(container)
                    .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
                    .setNeutralButton(R.string.dns_info_neutral) { _, _ ->
                        val textToCopy = if (selectedPositions.isEmpty()) {
                            clipText
                        } else {
                            selectedPositions.sorted().joinToString("\n") { lines[it] }
                        }
                        copyToClipboard("stats_dump", textToCopy)
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

    /** Format nanoseconds into a human-readable string. */
    private fun fmtNs(ns: Long): String = when {
        ns <= 0 -> "-"
        ns < 1_000L -> "$ns ns"
        ns < 1_000_000L -> "${"%.1f".format(ns / 1_000.0)} µs"
        ns < 1_000_000_000L -> "${"%.2f".format(ns / 1_000_000.0)} ms"
        else -> "${"%.3f".format(ns / 1_000_000_000.0)} s"
    }

    /** Format a long counter; returns "-" for non-positive values. */
    private fun fmtNum(v: Long): String = if (v <= 0) "-" else "%,d".format(v)

    private fun openProcDialog() {
        io {
            // Read everything on IO so the dialog opens quickly.
            val allThreadsSched = KernelProc.parseSchedAllThreads()
            val status = KernelProc.getStatus(forceRefresh = true)
            val smaps = KernelProc.getSmaps(forceRefresh = true)
            val auxv = KernelProc.getStats(forceRefresh = true)
            val stat = GoVpnAdapter.getGoMetrics()
            val formatedMetrics = UIUtils.formatNetMetrics(stat)
            val ctx = context
            val memMetrics = if (ctx != null) MemoryUtils.getMemoryStats(ctx) else ""
            uiCtx {
                if (!isAdded) return@uiCtx
                showProcDialog(allThreadsSched, status, smaps, auxv, formatedMetrics, memMetrics)
            }
        }
    }

    private fun showProcDialog(
        allThreadsSched: List<KernelProc.ThreadSchedInfo>,
        status: String,
        smaps: String,
        auxv: String,
        formatedMetrics: String?,
        memMetrics: String
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        val pad = resources.getDimensionPixelSize(R.dimen.dots_margin_bottom)

        val colorHint = ContextCompat.getColor(ctx, android.R.color.darker_gray)

        fun android.text.SpannableStringBuilder.bold(text: String): android.text.SpannableStringBuilder {
            val start = length
            append(text)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return this
        }

        fun android.text.SpannableStringBuilder.color(text: String, color: Int): android.text.SpannableStringBuilder {
            val start = length
            append(text)
            setSpan(android.text.style.ForegroundColorSpan(color),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return this
        }

        val otherSpan = android.text.SpannableStringBuilder()

        fun otherSection(title: String, content: String) {
            val start = otherSpan.length
            otherSpan.append("  $title\n")
            otherSpan.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, otherSpan.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            otherSpan.append("\n")
            val stripped = content.substringAfter("\n").trim()
            otherSpan.color("$stripped\n\n", colorHint)
        }

        // threads and the proc info in the same tab
        run {
            val threadCount = allThreadsSched.size
            otherSpan.bold("  THREADS  ($threadCount total)\n\n")

            if (allThreadsSched.isEmpty()) {
                otherSpan.color("  /proc/self/task not available or empty\n\n", colorHint)
            } else {
                allThreadsSched.forEach { t ->
                    otherSpan.bold("  ${t.tid}  [${t.name}]  ${t.state}\n")

                    val hasSchedstat = t.timeslices > 0 || t.runningNs > 0
                    val hasSchedFields = t.waitMax > 0 || t.nrWakeups > 0 ||
                            t.nrInvoluntarySwitches > 0 || t.nrVoluntarySwitches > 0

                    if (hasSchedstat) {
                        otherSpan.append(
                            "    run=${fmtNs(t.runningNs)}  wait=${fmtNs(t.waitingNs)}  slices=${fmtNum(t.timeslices)}\n"
                        )
                    }
                    if (hasSchedFields) {
                        val sb = StringBuilder("    ")
                        if (t.waitMax > 0)               sb.append("wait_max=${fmtNs(t.waitMax)}  ")
                        if (t.nrWakeups > 0)             sb.append("wakeups=${fmtNum(t.nrWakeups)}  ")
                        if (t.nrMigrations > 0)          sb.append("mig=${fmtNum(t.nrMigrations)}  ")
                        if (t.nrInvoluntarySwitches > 0) sb.append("inv_sw=${fmtNum(t.nrInvoluntarySwitches)}  ")
                        if (t.nrVoluntarySwitches > 0)   sb.append("vol_sw=${fmtNum(t.nrVoluntarySwitches)}")
                        otherSpan.append(sb.toString().trimEnd()).append("\n")
                    }
                    if (t.schedstatRaw.isNotBlank()) {
                        otherSpan.color("    schedstat: ${t.schedstatRaw}\n", colorHint)
                    }
                    otherSpan.append("\n")
                }
            }
        }

        otherSection("STATUS  (/proc/self/status)", status)
        otherSection("SMAPS  (/proc/self/smaps_rollup)", smaps)
        otherSection("AUXV  (/proc/self/auxv)", auxv)

        val clipText = buildString {
            appendLine("=== PROC / MEM ===")
            appendLine(otherSpan.toString())
            appendLine("=== METRICS ===")
            appendLine("Memory Metrics")
            appendLine(memMetrics)
            appendLine()
            appendLine(formatedMetrics.orEmpty())
        }

        fun makeTabButton(text: String): android.widget.Button {
            return android.widget.Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                this.text = text
                textSize = 12f
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isAllCaps = false
            }
        }

        fun makeScrollableSpannable(spannable: android.text.SpannableStringBuilder): android.widget.ScrollView {
            val tv = android.widget.TextView(ctx).apply {
                setPadding(pad, pad / 2, pad, pad)
                setText(spannable, android.widget.TextView.BufferType.SPANNABLE)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11.5f
            }
            return android.widget.ScrollView(ctx).apply {
                addView(tv)
                scrollBarStyle = android.widget.ScrollView.SCROLLBARS_INSIDE_OVERLAY
            }
        }

        fun makeScrollableText(content: String?): android.widget.ScrollView {
            val tv = android.widget.TextView(ctx).apply {
                setPadding(pad, pad / 2, pad, pad)
                text = content ?: requireContext().getString(R.string.lbl_not_available_short)
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11.5f
            }
            return android.widget.ScrollView(ctx).apply {
                addView(tv)
                scrollBarStyle = android.widget.ScrollView.SCROLLBARS_INSIDE_OVERLAY
            }
        }

        val procScrollView    = makeScrollableSpannable(otherSpan)
        val metricsContent = buildString {
            appendLine("Memory Metrics")
            appendLine(memMetrics)
            appendLine()
            appendLine(formatedMetrics.orEmpty())
        }
        val metricsScrollView = makeScrollableText(metricsContent)

        val tabProc    = makeTabButton("Threads / Proc / Mem")
        val tabMetrics = makeTabButton("Metrics")

        fun selectTab(showProc: Boolean) {
            procScrollView.visibility    = if (showProc)  View.VISIBLE else View.GONE
            metricsScrollView.visibility = if (!showProc) View.VISIBLE else View.GONE
            tabProc.alpha    = if (showProc)  1f else 0.45f
            tabMetrics.alpha = if (!showProc) 1f else 0.45f
            if (showProc) procScrollView.post    { procScrollView.scrollTo(0, 0) }
            else          metricsScrollView.post { metricsScrollView.scrollTo(0, 0) }
        }

        tabProc.setOnClickListener    { selectTab(true) }
        tabMetrics.setOnClickListener { selectTab(false) }

        val tabRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(tabMetrics)
            addView(tabProc)
        }

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tabRow)
            addView(procScrollView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(metricsScrollView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // Start on the Proc / Mem tab
        selectTab(false)

        val dialog = MaterialAlertDialogBuilder(ctx, R.style.App_Dialog_NoDim)
            .setTitle("Proc")
            .setView(container)
            .setPositiveButton(R.string.fapps_info_dialog_positive_btn) { d, _ -> d.dismiss() }
            .setNegativeButton(R.string.dns_info_neutral) { _, _ ->
                copyToClipboard("proc_analysis", clipText)
                showToastUiCentered(ctx, getString(R.string.copied_clipboard), Toast.LENGTH_SHORT)
            }
            .setNeutralButton("Refresh", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dialog.dismiss()
                openProcDialog()
            }
        }

        dialog.show()
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
            "RpnProxy"
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

    private fun printSysEnvAndProps() {
        if (!DEBUG) return

        val environmentMap: Map<String, String> = System.getenv()

        for ((key, value) in environmentMap) {
            Logger.d("EnvVariables", "$key = $value")
        }

        val accessAllowed = SecurityManager().checkPropertiesAccess()
        Logger.d("SysProp", "Access allowed? $accessAllowed")
        val prop: List<String> = System.getProperties().map { it.key.toString() }
        for (key in prop) {
            val value = System.getProperty(key)
            Logger.d("SysProp", "$key = $value")
        }

        printAllSysconfValues()
    }

    fun printAllSysconfValues() {
        Logger.d("EnvVariables", "--- STARTING OS SYSCONF DUMP ---")

        // Get all public static fields from OsConstants
        val fields = OsConstants::class.java.declaredFields

        var successCount = 0
        var errorCount = 0

        for (field in fields) {
            // Filter for fields that start with "_SC_" (System Configuration constants)
            if (field.name.startsWith("_SC_") && Modifier.isStatic(field.modifiers)) {
                try {
                    // Ensure the field is accessible and extract its integer value
                    field.isAccessible = true
                    val scConstantId = field.get(null) as Int

                    // Query the system configuration using Os.sysconf
                    val value = Os.sysconf(scConstantId)

                    Logger.i("EnvVariables", "${field.name}: $value")
                    successCount++
                } catch (e: Exception) {
                    // Some constants might not be supported on older kernel versions
                    Logger.w("EnvVariables", "Failed to read ${field.name}: ${e.localizedMessage}")
                    errorCount++
                }
            } else {
                Logger.d("EnvVariables", "Skipping non-sysconf field: ${field.name}")
            }
        }

        Logger.d(
            "EnvVariables",
            "--- DUMP COMPLETE (Success: $successCount, Failed/Unsupported: $errorCount) ---"
        )
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
        val ctx = context ?: return false
        val dir = ctx.filesDir

        val bugReportZip = File(getZipFileName(dir))
        if (bugReportZip.exists() && bugReportZip.length() > 0) {
            return true
        }

        if (isAtleastO()) {
            val tombstoneZip = EnhancedBugReport.getTombstoneZipFile(ctx)
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
        val ctx = context ?: return
        val packageName = ctx.packageName
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
                ctx,
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
        val ctx = context ?: return
        // ensure tombstone logs are added to zip if available
        if (isAtleastO()) {
            io {
                try {
                    EnhancedBugReport.addLogsToZipFile(ctx)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "err adding tombstone to zip: ${e.message}", e)
                }
            }
        }

        // see if bug report files exist
        val dir = ctx.filesDir
        val zipPath = getZipFileName(dir)
        val zipFile = File(zipPath)

        if (!zipFile.exists() || zipFile.length() <= 0) {
            showToastUiCentered(
                ctx,
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
        val ctx = context ?: return
        if (WorkScheduler.isWorkRunning(ctx, WorkScheduler.APP_EXIT_INFO_JOB_TAG))
            return

        workScheduler.scheduleOneTimeWorkForAppExitInfo()
        showBugReportProgressUi()

        val workManager = WorkManager.getInstance(ctx.applicationContext)
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
        val ctx = context ?: return
        showToastUiCentered(
            ctx,
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

    private fun logEvent(type: EventType, msg: String, details: String) {
        io {
            eventLogger.log(type, Severity.LOW, msg, EventSource.UI, true, details)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
