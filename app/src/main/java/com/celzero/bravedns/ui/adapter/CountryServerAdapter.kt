package com.celzero.bravedns.ui.adapter

import com.celzero.bravedns.ui.compose.controls.*

import androidx.compose.ui.platform.LocalLocale

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import java.util.Locale
import android.view.animation.AccelerateDecelerateInterpolator
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import android.graphics.Typeface

/** Native presenter; the original grouping and callback contract is retained. */
class CountryServerAdapter(countries: List<CountryItem>, private val listener: CitySelectionListener) {
    interface CitySelectionListener {
        fun onCitySelected(server: CountryConfig, isEnabled: Boolean)
        /**
         * Called when a server item is tapped while the proxy is stopped.
         * The host should open the settings sheet so the user can restart the proxy.
         */
        fun onProxyStoppedItemTapped()
        /**
         * Called when the user taps the star icon on a country card.
         * [countryCode] identifies the country;
         * [countryName] is provided for user feedback purposes;
         * [isFavourite] is the new desired state;
         */
        fun onFavouriteToggled(countryCode: String, countryName: String, isFavourite: Boolean)
    }

    /**
     * Represents a merged group of servers sharing the same key.
     * Multiple servers with same key are grouped and shown as one selectable item.
     */
    data class ServerGroup(
        val key: String, // Common server key
        val servers: List<CountryConfig>, // All servers with this key
        val city: String, // Primary city name
        val avgLoad: Int, // Average load across servers
        val avgLink: Int, // Average link speed across servers (in Mbps)
        val isEnabled: Boolean // Selection state
    ) {
        val serverCount: Int get() = servers.size
    }

    data class CountryItem(
        val countryCode: String,
        val countryName: String,
        val flagEmoji: String,
        val serverGroups: List<ServerGroup>,  // Changed from cities to serverGroups
        var isFavourite: Boolean = false
    )


