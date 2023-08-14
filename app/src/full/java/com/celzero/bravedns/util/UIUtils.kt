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
package com.celzero.bravedns.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.TypedArray
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Html
import android.text.Spanned
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.VpnController
import ipn.Ipn
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.regex.Matcher
import java.util.regex.Pattern

object UiUtils {

    fun getDnsStatus(): Int {
        val status = VpnController.state()

        return if (status.on) {
            when {
                status.connectionState === BraveVPNService.State.NEW -> {
                    // app's starting here, but such a status confuses users
                    // R.string.status_starting
                    R.string.dns_connected
                }
                status.connectionState === BraveVPNService.State.WORKING -> {
                    R.string.dns_connected
                }
                status.connectionState === BraveVPNService.State.APP_ERROR -> {
                    R.string.status_app_error
                }
                status.connectionState === BraveVPNService.State.DNS_ERROR -> {
                    R.string.status_dns_error
                }
                status.connectionState === BraveVPNService.State.DNS_SERVER_DOWN -> {
                    R.string.status_dns_server_down
                }
                status.connectionState === BraveVPNService.State.NO_INTERNET -> {
                    R.string.status_no_internet
                }
                else -> {
                    R.string.status_failing
                }
            }
        } else {
            R.string.rt_filter_parent_selected
        }
    }

    fun getProxyStatusStringRes(statusId: Long): Int {
        return when (statusId) {
            Ipn.TOK -> {
                R.string.dns_connected
            }
            Ipn.TKO -> {
                R.string.status_failing
            }
            Ipn.END -> {
                R.string.rt_filter_parent_selected
            }
            else -> {
                R.string.rt_filter_parent_selected
            }
        }
    }

    fun humanReadableTime(timestamp: Long): String {
        val offSet = TimeZone.getDefault().rawOffset + TimeZone.getDefault().dstSavings
        val now = timestamp - offSet
        return Utilities.convertLongToTime(now, Constants.TIME_FORMAT_1)
    }

