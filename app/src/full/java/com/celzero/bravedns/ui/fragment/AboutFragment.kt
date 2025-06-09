/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.content.ActivityNotFoundException
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.databinding.DialogViewLogsBinding
import com.celzero.bravedns.databinding.DialogWhatsnewBinding
import com.celzero.bravedns.databinding.FragmentAboutBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.scheduler.BugReportZipper.FILE_PROVIDER_NAME
import com.celzero.bravedns.scheduler.BugReportZipper.getZipFileName
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.openAppInfo
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.UIUtils.sendEmailIntent
import com.celzero.bravedns.util.UIUtils.updateHtmlEncodedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import com.celzero.bravedns.util.Utilities.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener, KoinComponent {
    private val b by viewBinding(FragmentAboutBinding::bind)

    private var lastAppExitInfoDialogInvokeTime = INIT_TIME_MS
    private val workScheduler by inject<WorkScheduler>()

    companion object {
        private const val SCHEME_PACKAGE = "package"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        if (isFdroidFlavour()) {
            b.aboutAppUpdate.visibility = View.GONE
        }

        updateVersionInfo()

        updateSponsorInfo()

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
    }

    private fun updateVersionInfo() {
        try {
            val version = getVersionName()
            // take first 7 characters of the version name, as the version has build number
            // appended to it, which is not required for the user to see.
            val slicedVersion = version.slice(0..6)
            b.aboutWhatsNew.text = getString(R.string.about_whats_new, slicedVersion)

            // complete version name along with the source of installation
            val v = getString(R.string.about_version_install_source, version, getDownloadSource())

            val build = VpnController.goBuildVersion(false)
            b.aboutAppVersion.text = "$v\n$build"

        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_UI, "err-version-info; pkg name not found: ${e.message}", e)
        }
    }

    private fun updateSponsorInfo() {
        if (RpnProxyManager.isRpnActive()) {
            b.sponsorInfoUsage.visibility = View.GONE
            b.aboutSponsor.visibility = View.GONE
            return
        }

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
        val days = (timeDiff / (1000 * 60 * 60 * 24)).toDouble()
        val month = days / 30
        val amount = month * (0.60 + 0.20)
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
                    showNoLogDialog()
                }
            }
            b.aboutMail -> {
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
                openUrl(requireContext(), getString(R.string.about_mozilla_alumni_link))
            }
            b.fossImg -> {
                openUrl(requireContext(), getString(R.string.about_foss_link))
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
        }
    }

    private fun openStatsDialog() {
        io {
            val stat = VpnController.getNetStat()
            val formatedStat = UIUtils.formatNetStat(stat)
            val vpnStats = VpnController.vpnStats()
            uiCtx {
                val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
                val builder =
                    MaterialAlertDialogBuilder(requireContext()).setView(dialogBinding.root)
                val lp = WindowManager.LayoutParams()
                val dialog = builder.create()
                dialog.show()
                lp.copyFrom(dialog.window?.attributes)
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT

                dialog.setCancelable(true)
                dialog.window?.attributes = lp

                val heading = dialogBinding.infoRulesDialogRulesTitle
                val okBtn = dialogBinding.infoRulesDialogCancelImg
                val descText = dialogBinding.infoRulesDialogRulesDesc
                dialogBinding.infoRulesDialogRulesIcon.visibility = View.GONE

                heading.text = getString(R.string.stats_title)
                heading.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(requireContext(), R.drawable.ic_log_level),
                    null,
                    null,
                    null
                )

                descText.movementMethod = LinkMovementMethod.getInstance()
                descText.text = formatedStat + vpnStats

                okBtn.setOnClickListener { dialog.dismiss() }

                dialog.show()
            }
        }
    }

    private fun showNoLogDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.about_bug_no_log_dialog_title)
        builder.setMessage(R.string.about_bug_no_log_dialog_message)
        builder.setPositiveButton(getString(R.string.about_bug_no_log_dialog_positive_btn)) { _, _
            ->
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
        binding.desc.text = updateHtmlEncodedText(getString(R.string.whats_new_version_update))
        // replace the version name in the title
        val v = getVersionName().slice(0..6)
        val title = getString(R.string.about_whats_new, v)
        MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setTitle(title)
            .setPositiveButton(getString(R.string.about_dialog_positive_button)) {
                dialogInterface,
                _ ->
                dialogInterface.dismiss()
            }
            .setNeutralButton(getString(R.string.about_dialog_neutral_button)) {
                _: DialogInterface,
                _: Int ->
                sendEmailIntent(requireContext())
            }
            .setCancelable(true)
            .create()
            .show()
    }

    // ref: https://developer.android.com/guide/components/intents-filters
    private fun emailBugReport() {
        try {
            // get the rethink.tombstone file
            val tombstoneFile:File? = EnhancedBugReport.getTombstoneZipFile(requireContext())

            // get the bug_report.zip file
            val dir = requireContext().filesDir
            val file = File(getZipFileName(dir))
            val uri = getFileUri(file) ?: throw Exception("file uri is null")

            // create an intent for sending email with or without multiple attachments
            val emailIntent = if (tombstoneFile != null) {
                Intent(Intent.ACTION_SEND_MULTIPLE)
            } else {
                Intent(Intent.ACTION_SEND)
            }
            emailIntent.type = "text/plain"
            emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
            emailIntent.putExtra(
                Intent.EXTRA_SUBJECT,
                getString(R.string.about_mail_bugreport_subject)
            )

            // attach extra files (either as a list or single file based on availability)
            if (tombstoneFile != null) {
                val tombstoneUri =
                    getFileUri(tombstoneFile) ?: throw Exception("tombstoneUri is null")
                val uriList = arrayListOf<Uri>(uri, tombstoneUri)
                // send multiple attachments
                emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList)
            } else {
                // ensure EXTRA_TEXT is passed correctly as an ArrayList<CharSequence>
                val bugReportText = getString(R.string.about_mail_bugreport_text)
                val bugReportTextList = arrayListOf<CharSequence>(bugReportText)
                emailIntent.putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, bugReportTextList)
                emailIntent.putExtra(Intent.EXTRA_STREAM, uri)
            }
            Logger.i(LOG_TAG_UI, "email with attachment: $uri, ${tombstoneFile?.path}")
            emailIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            emailIntent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            startActivity(
                Intent.createChooser(
                    emailIntent,
                    getString(R.string.about_mail_bugreport_share_title)
                )
            )
        } catch (e: Exception) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.error_loading_log_file),
                Toast.LENGTH_SHORT
            )
            Logger.e(LOG_TAG_UI, "error sending email: ${e.message}", e)
        }
    }

    private fun getFileUri(file: File): Uri? {
        if (isFileAvailable(file)) {
            return FileProvider.getUriForFile(
                requireContext().applicationContext,
                FILE_PROVIDER_NAME,
                file
            )
        }
        return null
    }

    private fun isFileAvailable(file: File): Boolean {
        return file.isFile && file.exists()
    }

    private fun showContributors() {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
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
        descText.text = updateHtmlEncodedText(getString(R.string.contributors_list))

        okBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun promptCrashLogAction() {
        val binding =
            DialogViewLogsBinding.inflate(LayoutInflater.from(requireContext()), null, false)
        val builder = AlertDialog.Builder(requireContext()).setView(binding.root)
        builder.setTitle(getString(R.string.about_bug_report))

        val dir = requireContext().filesDir
        val zipPath = getZipFileName(dir)
        val zipFile =
            try {
                ZipFile(zipPath)
            } catch (ignored: Exception) { // FileNotFound, ZipException
                null
            }

        if (zipFile == null || zipFile.size() <= 0) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.log_file_not_available),
                Toast.LENGTH_SHORT
            )
            return
        }

        io {
            // load only 20k characters to avoid ANR
            val maxLength = 20000
            try {
                val inputString = StringBuilder(maxLength)
                val entries = zipFile.entries()
                val buffer = CharArray(4096) // Read in smaller chunks
                var shouldBreak = false
                while (entries.hasMoreElements() && !shouldBreak) {
                    val entry = entries.nextElement()
                    zipFile.getInputStream(entry).use { inputStream ->
                        val reader = inputStream.bufferedReader()
                        var charsRead: Int
                        while (reader.read(buffer).also { charsRead = it } > 0 && !shouldBreak) {
                            if (charsRead + inputString.length > maxLength) {
                                // add only what we need to reach maxLength
                                inputString.append(buffer, 0, maxLength - inputString.length)
                                shouldBreak = true
                                break
                            } else {
                                inputString.append(buffer, 0, charsRead)
                            }
                        }
                    }
                    if (inputString.length >= maxLength) {
                        break
                    }
                }
                Logger.d(LOG_TAG_UI, "bug report content size: ${inputString.length}, $zipPath, ${zipFile.size()}")
                uiCtx {
                    if (!isAdded) return@uiCtx
                    binding.info.visibility = View.VISIBLE
                    if (inputString.isEmpty()) {
                        binding.logs.text = getString(R.string.error_loading_log_file)
                        return@uiCtx
                    }
                    if (inputString.length > maxLength) {
                        binding.logs.text = inputString.slice(0 until maxLength)
                    } else {
                        binding.logs.text = inputString
                    }
                }
                if (isAtleastO()) {
                    EnhancedBugReport.addLogsToZipFile(requireContext())
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "err loading log files to textview: ${e.message}", e)
                uiCtx {
                    if (!isAdded) return@uiCtx
                    binding.info.visibility = View.GONE
                    binding.logs.text = getString(R.string.error_loading_log_file)
                }
            } finally {
                zipFile.close()
            }

            uiCtx {
                if (!isAdded) return@uiCtx

                binding.progressLayout.visibility = View.GONE
            }
        }

        val width = (resources.displayMetrics.widthPixels * 0.75).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.75).toInt()

        builder.setPositiveButton(getString(R.string.about_bug_report_dialog_positive_btn)) { _, _
            ->
            emailBugReport()
        }
        builder.setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ -> dialog.dismiss() }

        val alert: AlertDialog = builder.create()
        alert.window?.setLayout(width, height)
        alert.show()
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