    private var countries by mutableStateOf(sortCountries(countries), neverEqualPolicy())
    private val expandedCountries = mutableStateMapOf<String, Boolean>()
    private var proxyStoppedValue by mutableStateOf(false)
    fun setProxyStopped(stopped: Boolean) { if (proxyStoppedValue != stopped) proxyStoppedValue = stopped }
    fun updateCountries(newCountries: List<CountryItem>) {
        countries = sortCountries(newCountries)
        val newCodes = countries.map { it.countryCode }.toSet()
        expandedCountries.keys.retainAll(newCodes)
    }
    private fun sortCountries(list: List<CountryItem>): List<CountryItem> = list.sortedBy { it.countryName.lowercase(Locale.getDefault()) }
    private fun sectionLetter(name: String): String {
        val c = name.trim().uppercase(Locale.getDefault()).firstOrNull() ?: return "#"
        return if (c.isLetter()) c.toString() else "#"
    }
    @Composable
    fun Content(modifier: Modifier = Modifier) {
        Column(modifier) {
            var previous: String? = null
            countries.forEach { item ->
                val letter = sectionLetter(item.countryName)
                if (letter != previous) Text(letter,
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
                    color = themeColor(R.attr.primaryLightColorText),
                    style = countryTextStyle(11.sp, family = "sans-serif-medium", bold = true, spacing = .1f))
                previous = letter
                key(item.countryCode) { Country(item) }
            }
        }
    }
    @Composable
    private fun Country(item: CountryItem) {
        val expanded = expandedCountries[item.countryCode] == true
        val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "country expansion")
        val star = remember { RpnControlState() }
        // list_item_country_card is a plain section, with a circular avatar and an inset divider.
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expandedCountries[item.countryCode] = !expanded }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.size(36.dp).background(themeColor(R.attr.colorSurfaceVariant), CircleShape).padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(item.flagEmoji, maxLines = 1, style = countryTextStyle(12.sp, includeFontPadding = false))
                    Text(item.countryCode.uppercase(LocalLocale.current.platformLocale), Modifier.fillMaxWidth(),
                        color = themeColor(R.attr.primaryLightColorText), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = countryTextStyle(rpnDimensionSp(R.dimen.mini_font_text_view), includeFontPadding = false, bold = true, spacing = .02f)
                            .copy(textAlign = TextAlign.Center))
                }
                Column(Modifier.weight(1f).padding(start = 13.dp, end = 10.dp)) {
                    Text(item.countryName, Modifier.fillMaxWidth(), color = themeColor(R.attr.primaryTextColor),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = countryTextStyle(rpnDimensionSp(R.dimen.extra_large_font_text_view)))
                    val total = item.serverGroups.sumOf { it.serverCount }
                    Text(pluralStringResource(R.plurals.server_count, total, total), Modifier.padding(top = 2.dp),
                        color = themeColor(R.attr.primaryLightColorText), style = countryTextStyle(11.sp))
                }
                IconButton({
                    val next = !item.isFavourite
                    star.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120).setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                        star.animate().scaleX(1f).scaleY(1f).setDuration(100).setInterpolator(AccelerateDecelerateInterpolator()).start()
                    }.start()
                    item.isFavourite = next
                    countries = countries.toList()
                    listener.onFavouriteToggled(item.countryCode, item.countryName, next)
                }, Modifier.padding(horizontal = 8.dp).size(48.dp)) { Image(painterResource(if (item.isFavourite) R.drawable.ic_star_filled else R.drawable.ic_star_outline), stringResource(R.string.server_selection_filter_favourites_only), Modifier.size(32.dp).rpnMotion(star)) }
                Icon(painterResource(R.drawable.ic_expand_more_24), stringResource(R.string.server_selection_filter_locations),
                    Modifier.padding(horizontal = 8.dp).size(24.dp).padding(2.dp).rotate(arrow), tint = themeColor(R.attr.primaryLightColorText))
            }
            HorizontalDivider(Modifier.padding(start = 53.dp), thickness = 1.dp, color = themeColor(R.attr.border))
            if (expanded) Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 6.dp)) {
                item.serverGroups.forEach { group -> key(group.key) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                        if (proxyStoppedValue) listener.onProxyStoppedItemTapped() else listener.onCitySelected(group.servers.first(), !group.isEnabled)
                    }.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.city.capitalizeWords(), color = themeColor(R.attr.primaryTextColor),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = countryTextStyle(15.sp, family = "sans-serif-medium"))
                            if (group.serverCount > 1) Text(pluralStringResource(R.plurals.server_count, group.serverCount, group.serverCount),
                                Modifier.padding(top = 2.dp), color = themeColor(R.attr.primaryLightColorText), style = countryTextStyle(12.sp))
                        }
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (group.avgLoad > 0) {
                                val (label, attr) = loadInfo(group.avgLoad)
                                Box(Modifier.padding(end = 5.dp).size(7.dp).background(themeColor(attr), CircleShape))
                                Text(label, Modifier.padding(end = 8.dp), color = themeColor(attr),
                                    style = countryTextStyle(12.sp, family = "sans-serif-medium"))
                            }
                            if (group.avgLink > 0) {
                                val (label, attr) = speedInfo(group.avgLink)
                                com.celzero.bravedns.ui.compose.controls.OriginalLabel("item_server_group:chip_link_speed",
                                    Modifier.padding(end = 12.dp), containerColor = themeColor(if (group.avgLink >= 10_000) attr else R.attr.chipColorBgNormal)) { Text(label) }
                            }
                            OriginalCheckbox("item_server_group:checkbox_server", group.isEnabled, null,
                                Modifier.size(28.dp).alpha(if (proxyStoppedValue) .4f else 1f), enabled = !proxyStoppedValue)
                        }
                    }
                } }
            }
        }
    }

    @Composable private fun countryTextStyle(size: androidx.compose.ui.unit.TextUnit, includeFontPadding: Boolean = true,
        family: String? = null, bold: Boolean = false, spacing: Float? = null) = utilityTextStyle(
        size = size.value, family = family, weight = if (bold) Typeface.BOLD else null, spacing = spacing)
        .copy(platformStyle = PlatformTextStyle(includeFontPadding = includeFontPadding))

    private fun speedInfo(linkMbps: Int): Pair<String, Int> {
        val formatted: String
        val attr: Int

        when {
            linkMbps >= 10_000 -> {
                val gbps = linkMbps / 1_000.0
                formatted = String.format(Locale.US, "%.0f Gbps", gbps)
                attr = R.attr.chipTextPositive
            }
            linkMbps >= 1_000 -> {
                val gbps = linkMbps / 1_000.0
                formatted = if (gbps == gbps.toLong().toDouble())
                    String.format(Locale.US, "%.0f Gbps", gbps)
                else
                    String.format(Locale.US, "%.1f Gbps", gbps)
                attr = R.attr.accentGood
            }
            linkMbps >= 100 -> {
                formatted = "$linkMbps Mbps"
                attr = R.attr.chipTextNeutral
            }
            linkMbps >= 10 -> {
                formatted = "$linkMbps Mbps"
                attr = R.attr.chipTextNeutral
            }
            else -> {
                formatted = "$linkMbps Mbps"
                attr = R.attr.chipTextNegative
            }
        }
        return Pair(formatted, attr)
    }

    /**
     * Returns (displayText, textColorAttr) for [loadPercent].
     *
     * Tier thresholds:
     *   ≤ 20 → Light
     *   ≤ 40 → Normal
     *   ≤ 60 → Busy
     *   ≤ 80 → Very Busy
     *   > 80 → Overloaded
     */
    private fun loadInfo(loadPercent: Int): Pair<String, Int> {
        val attr: Int

        when {
            loadPercent <= 20 -> {
                attr  = R.attr.chipTextPositive
            }
            loadPercent <= 40 -> {
                attr  = R.attr.accentGood
            }
            loadPercent <= 60 -> {
                attr  = R.attr.chipTextNeutral
            }
            loadPercent <= 80 -> {
                attr  = R.attr.chipTextNegative
            }
            else -> {
                attr  = R.attr.chipTextNegative
            }
        }
        val label = "$loadPercent%"
        return Pair(label, attr)
    }
}
