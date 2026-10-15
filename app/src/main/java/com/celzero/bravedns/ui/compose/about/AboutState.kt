/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.about

import android.content.Context
import android.view.View
import androidx.compose.runtime.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

/** Observable presentation only; controllers retain all account, profile and export work. */
class AboutState(context: Context) {
    var tokenSingleTap: () -> Unit = {}
    var tokenDoubleTap: () -> Unit = {}
    val fhsTitleRethink = WgControlState(context).apply { text = context.getText(R.string.app_name_small_case); isAllCaps = true }
    val sponsorInfoUsage = WgControlState(context)
    val aboutSponsor = WgControlState(context).apply { text = context.getText(R.string.about_sponsor_link_text); imageResource = R.drawable.ic_heart_accent }
    val aboutManageRpn = WgControlState(context).apply { text = "Manage RPN"; visibility = View.GONE; imageResource = R.drawable.ic_rethink_plus }
    val aboutCommunitySponsor = WgControlState(context).apply { visibility = View.GONE }
    val aboutSponsorSince = WgControlState(context)
    val aboutSponsorAgain = WgControlState(context).apply { text = context.getText(R.string.sponsor_about_again) }
    val aboutTelegram = WgControlState(context).apply { text = context.getText(R.string.about_join_telegram); imageResource = R.drawable.ic_telegram }
    val progressLayout = WgControlState(context).apply { visibility = View.GONE }
    val aboutCrashLog = WgControlState(context).apply { text = context.getText(R.string.about_bug_report); imageResource = R.drawable.ic_android_icon }
    val aboutAppUpdate = WgControlState(context).apply { text = context.getText(R.string.about_app_update_check); imageResource = R.drawable.ic_update }
    val aboutAppContributors = WgControlState(context).apply { text = context.getText(R.string.about_app_contributors); imageResource = R.drawable.ic_authors }
    val aboutAppTranslate = WgControlState(context).apply { text = context.getText(R.string.about_app_translate); imageResource = R.drawable.ic_translate }
    val aboutWebsite = WgControlState(context).apply { text = context.getText(R.string.about_website); imageResource = R.drawable.ic_website }
    val aboutGithub = WgControlState(context).apply { text = context.getText(R.string.about_github); imageResource = R.drawable.ic_github }
    val aboutFaq = WgControlState(context).apply { text = context.getText(R.string.about_faq); imageResource = R.drawable.ic_faq }
    val aboutBlog = WgControlState(context).apply { text = context.getText(R.string.about_docs); imageResource = R.drawable.ic_blog }
    val aboutPrivacyPolicy = WgControlState(context).apply { text = context.getText(R.string.about_privacy_policy); imageResource = R.drawable.ic_privacy_policy }
    val aboutTermsOfService = WgControlState(context).apply { text = context.getText(R.string.about_terms_of_service); imageResource = R.drawable.ic_terms_service }
    val aboutTwitter = WgControlState(context).apply { text = context.getText(R.string.about_twitter); imageResource = R.drawable.ic_twitter }
    val aboutMail = WgControlState(context).apply { text = context.getText(R.string.about_email); imageResource = R.drawable.ic_mail }
    val aboutReddit = WgControlState(context).apply { text = context.getText(R.string.lbl_reddit); imageResource = R.drawable.ic_reddit }
    val aboutElement = WgControlState(context).apply { text = context.getText(R.string.lbl_matrix); imageResource = R.drawable.ic_element }
    val aboutMastodon = WgControlState(context).apply { text = context.getText(R.string.lbl_mastodon); imageResource = R.drawable.ic_mastodon }
    val aboutAppInfo = WgControlState(context).apply { text = context.getText(R.string.about_settings_app_info); imageResource = R.drawable.ic_app_info }
    val aboutVpnProfile = WgControlState(context).apply { text = context.getText(R.string.about_settings_vpn_profile); imageResource = R.drawable.ic_about_key }
    val aboutAppNotification = WgControlState(context).apply { text = context.getText(R.string.about_settings_notification); imageResource = R.drawable.ic_notification }
    val aboutStats = WgControlState(context).apply { text = context.getText(R.string.title_statistics); imageResource = R.drawable.ic_log_level }
    val aboutProc = WgControlState(context).apply { text = context.getText(R.string.title_proc); imageResource = R.drawable.ic_proc }
    val aboutStackTrace = WgControlState(context).apply { text = "Stacktrace"; imageResource = R.drawable.ic_stack_trace }
    val aboutMemoryProfile = WgControlState(context).apply { text = "Profile Memory"; imageResource = R.drawable.ic_profile_memory }
    val aboutDbStats = WgControlState(context).apply { text = context.getText(R.string.title_database_dump); imageResource = R.drawable.ic_backup }
    val aboutConsoleLogs = WgControlState(context).apply { text = context.getText(R.string.console_log_title); imageResource = R.drawable.ic_app_log }
    val aboutEventLogs = WgControlState(context).apply { text = context.getText(R.string.event_logs_title); imageResource = R.drawable.ic_event_note }
    val mozillaImg = WgControlState(context).apply { imageResource = R.drawable.mozilla }
    val fossImg = WgControlState(context).apply { imageResource = R.drawable.foss_logo }
    val flossFundsImg = WgControlState(context).apply { imageResource = R.drawable.ic_floss_fund_badge }
    val aboutAppVersion = WgControlState(context)
    val tokenTextView = WgControlState(context)
    val aboutLicense = WgControlState(context).apply { text = context.getText(R.string.about_license) }
}
