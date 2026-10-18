/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.icu.text.CompactDecimalFormat
import androidx.compose.runtime.*
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.ui.compose.home.AppActivityNativeRow
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
    private val iconResolver: ((Context, Int) -> Drawable?)? = null,
    private val onExpandRequested: (AppActivitySummary) -> Unit
) {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_ENTRY = 1

        // cap of child rows kept per app group; summaries above remain exact
        const val MAX_CHILD_ROWS = 20
    }

    private val rows = mutableStateListOf<Any>()
    private var presentationRevision by mutableIntStateOf(0)

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

    fun getItemCount(): Int = rows.size
    @get:JvmName("getNativeItemCount")
    val itemCount: Int get() = rows.size

    @Composable fun GroupAt(index: Int) = Row(index)

    fun getItemViewType(position: Int): Int {
        return if (rows[position] is AppActivitySummary) TYPE_APP else TYPE_ENTRY
    }

    @Composable
    fun Row(position: Int) {
        presentationRevision
        val row = rows[position]
        AppActivityNativeRow(row, row is AppActivitySummary && row.uid in expandedUids,
            showBlockedCount, favIconEnabled, ::formatCount, iconResolver ?: ::resolveIconForUid,
            onGroupClicked = ::onGroupClicked)
    }

    // Observable state replaces RecyclerView's notification protocol. Keeping
    // these local names leaves the existing list/expansion mutations exact.
    private fun notifyDataSetChanged() { presentationRevision++ }
    private fun notifyItemChanged(position: Int) { presentationRevision++ }
    private fun notifyItemRangeInserted(position: Int, count: Int) { presentationRevision++ }
    private fun notifyItemRangeRemoved(position: Int, count: Int) { presentationRevision++ }

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

    fun onGroupClicked(summary: AppActivitySummary) {
            val headerPos = rows.indexOfFirst {
                it is AppActivitySummary && it.uid == summary.uid
            }
            if (headerPos < 0) return

            if (summary.uid in expandedUids) {
                expandedUids.remove(summary.uid)
                removeChildRows(summary.uid)
                return
            }

            expandedUids.add(summary.uid)
            notifyItemChanged(headerPos) // native chevron animation, including cached empty groups
            val cached = childrenByUid[summary.uid]
            if (cached != null) {
                insertChildRows(summary.uid, cached)
            } else {
                notifyItemChanged(headerPos) // chevron only; children arrive async
                onExpandRequested(summary)
            }
        }


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
