/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.icu.text.CompactDecimalFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppActivityRow
import com.celzero.bravedns.databinding.ItemLogActivityAppBinding
import com.celzero.bravedns.databinding.ItemLogActivityConnBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import java.util.Locale

/**
 * Summary for one app within the selected activity window; children are loaded
 * lazily when the user expands the group.
 */
data class AppActivitySummary(
    val uid: Int,
    val appName: String,
    val total: Long,
    val allowed: Long,
    val blocked: Long
)

/**
 * One aggregated domain rendered under an expanded app group: counts are
 * cumulative for the whole selected window (allowed/blocked), [lastSeenMs]
 * is the most recent connection time for the domain and [flag] is the
 * region emoji recorded on that latest row.
 */
data class AppActivityEntry(
    val label: String,
    val allowed: Long,
    val blocked: Long,
    val lastSeenMs: Long,
    val flag: String
)

/**
 * Two-level list for the activity detail sheet: collapsed app groups show a
 * summary (total / allowed / blocked); tapping a group expands it to its
 * recent connection rows. Children are fetched on demand via
 * [onExpandRequested]; collapsing needs no data access.
 */
class AppActivityAdapter(
    private val favIconEnabled: Boolean,
    private val showBlockedCount: Boolean = true,
    private val onExpandRequested: (AppActivitySummary) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_ENTRY = 1

        // cap of child rows kept per app group; summaries above remain exact
        const val MAX_CHILD_ROWS = 20
    }

    private val rows = mutableListOf<Any>()

    // master list as delivered by submit(); rows[] is the filtered/flattened
    // view built from it
    private var allSummaries: List<AppActivitySummary> = emptyList()

    // insertion-ordered expansion state; uid identifies a group
    private val expandedUids = LinkedHashSet<Int>()
    private val childrenByUid = mutableMapOf<Int, List<AppActivityEntry>>()

    // when true only apps with at least one blocked entry are listed and only
    // blocked children are shown on expand
    private var blockedOnly = false

    fun submit(summaries: List<AppActivitySummary>) {
        allSummaries = summaries
        expandedUids.clear()
        childrenByUid.clear()
        rebuildRows()
    }

    private fun rebuildRows() {
        expandedUids.clear()
        childrenByUid.clear()
        rows.clear()
        val visible =
            if (blockedOnly) allSummaries.filter { it.blocked > 0 } else allSummaries
        rows.addAll(visible)
        notifyDataSetChanged()
    }

    /**
     * Delivers lazily-fetched rows for an app group. Never mutates expansion
     * state: if the group is currently expanded its child rows are inserted,
     * otherwise they are cached for the next expand.
     */
    fun setChildren(uid: Int, entries: List<AppActivityEntry>) {
        val capped = entries.take(MAX_CHILD_ROWS)
        childrenByUid[uid] = capped
        if (uid in expandedUids) {
            insertChildRows(uid, capped)
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return if (rows[position] is AppActivitySummary) TYPE_APP else TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_APP) {
            AppVH(ItemLogActivityAppBinding.inflate(inf, parent, false))
        } else {
            EntryVH(ItemLogActivityConnBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is AppActivitySummary -> (holder as AppVH).bind(row)
            is AppActivityEntry -> (holder as EntryVH).bind(row)
        }
    }

    // compact count rendering shared by the app headers and the child rows;
    // mirrors HomeScreenFragment's fhs card counts (1.5K instead of 999+)
    private fun formatCount(n: Long): String {
        return if (Utilities.isAtleastN()) {
            CompactDecimalFormat.getInstance(
                Locale.US,
                CompactDecimalFormat.CompactStyle.SHORT
            ).format(n)
        } else {
            n.toString()
        }
    }

    private fun visibleEntries(uid: Int): List<AppActivityEntry> {
        val entries = childrenByUid[uid] ?: return emptyList()
        return if (blockedOnly) entries.filter { it.blocked > 0 } else entries
    }

    private fun insertChildRows(uid: Int, entries: List<AppActivityEntry>) {
        val headerPos = rows.indexOfFirst { it is AppActivitySummary && it.uid == uid }
        if (headerPos < 0 || entries.isEmpty()) return
        // defensive: drop any stale child rows already present after the header
        removeStaleChildRows(headerPos)

        val toInsert = if (blockedOnly) entries.filter { it.blocked > 0 } else entries
        if (toInsert.isEmpty()) {
            notifyItemChanged(headerPos) // chevron state only
            return
        }
        rows.addAll(headerPos + 1, toInsert)
        notifyItemRangeInserted(headerPos + 1, toInsert.size)
        notifyItemChanged(headerPos) // chevron state
    }

    private fun removeStaleChildRows(headerPos: Int) {
        var first = -1
        var count = 0
        var i = headerPos + 1
        while (i < rows.size && rows[i] is AppActivityEntry) {
            if (first < 0) first = i
            count++
            i++
        }
        if (count > 0) {
            repeat(count) { rows.removeAt(first) }
        }
    }

    private fun removeChildRows(uid: Int) {
        val headerPos = rows.indexOfFirst { it is AppActivitySummary && it.uid == uid }
        if (headerPos < 0) return
        var first = -1
        var count = 0
        var i = headerPos + 1
        while (i < rows.size && rows[i] is AppActivityEntry) {
            if (first < 0) first = i
            count++
            i++
        }
        if (count > 0) {
            repeat(count) { rows.removeAt(first) }
            notifyItemRangeRemoved(first, count)
        }
        notifyItemChanged(headerPos)
    }

    inner class AppVH(private val b: ItemLogActivityAppBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(summary: AppActivitySummary) {
            val ctx = b.root.context
            val name = summary.appName.ifBlank { ctx.getString(R.string.lbl_unknown) }

            // prefer the real app icon: preventively resolve the package (and
            // its cached icon) from the uid and show it; the initials avatar
            // is only a fallback for uids with no resolvable package/icon
            val icon = resolveIconForUid(ctx, summary.uid)
            if (icon != null) {
                b.laaAppIcon.setImageDrawable(icon)
                b.laaAppIcon.visibility = View.VISIBLE
                b.laaAvatar.visibility = View.GONE
            } else {
                b.laaAppIcon.visibility = View.GONE
                b.laaAvatar.visibility = View.VISIBLE
                // avatar pill follows the active theme's accent at low alpha
                // so it stays legible on light and dark surfaces alike
                val accent = UIUtils.fetchColor(ctx, R.attr.accentGood)
                val avatarBg = b.laaAvatar.background?.mutate()
                avatarBg?.setTint(ColorUtils.setAlphaComponent(accent, 0x1A))
                b.laaAvatar.background = avatarBg
                b.laaAvatar.text = name.take(1).uppercase()
            }
            b.laaName.text = name
            b.laaConnCount.text =
                ctx.getString(R.string.log_activity_conn_count, summary.total)

            b.laaAllowedCount.text = formatCount(summary.allowed)
            b.laaBlockedCount.text = formatCount(summary.blocked)
            b.laaBlockedCount.visibility =
                if (showBlockedCount && summary.blocked > 0) View.VISIBLE else View.GONE
            b.laaAllowedCount.visibility =
                if (summary.allowed > 0) View.VISIBLE else View.GONE

            val isExpanded = summary.uid in expandedUids
            b.laaChevron.animate().cancel()
            b.laaChevron.rotation = if (isExpanded) 180f else 0f

            b.root.setOnClickListener { onGroupClicked(summary) }
        }

        // explicit toggle: expand inserts cached rows or requests a lazy load;
        // collapse always removes rows locally. No path re-triggers a load for
        // an already-expanded group, so repeated taps reliably collapse.
        private fun onGroupClicked(summary: AppActivitySummary) {
            val headerPos = rows.indexOfFirst {
                it is AppActivitySummary && it.uid == summary.uid
            }
            if (headerPos < 0) return

            if (summary.uid in expandedUids) {
                expandedUids.remove(summary.uid)
                removeChildRows(summary.uid)
                b.laaChevron.animate().rotation(0f).setDuration(150).start()
                return
            }

            expandedUids.add(summary.uid)
            b.laaChevron.animate().rotation(180f).setDuration(150).start()
            val cached = childrenByUid[summary.uid]
            if (cached != null) {
                insertChildRows(summary.uid, cached)
            } else {
                notifyItemChanged(headerPos) // chevron only; children arrive async
                onExpandRequested(summary)
            }
        }

        /**
         * Resolves the app icon for a uid: first package of the uid, then its
         * cached icon via [Utilities.getIcon]; falls back to the system
         * default icon, and returns null (initials avatar) when even that
         * fails. Mirrors RpnStatsBottomSheet's TopAppsAdapter behavior.
         */
        private fun resolveIconForUid(context: Context, uid: Int): Drawable? {
            return try {
                context.packageManager.getPackagesForUid(uid)?.firstOrNull()?.let {
                    Utilities.getIcon(context, it)
                } ?: Utilities.getDefaultIcon(context)
            } catch (_: Exception) {
                null
            }
        }
    }

    inner class EntryVH(private val b: ItemLogActivityConnBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(entry: AppActivityEntry) {
            b.lacLabel.text = entry.label
            // counts are separate colored views like the app headers above
            b.lacAllowedCount.text = formatCount(entry.allowed)
            b.lacAllowedCount.visibility =
                if (entry.allowed > 0) View.VISIBLE else View.GONE
            b.lacBlockedCount.text = formatCount(entry.blocked)
            b.lacBlockedCount.visibility =
                if (showBlockedCount && entry.blocked > 0) View.VISIBLE else View.GONE
            b.lacRecent.text = b.root.context.getString(
                R.string.log_activity_domain_recent,
                Utilities.convertLongToTime(entry.lastSeenMs, TIME_FORMAT_1)
            )
            displayIcon(entry)
        }

        private fun displayIcon(entry: AppActivityEntry) {
            b.lacFlag.text = entry.flag
            b.lacFlag.visibility = View.VISIBLE
            b.lacIcon.visibility = View.GONE
            b.lacIcon.setImageDrawable(null)
            if (!favIconEnabled || entry.label.isBlank()) {
                clearFavIcon()
                return
            }

            // skip the glide cache lookup for domains known to have no icon
            val domain = entry.label.dropLastWhile { it == '.' }
            if (FavIconDownloader.isUrlAvailableInFailedCache(domain) != null) {
                return
            }
            displayNextDnsFavIcon(domain)
        }

        private fun displayNextDnsFavIcon(domain: String) {
            // url to check if the icon is cached from nextdns
            val nextDnsUrl = FavIconDownloader.constructFavIcoUrlNextDns(domain)
            // url to check if the icon is cached from duckduckgo
            val duckDuckGoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(domain)
            // subdomain to check if the icon is cached from duckduckgo
            val duckduckgoDomainURL = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(domain)
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                var request = Glide.with(b.root.context.applicationContext)
                    .load(nextDnsUrl)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .transition(withCrossFade(factory))

                val errorRequest = displayDuckduckgoFavIcon(duckDuckGoUrl, duckduckgoDomainURL)
                if (errorRequest != null) {
                    request = request.error(errorRequest)
                }

                request.into(iconTarget())
            } catch (_: Exception) {
                Logger.d(LOG_TAG_DNS, "err loading icon, load flag instead")
                displayDuckduckgoFavIcon(duckDuckGoUrl, duckduckgoDomainURL)?.into(iconTarget())
            }
        }

        /**
         * Loads the fav icons from the glide cache (populated by
         * FavIconDownloader at log time). On failure, keeps the flag visible.
         */
        private fun displayDuckduckgoFavIcon(
            url: String,
            subDomainURL: String
        ): RequestBuilder<Drawable>? {
            return try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                Glide.with(b.root.context.applicationContext)
                    .load(url)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(
                        Glide.with(b.root.context.applicationContext)
                            .load(subDomainURL)
                            .onlyRetrieveFromCache(true)
                    )
                    .transition(withCrossFade(factory))
            } catch (e: Exception) {
                null
            }
        }

        private fun iconTarget(): CustomViewTarget<ImageView, Drawable> {
            return object : CustomViewTarget<ImageView, Drawable>(b.lacIcon) {
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        showFlag()
                        hideFavIcon()
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?
                    ) {
                        hideFlag()
                        showFavIcon(resource)
                    }

                    override fun onResourceCleared(placeholder: Drawable?) {
                        hideFavIcon()
                        showFlag()
                    }
                }
        }

        private fun clearFavIcon() {
            Glide.with(b.root.context.applicationContext).clear(b.lacIcon)
        }

        private fun showFavIcon(drawable: Drawable) {
            b.lacIcon.visibility = View.VISIBLE
            b.lacIcon.setImageDrawable(drawable)
        }

        private fun hideFavIcon() {
            b.lacIcon.visibility = View.GONE
            b.lacIcon.setImageDrawable(null)
        }

        private fun showFlag() {
            b.lacFlag.visibility = View.VISIBLE
        }

        private fun hideFlag() {
            b.lacFlag.visibility = View.GONE
        }
    }
}
