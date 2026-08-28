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

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppActivityRow
import com.celzero.bravedns.databinding.ItemLogActivityAppBinding
import com.celzero.bravedns.databinding.ItemLogActivityConnBinding
import com.celzero.bravedns.util.UIUtils

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

/** One connection/dns-log row rendered under an expanded app group. */
data class AppActivityEntry(
    val label: String,
    val timeLabel: String,
    val blocked: Boolean,
    val timestampMs: Long
)

/**
 * Two-level list for the activity detail sheet: collapsed app groups show a
 * summary (total / allowed / blocked); tapping a group expands it to its
 * recent connection rows. Children are fetched on demand via
 * [onExpandRequested]; collapsing needs no data access.
 */
class AppActivityAdapter(
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

    /**
     * Toggles the list between all apps and blocked-only apps. Collapses any
     * expanded group so the filtered list starts from a clean, predictable
     * state.
     */
    fun setBlockedOnly(blockedOnly: Boolean) {
        if (this.blockedOnly == blockedOnly) return
        this.blockedOnly = blockedOnly
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

    private fun visibleEntries(uid: Int): List<AppActivityEntry> {
        val entries = childrenByUid[uid] ?: return emptyList()
        return if (blockedOnly) entries.filter { it.blocked } else entries
    }

    private fun insertChildRows(uid: Int, entries: List<AppActivityEntry>) {
        val headerPos = rows.indexOfFirst { it is AppActivitySummary && it.uid == uid }
        if (headerPos < 0 || entries.isEmpty()) return
        // defensive: drop any stale child rows already present after the header
        removeStaleChildRows(headerPos)

        val toInsert = if (blockedOnly) entries.filter { it.blocked } else entries
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
            val name = summary.appName.ifBlank { ctx.getString(R.string.log_activity_unknown_app) }

            // avatar pill follows the active theme's accent at low alpha so
            // it stays legible on light and dark surfaces alike
            val accent = UIUtils.fetchColor(ctx, R.attr.accentGood)
            val avatarBg = b.laaAvatar.background?.mutate()
            avatarBg?.setTint(ColorUtils.setAlphaComponent(accent, 0x1A))
            b.laaAvatar.background = avatarBg
            b.laaAvatar.text = name.take(1).uppercase()
            b.laaName.text = name
            b.laaConnCount.text =
                ctx.getString(R.string.log_activity_conn_count, summary.total)

            b.laaAllowedCount.text = formatCount(summary.allowed)
            b.laaBlockedCount.text = formatCount(summary.blocked)
            b.laaBlockedCount.visibility =
                if (summary.blocked > 0) View.VISIBLE else View.GONE
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

        private fun formatCount(n: Long): String {
            return if (n > 999) "999+" else n.toString()
        }
    }

    inner class EntryVH(private val b: ItemLogActivityConnBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(entry: AppActivityEntry) {
            // resolve accents through the theme (accentBad/accentGood map to
            // per-theme error/primary colors, e.g. burnt orange in White)
            val attr = if (entry.blocked) R.attr.accentBad else R.attr.accentGood
            b.lacStatusDot.backgroundTintList =
                ColorStateList.valueOf(UIUtils.fetchColor(b.root.context, attr))
            b.lacLabel.text = entry.label
            b.lacTime.text = entry.timeLabel
        }
    }
}
