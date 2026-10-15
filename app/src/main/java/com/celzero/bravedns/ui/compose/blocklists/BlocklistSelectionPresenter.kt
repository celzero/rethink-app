package com.celzero.bravedns.ui.compose.blocklists

import android.content.Context
import com.celzero.bravedns.R
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.fragment.RethinkBlocklistFragment
import com.celzero.bravedns.util.UIUtils.openUrl
import kotlinx.coroutines.*

data class BlocklistRow(val key: String, val group: String, val groupName: String, val groupDescription: String, val label: String, val description: String, val level: Int, val selected: () -> Boolean, val onSelected: (Boolean) -> Unit, val entries: String? = null, val onEntries: (() -> Unit)? = null)

/** Original adapter boundary operations. Writes deliberately outlive recycled rows. */
class BlocklistSelectionPresenter(private val context: Context, private val fragment: RethinkBlocklistFragment, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO, private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main) {
    fun localPack(map: LocalBlocklistPacksMap) = BlocklistRow(
        "pack:${map.pack}:${map.level}", map.group, getGroupName(map.group), getTitleDesc(map.group),
        map.pack.replaceFirstChar(Char::titlecase), context.getString(R.string.rsv_blocklist_count_text, map.blocklistIds.size.toString()), map.level,
        { fragment.getSelectedFileTags().containsAll(map.blocklistIds) }, { selected -> setLocalPack(map.blocklistIds.toMutableList(), if (selected) 1 else 0) })
    fun remotePack(map: RemoteBlocklistPacksMap) = BlocklistRow(
        "pack:${map.pack}:${map.level}", map.group, getGroupName(map.group), getTitleDesc(map.group),
        map.pack.replaceFirstChar(Char::titlecase), context.getString(R.string.rsv_blocklist_count_text, map.blocklistIds.size.toString()), map.level,
        { fragment.getSelectedFileTags().containsAll(map.blocklistIds) }, { selected -> setRemotePack(map.blocklistIds.toMutableList(), if (selected) 1 else 0) })
    fun localTag(filetag: RethinkLocalFileTag) = BlocklistRow(
        "tag:${filetag.value}", filetag.group, getGroupName(filetag.group), getTitleDesc(filetag.group), filetag.vname,
        if (filetag.subg.isEmpty()) filetag.group else filetag.subg, filetag.level?.getOrNull(0) ?: 0,
        { filetag.isSelected }, { selected -> setLocalTag(filetag, selected) }, context.getString(R.string.dc_entries, filetag.entries.toString()), { openUrl(context, filetag.url[0]) })
    fun remoteTag(filetag: RethinkRemoteFileTag) = BlocklistRow(
        "tag:${filetag.value}", filetag.group, getGroupName(filetag.group), getTitleDesc(filetag.group), filetag.vname,
        if (filetag.subg.isEmpty()) filetag.group else filetag.subg, filetag.level?.getOrNull(0) ?: 0,
        { filetag.isSelected }, { selected -> setRemoteTag(filetag, selected) }, context.getString(R.string.dc_entries, filetag.entries.toString()), { openUrl(context, filetag.url[0]) })
        private fun setLocalPack(tagIds: MutableList<Int>, selected: Int) {
            io {
                RethinkBlocklistManager.updateFiletagsLocal(tagIds.toSet(), selected)
                val selectedTags = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
                ui {
                    fragment.updateFileTagList(selectedTags)
                }
            }
        }
        private fun setRemotePack(tagIds: MutableList<Int>, selected: Int) {
            io {
                RethinkBlocklistManager.updateFiletagsRemote(tagIds.toSet(), selected)
                val selectedTags = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                ui {
                    fragment.updateFileTagList(selectedTags)
                }
            }
        }
        private fun setLocalTag(filetag: RethinkLocalFileTag, selected: Boolean) {
            io {
                filetag.isSelected = selected
                RethinkBlocklistManager.updateFiletagLocal(filetag)
                val list = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
                // Skip the host-fragment callback once its view is gone; the
                // persistence write above still completes regardless.
                if (fragment.isAdded) {
                    fragment.updateFileTagList(list)
                }
            }
        }
        private fun setRemoteTag(filetag: RethinkRemoteFileTag, selected: Boolean) {
            io {
                filetag.isSelected = selected
                RethinkBlocklistManager.updateFiletagRemote(filetag)
                val list = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                // Skip the host-fragment callback once its view is gone; the
                // persistence write above still completes regardless.
                if (fragment.isAdded) {
                    fragment.updateFileTagList(list)
                }
            }
        }
        private fun getTitleDesc(title: String): String {
            return if (title.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
                context.getString(RethinkBlocklistManager.PARENTAL_CONTROL.desc)
            } else if (title.equals(RethinkBlocklistManager.SECURITY.name, true)) {
                context.getString(RethinkBlocklistManager.SECURITY.desc)
            } else if (title.equals(RethinkBlocklistManager.PRIVACY.name, true)) {
                context.getString(RethinkBlocklistManager.PRIVACY.desc)
            } else {
                ""
            }
        }
        private fun getGroupName(group: String): String {
            return if (group.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
                context.getString(RethinkBlocklistManager.PARENTAL_CONTROL.label)
            } else if (group.equals(RethinkBlocklistManager.SECURITY.name, true)) {
                context.getString(RethinkBlocklistManager.SECURITY.label)
            } else if (group.equals(RethinkBlocklistManager.PRIVACY.name, true)) {
                context.getString(RethinkBlocklistManager.PRIVACY.label)
            } else {
                ""
            }
        }
        private fun io(f: suspend () -> Unit) {
            CoroutineScope(ioDispatcher).launch { f() }
        }
        private fun ui(f: () -> Unit) {
            // Skip UI callbacks once the host fragment's view is gone; the
            // persistence write in io() still completes regardless.
            if (!fragment.isAdded) return
            CoroutineScope(mainDispatcher).launch { f() }
        }
}
