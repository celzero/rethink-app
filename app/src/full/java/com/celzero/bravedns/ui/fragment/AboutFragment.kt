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
import com.celzero.bravedns.scheduler.BugReportZipper.FILE_PROVIDER_NAME
import com.celzero.bravedns.scheduler.BugReportZipper.getZipFileName
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.UIUtils.openAppInfo
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
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener, KoinComponent {
    private val b by viewBinding(FragmentAboutBinding::bind)

    private var lastAppExitInfoDialogInvokeTime = INIT_TIME_MS
    private val workScheduler by inject<WorkScheduler>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {

        if (isFdroidFlavour()) {
            b.aboutAppUpdate.visibility = View.GONE
        }

        b.aboutSponsor.setOnClickListener(this)
        b.aboutWebsite.setOnClickListener(this)
        b.aboutTwitter.setOnClickListener(this)
        b.aboutGithub.setOnClickListener(this)
        b.aboutBlog.setOnClickListener(this)
        b.aboutPrivacyPolicy.setOnClickListener(this)
        b.aboutMail.setOnClickListener(this)
        b.aboutTelegram.setOnClickListener(this)
        b.aboutFaq.setOnClickListener(this)
        b.mozillaImg.setOnClickListener(this)
        b.fossImg.setOnClickListener(this)
        b.osomImg.setOnClickListener(this)
        b.aboutAppUpdate.setOnClickListener(this)
        b.aboutWhatsNew.setOnClickListener(this)
        b.aboutAppInfo.setOnClickListener(this)
        b.aboutAppNotification.setOnClickListener(this)
        b.aboutVpnProfile.setOnClickListener(this)
        b.aboutCrashLog.setOnClickListener(this)
        b.aboutAppVersion.setOnClickListener(this)
        b.aboutAppContributors.setOnClickListener(this)
        b.aboutAppTranslate.setOnClickListener(this)

        try {
            val version = getVersionName() ?: ""
            // take first 7 characters of the version name, as the version has build number
            // appended to it, which is not required for the user to see.
            val slicedVersion = version.slice(0..6) ?: ""
            b.aboutWhatsNew.text = getString(R.string.about_whats_new, slicedVersion)
            // show the complete version name along with the source of installation
            b.aboutAppVersion.text =
                getString(R.string.about_version_install_source, version, getDownloadSource())
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.w(LOG_TAG_UI, "package name not found: ${e.message}", e)
        }
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(
                requireContext().packageManager,
                requireContext().packageName
            )
        return pInfo?.versionName ?: ""
    }

    private fun getDownloadSource(): String {
        if (isFdroidFlavour()) return getString(R.string.build__flavor_fdroid)

        if (isPlayStoreFlavour()) return getString(R.string.build__flavor_play_store)

        return getString(R.string.build__flavor_website)
    }

    override fun onClick(view: View?) {
        when (view) {
            b.aboutTelegram -> {
                openActionViewIntent(getString(R.string.about_telegram_link).toUri())
            }
            b.aboutBlog -> {
                openActionViewIntent(getString(R.string.about_docs_link).toUri())
            }
            b.aboutFaq -> {
                openActionViewIntent(getString(R.string.about_faq_link).toUri())
            }
            b.aboutGithub -> {
                openActionViewIntent(getString(R.string.about_github_link).toUri())
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
                openActionViewIntent(getString(R.string.about_twitter_handle).toUri())
            }
            b.aboutWebsite -> {
                openActionViewIntent(getString(R.string.about_website_link).toUri())
            }
            b.aboutSponsor -> {
                openActionViewIntent(RETHINKDNS_SPONSOR_LINK.toUri())
            }
            b.mozillaImg -> {
                openActionViewIntent(getString(R.string.about_mozilla_alumni_link).toUri())
            }
            b.fossImg -> {
                openActionViewIntent(getString(R.string.about_foss_link).toUri())
            }
            b.osomImg -> {
                openActionViewIntent(getString(R.string.about_osom_link).toUri())
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
                openActionViewIntent(getString(R.string.about_translate_link).toUri())
            }
            b.aboutPrivacyPolicy -> {
                openActionViewIntent(getString(R.string.about_privacy_policy_link).toUri())
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

    private fun openActionViewIntent(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.intent_launch_error, intent.data),
                Toast.LENGTH_SHORT
            )
            Logger.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
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
                intent.data = Uri.parse("package:$packageName")
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
        val emailIntent = Intent(Intent.ACTION_SEND)
        emailIntent.type = "text/plain"
        emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_bugreport_subject))
        emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.about_mail_bugreport_text))
        // Get the bug_report.zip file
        val dir = requireContext().filesDir
        val file = File(getZipFileName(dir))
        val uri = getFileUri(file)
        emailIntent.putExtra(Intent.EXTRA_STREAM, uri)

        emailIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        emailIntent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        startActivity(
            Intent.createChooser(emailIntent, getString(R.string.about_mail_bugreport_share_title))
        )
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
            var fin: FileInputStream? = null
            var zin: ZipInputStream? = null
            // load only 20k characters to avoid ANR
            val maxLength = 20000
            try {
                fin = FileInputStream(zipPath)
                zin = ZipInputStream(fin)
                var ze: ZipEntry?
                var inputString: String? = ""
                // don't load more than 20k characters to avoid ANR
                // TODO: use recycler view instead of textview
                while (zin.nextEntry.also { ze = it } != null) {
                    val inStream = zipFile.getInputStream(ze)
                    inputString += inStream?.bufferedReader().use { it?.readText() }
                    if (inputString?.length!! > maxLength) break
                }
                uiCtx {
                    if (!isAdded) return@uiCtx
                    binding.info.visibility = View.VISIBLE
                    binding.logs.text = inputString?.slice(0 until maxLength)
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "err loading log files to textview: ${e.message}", e)
                uiCtx {
                    if (!isAdded) return@uiCtx
                    binding.info.visibility = View.GONE
                    binding.logs.text = getString(R.string.error_loading_log_file)
                }
            } finally {
                fin?.close()
                zin?.close()
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