    fun formatToRelativeTime(context: Context, timestamp: Long): String {
        val now = System.currentTimeMillis()
        return if (DateUtils.isToday(timestamp)) {
            context.getString(R.string.relative_time_today)
        } else if (isYesterday(Date(timestamp))) {
            context.getString(R.string.relative_time_yesterday)
        } else {
            val d =
                DateUtils.getRelativeTimeSpanString(
                    timestamp,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            d.toString()
        }
    }

    // ref: https://stackoverflow.com/a/3006423
    private fun isYesterday(day: Date): Boolean {
        val c1 = Calendar.getInstance()
        c1.add(Calendar.DAY_OF_YEAR, -1)
        val c2 = Calendar.getInstance()
        c2.time = day
        if (
            c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
        ) {
            return true
        }

        return false
    }

    fun openVpnProfile(context: Context) {
        try {
            val intent =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Intent(Settings.ACTION_VPN_SETTINGS)
                } else {
                    Intent(Constants.ACTION_VPN_SETTINGS_INTENT)
                }
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.vpn_profile_error),
                Toast.LENGTH_SHORT
            )
            Log.w(LoggerConstants.LOG_TAG_VPN, "Failure opening app info: ${e.message}", e)
        }
    }

    fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            context.startActivity(intent)
        } catch (e: Exception) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.intent_launch_error, url),
                Toast.LENGTH_SHORT
            )
            Log.w(LoggerConstants.LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    fun openNetworkSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.private_dns_error),
                Toast.LENGTH_SHORT
            )
            Log.w(
                LoggerConstants.LOG_TAG_VPN,
                "Failure opening network setting screen: ${e.message}",
                e
            )
        }
    }

    fun clipboardCopy(context: Context, s: String, label: String) {
        val clipboard: ClipboardManager? = context.getSystemService()
        val clip = ClipData.newPlainText(label, s)
        clipboard?.setPrimaryClip(clip)
    }

    fun updateHtmlEncodedText(text: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    fun sendEmailIntent(context: Context) {
        val intent =
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse(context.getString(R.string.about_mail_to_string))
                putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.about_mail_to)))
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.about_mail_subject))
            }
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.about_mail_bugreport_share_title)
            )
        )
    }

    fun openAndroidAppInfo(context: Context, packageName: String?) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            context.startActivity(intent)
        } catch (e: Exception) { // ActivityNotFoundException | NullPointerException
            Log.w(LoggerConstants.LOG_TAG_FIREWALL, "Failure calling app info: ${e.message}", e)
            Utilities.showToastUiCentered(
                context,
                context.getString(R.string.ctbs_app_info_not_available_toast),
                Toast.LENGTH_SHORT
            )
        }
    }

    fun fetchColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        val a: TypedArray = context.obtainStyledAttributes(typedValue.data, intArrayOf(attr))
        val color = a.getColor(0, 0)
        a.recycle()
        return color
    }

    fun fetchToggleBtnColors(context: Context, attr: Int): Int {
        val attributeFetch =
            if (attr == R.color.firewallNoRuleToggleBtnTxt) {
                R.attr.firewallNoRuleToggleBtnTxt
            } else if (attr == R.color.firewallNoRuleToggleBtnBg) {
                R.attr.firewallNoRuleToggleBtnBg
            } else if (attr == R.color.firewallBlockToggleBtnTxt) {
                R.attr.firewallBlockToggleBtnTxt
            } else if (attr == R.color.firewallBlockToggleBtnBg) {
                R.attr.firewallBlockToggleBtnBg
            } else if (attr == R.color.firewallWhiteListToggleBtnTxt) {
                R.attr.firewallWhiteListToggleBtnTxt
            } else if (attr == R.color.firewallWhiteListToggleBtnBg) {
                R.attr.firewallWhiteListToggleBtnBg
            } else if (attr == R.color.firewallExcludeToggleBtnBg) {
                R.attr.firewallExcludeToggleBtnBg
            } else if (attr == R.color.firewallExcludeToggleBtnTxt) {
                R.attr.firewallExcludeToggleBtnTxt
            } else if (attr == R.color.defaultToggleBtnBg) {
                R.attr.defaultToggleBtnBg
            } else if (attr == R.color.defaultToggleBtnTxt) {
                R.attr.defaultToggleBtnTxt
            } else if (attr == R.color.accentGood) {
                R.attr.accentGood
            } else if (attr == R.color.accentBad) {
                R.attr.accentBad
            } else {
                R.attr.chipBgColorPositive
            }
        return fetchColor(context, attributeFetch)
    }

    fun fetchFavIcon(context: Context, dnsLog: DnsLog) {
        if (dnsLog.groundedQuery()) return

        if (isDgaDomain(dnsLog.queryStr)) return

        if (DEBUG) Log.d(LoggerConstants.LOG_TAG_UI, "Glide - fetchFavIcon():${dnsLog.queryStr}")

        // fetch fav icon in background using glide
        FavIconDownloader(context, dnsLog.queryStr).run()
    }

    // check if the domain is generated by a DGA (Domain Generation Algorithm)
    private fun isDgaDomain(fqdn: String): Boolean {
        // dnsleaktest.com fqdn's are auto-generated
        if (fqdn.contains(DnsLogTracker.DNS_LEAK_TEST)) return true

        // fqdn's which has uuids are auto-generated
        return containsUuid(fqdn)
    }

    private fun containsUuid(fqdn: String): Boolean {
        // ref: https://stackoverflow.com/a/39611414
        val regex = "\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}"
        val pattern: Pattern = Pattern.compile(regex)
        val matcher: Matcher = pattern.matcher(fqdn)
        return matcher.find()
    }

    fun getCountryNameFromFlag(flag: String?): String {
        if (flag == null) return ""

        val flagCodePoints =
            mapOf(
                "🇦🇨" to "Ascension Island",
                "🇦🇩" to "Andorra",
                "🇦🇪" to "United Arab Emirates",
                "🇦🇫" to "Afghanistan",
                "🇦🇬" to "Antigua & Barbuda",
                "🇦🇮" to "Anguilla",
                "🇦🇱" to "Albania",
                "🇦🇲" to "Armenia",
                "🇦🇴" to "Angola",
                "🇦🇶" to "Antarctica",
                "🇦🇷" to "Argentina",
                "🇦🇸" to "American Samoa",
                "🇦🇹" to "Austria",
                "🇦🇺" to "Australia",
                "🇦🇼" to "Aruba",
                "🇦🇽" to "Åland Islands",
                "🇦🇿" to "Azerbaijan",
                "🇧🇦" to "Bosnia & Herzegovina",
                "🇧🇧" to "Barbados",
                "🇧🇩" to "Bangladesh",
                "🇧🇪" to "Belgium",
                "🇧🇫" to "Burkina Faso",
                "🇧🇬" to "Bulgaria",
                "🇧🇭" to "Bahrain",
                "🇧🇮" to "Burundi",
                "🇧🇯" to "Benin",
                "🇧🇱" to "St. Barthélemy",
                "🇧🇲" to "Bermuda",
                "🇧🇳" to "Brunei",
                "🇧🇴" to "Bolivia",
                "🇧🇶" to "Caribbean Netherlands",
                "🇧🇷" to "Brazil",
                "🇧🇸" to "Bahamas",
                "🇧🇹" to "Bhutan",
                "🇧🇻" to "Bouvet Island",
                "🇧🇼" to "Botswana",
                "🇧🇾" to "Belarus",
                "🇧🇿" to "Belize",
                "🇨🇦" to "Canada",
                "🇨🇨" to "Cocos (Keeling) Islands",
                "🇨🇩" to "Congo - Kinshasa",
                "🇨🇫" to "Central African Republic",
                "🇨🇬" to "Congo - Brazzaville",
                "🇨🇭" to "Switzerland",
                "🇨🇮" to "Côte d’Ivoire",
                "🇨🇰" to "Cook Islands",
                "🇨🇱" to "Chile",
                "🇨🇲" to "Cameroon",
                "🇨🇳" to "China",
                "🇨🇴" to "Colombia",
                "🇨🇵" to "Clipperton Island",
                "🇨🇷" to "Costa Rica",
                "🇨🇺" to "Cuba",
                "🇨🇻" to "Cape Verde",
                "🇨🇼" to "Curaçao",
                "🇨🇽" to "Christmas Island",
                "🇨🇾" to "Cyprus",
                "🇨🇿" to "Czechia",
                "🇩🇪" to "Germany",
                "🇩🇬" to "Diego Garcia",
                "🇩🇯" to "Djibouti",
                "🇩🇰" to "Denmark",
                "🇩🇲" to "Dominica",
                "🇩🇴" to "Dominican Republic",
                "🇩🇿" to "Algeria",
                "🇪🇦" to "Ceuta & Melilla",
                "🇪🇨" to "Ecuador",
                "🇪🇪" to "Estonia",
                "🇪🇬" to "Egypt",
                "🇪🇭" to "Western Sahara",
                "🇪🇷" to "Eritrea",
                "🇪🇸" to "Spain",
                "🇪🇹" to "Ethiopia",
                "🇪🇺" to "European Union",
                "🇫🇮" to "Finland",
                "🇫🇯" to "Fiji",
                "🇫🇰" to "Falkland Islands",
                "🇫🇲" to "Micronesia",
                "🇫🇴" to "Faroe Islands",
                "🇫🇷" to "France",
                "🇬🇦" to "Gabon",
                "🇬🇧" to "United Kingdom",
                "🇬🇩" to "Grenada",
                "🇬🇪" to "Georgia",
                "🇬🇫" to "French Guiana",
                "🇬🇬" to "Guernsey",
                "🇬🇭" to "Ghana",
                "🇬🇮" to "Gibraltar",
                "🇬🇱" to "Greenland",
                "🇬🇲" to "Gambia",
                "🇬🇳" to "Guinea",
                "🇬🇵" to "Guadeloupe",
                "🇬🇶" to "Equatorial Guinea",
                "🇬🇷" to "Greece",
                "🇬🇸" to "South Georgia & South Sandwich Islands",
                "🇬🇹" to "Guatemala",
                "🇬🇺" to "Guam",
                "🇬🇼" to "Guinea-Bissau",
                "🇬🇾" to "Guyana",
                "🇭🇰" to "Hong Kong SAR China",
                "🇭🇲" to "Heard & McDonald Islands",
                "🇭🇳" to "Honduras",
                "🇭🇷" to "Croatia",
                "🇭🇹" to "Haiti",
                "🇭🇺" to "Hungary",
                "🇮🇨" to "Canary Islands",
                "🇮🇩" to "Indonesia",
                "🇮🇪" to "Ireland",
                "🇮🇱" to "Israel",
                "🇮🇲" to "Isle of Man",
                "🇮🇳" to "India",
                "🇮🇴" to "British Indian Ocean Territory",
                "🇮🇶" to "Iraq",
                "🇮🇷" to "Iran",
                "🇮🇸" to "Iceland",
                "🇮🇹" to "Italy",
                "🇯🇪" to "Jersey",
                "🇯🇲" to "Jamaica",
                "🇯🇴" to "Jordan",
                "🇯🇵" to "Japan",
                "🇰🇪" to "Kenya",
                "🇰🇬" to "Kyrgyzstan",
                "🇰🇭" to "Cambodia",
                "🇰🇮" to "Kiribati",
                "🇰🇲" to "Comoros",
                "🇰🇳" to "St. Kitts & Nevis",
                "🇰🇵" to "North Korea",
                "🇰🇷" to "South Korea",
                "🇰🇼" to "Kuwait",
                "🇰🇾" to "Cayman Islands",
                "🇰🇿" to "Kazakhstan",
                "🇱🇦" to "Laos",
                "🇱🇧" to "Lebanon",
                "🇱🇨" to "St. Lucia",
                "🇱🇮" to "Liechtenstein",
                "🇱🇰" to "Sri Lanka",
                "🇱🇷" to "Liberia",
                "🇱🇸" to "Lesotho",
                "🇱🇹" to "Lithuania",
                "🇱🇺" to "Luxembourg",
                "🇱🇻" to "Latvia",
                "🇱🇾" to "Libya",
                "🇲🇦" to "Morocco",
                "🇲🇨" to "Monaco",
                "🇲🇩" to "Moldova",
                "🇲🇪" to "Montenegro",
                "🇲🇫" to "St. Martin",
                "🇲🇬" to "Madagascar",
                "🇲🇭" to "Marshall Islands",
                "🇲🇰" to "North Macedonia",
                "🇲🇱" to "Mali",
                "🇲🇲" to "Myanmar (Burma)",
                "🇲🇳" to "Mongolia",
                "🇲🇴" to "Macao SAR China",
                "🇲🇵" to "Northern Mariana Islands",
                "🇲🇶" to "Martinique",
                "🇲🇷" to "Mauritania",
                "🇲🇸" to "Montserrat",
                "🇲🇹" to "Malta",
                "🇲🇺" to "Mauritius",
                "🇲🇻" to "Maldives",
                "🇲🇼" to "Malawi",
                "🇲🇽" to "Mexico",
                "🇲🇾" to "Malaysia",
                "🇲🇿" to "Mozambique",
                "🇳🇦" to "Namibia",
                "🇳🇨" to "New Caledonia",
                "🇳🇪" to "Niger",
                "🇳🇫" to "Norfolk Island",
                "🇳🇬" to "Nigeria",
                "🇳🇮" to "Nicaragua",
                "🇳🇱" to "Netherlands",
                "🇳🇴" to "Norway",
                "🇳🇵" to "Nepal",
                "🇳🇷" to "Nauru",
                "🇳🇺" to "Niue",
                "🇳🇿" to "New Zealand",
                "🇴🇲" to "Oman",
                "🇵🇦" to "Panama",
                "🇵🇪" to "Peru",
                "🇵🇫" to "French Polynesia",
                "🇵🇬" to "Papua New Guinea",
                "🇵🇭" to "Philippines",
                "🇵🇰" to "Pakistan",
                "🇵🇱" to "Poland",
                "🇵🇲" to "St. Pierre & Miquelon",
                "🇵🇳" to "Pitcairn Islands",
                "🇵🇷" to "Puerto Rico",
                "🇵🇸" to "Palestinian Territories",
                "🇵🇹" to "Portugal",
                "🇵🇼" to "Palau",
                "🇵🇾" to "Paraguay",
                "🇶🇦" to "Qatar",
                "🇷🇪" to "Réunion",
                "🇷🇴" to "Romania",
                "🇷🇸" to "Serbia",
                "🇷🇺" to "Russia",
                "🇷🇼" to "Rwanda",
                "🇸🇦" to "Saudi Arabia",
                "🇸🇧" to "Solomon Islands",
                "🇸🇨" to "Seychelles",
                "🇸🇩" to "Sudan",
                "🇸🇪" to "Sweden",
                "🇸🇬" to "Singapore",
                "🇸🇭" to "St. Helena",
                "🇸🇮" to "Slovenia",
                "🇸🇯" to "Svalbard & Jan Mayen",
                "🇸🇰" to "Slovakia",
                "🇸🇱" to "Sierra Leone",
                "🇸🇲" to "San Marino",
                "🇸🇳" to "Senegal",
                "🇸🇴" to "Somalia",
                "🇸🇷" to "Suriname",
                "🇸🇸" to "South Sudan",
                "🇸🇹" to "São Tomé & Príncipe",
                "🇸🇻" to "El Salvador",
                "🇸🇽" to "Sint Maarten",
                "🇸🇾" to "Syria",
                "🇸🇿" to "Eswatini",
                "🇹🇦" to "Tristan da Cunha",
                "🇹🇨" to "Turks & Caicos Islands",
                "🇹🇩" to "Chad",
                "🇹🇫" to "French Southern Territories",
                "🇹🇬" to "Togo",
                "🇹🇭" to "Thailand",
                "🇹🇯" to "Tajikistan",
                "🇹🇰" to "Tokelau",
                "🇹🇱" to "Timor-Leste",
                "🇹🇲" to "Turkmenistan",
                "🇹🇳" to "Tunisia",
                "🇹🇴" to "Tonga",
                "🇹🇷" to "Turkey",
                "🇹🇹" to "Trinidad & Tobago",
                "🇹🇻" to "Tuvalu",
                "🇹🇼" to "Taiwan",
                "🇹🇿" to "Tanzania",
                "🇺🇦" to "Ukraine",
                "🇺🇬" to "Uganda",
                "🇺🇲" to "U.S. Outlying Islands",
                "🇺🇳" to "United Nations",
                "🇺🇸" to "United States",
                "🇺🇾" to "Uruguay",
                "🇺🇿" to "Uzbekistan",
                "🇻🇦" to "Vatican City",
                "🇻🇨" to "St. Vincent & Grenadines",
                "🇻🇪" to "Venezuela",
                "🇻🇬" to "British Virgin Islands",
                "🇻🇮" to "U.S. Virgin Islands",
                "🇻🇳" to "Vietnam",
                "🇻🇺" to "Vanuatu",
                "🇼🇫" to "Wallis & Futuna",
                "🇼🇸" to "Samoa",
                "🇽🇰" to "Kosovo",
                "🇾🇪" to "Yemen",
                "🇾🇹" to "Mayotte",
                "🇿🇦" to "South Africa",
                "🇿🇲" to "Zambia",
                "🇿🇼" to "Zimbabwe"
            )
        return flagCodePoints[flag] ?: "Unknown"
    }
}
