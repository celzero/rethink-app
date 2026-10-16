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
package com.celzero.bravedns.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemBlocklistAppRowBinding
import com.celzero.bravedns.databinding.ListItemBlocklistDomainRowBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlocklistAppsAdapter(
    private val context: Context,
    private val fetchDomains: suspend (uid: Int) -> List<AppConnection>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_APP = 0
        private const val TYPE_DOMAIN = 1

        // chevron orientation for the expand/collapse indicator
        private const val ROTATION_EXPANDED = 180f
        private const val ROTATION_COLLAPSED = 0f
    }

    private data class AppGroup(
        val app: AppConnection,
        var expanded: Boolean = false,
        var domains: List<AppConnection>? = null
    )

    private sealed class Row {
        abstract val groupIndex: Int

        class Header(override val groupIndex: Int) : Row()
        class Domain(override val groupIndex: Int, val domain: AppConnection) : Row()
    }

    private val groups = mutableListOf<AppGroup>()
    private val rows = mutableListOf<Row>()

    // per-uid identity caches; first bind resolves asynchronously, later binds
    // (the common case while scrolling) are fully synchronous
    private val appNameByUid = HashMap<Int, String?>()
    private val appIconByUid = HashMap<Int, Drawable?>()

    fun submitApps(apps: List<AppConnection>) {
        groups.clear()
        groups.addAll(apps.map { AppGroup(it) })
        rebuildRows()
        notifyDataSetChanged()
    }

    private fun rebuildRows() {
        rows.clear()
        groups.forEachIndexed { index, group ->
            rows.add(Row.Header(index))
            if (group.expanded) {
                group.domains?.forEach { rows.add(Row.Domain(index, it)) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is Row.Header -> TYPE_APP
            is Row.Domain -> TYPE_DOMAIN
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_APP -> {
                val b = ListItemBlocklistAppRowBinding.inflate(inflater, parent, false)
                AppViewHolder(b)
            }
            else -> {
                val b = ListItemBlocklistDomainRowBinding.inflate(inflater, parent, false)
                DomainViewHolder(b)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as AppViewHolder).bind(row.groupIndex)
            is Row.Domain -> (holder as DomainViewHolder).bind(row.domain)
        }
    }

    private fun toggle(groupIndex: Int) {
        val group = groups[groupIndex]
        group.expanded = !group.expanded
        if (group.expanded && group.domains == null) {
            // first expansion: fetch the per-app domains off the main thread,
            // then re-render the (still expanded) group
            io {
                val domains = fetchDomains(group.app.uid)
                uiCtx {
                    group.domains = domains
                    rebuildRows()
                    notifyDataSetChanged()
                }
            }
        }
        rebuildRows()
        notifyDataSetChanged()
    }

    inner class AppViewHolder(private val b: ListItemBlocklistAppRowBinding) :
        RecyclerView.ViewHolder(b.root) {

        private var bindSeq: Long = 0L

        fun bind(groupIndex: Int) {
            val seq = ++bindSeq
            val group = groups[groupIndex]
            val app = group.app
            b.blAppName.text = appNameByUid[app.uid] ?: app.appOrDnsName ?: context.getString(
                R.string.network_log_app_name_unnamed,
                app.uid.toString()
            )
            b.blAppCount.text = app.count.toString()
            b.blAppExpand.rotation = if (group.expanded) ROTATION_EXPANDED else ROTATION_COLLAPSED
            resolveAppIdentity(app, seq)
            b.blAppRow.setOnClickListener { toggle(groupIndex) }
        }

        private fun applyIdentity(app: AppConnection, icon: Drawable?, name: String?) {
            b.blAppName.text = name ?: app.appOrDnsName ?: context.getString(
                R.string.network_log_app_name_unnamed,
                app.uid.toString()
            )
            b.blAppIcon.setImageDrawable(icon ?: Utilities.getDefaultIcon(context))
        }

        private fun resolveAppIdentity(app: AppConnection, seq: Long) {
            // paint the cached/default icon synchronously so recycled rows
            // never show a stale app's glyph
            b.blAppIcon.setImageDrawable(
                appIconByUid[app.uid] ?: Utilities.getDefaultIcon(context)
            )
            if (appNameByUid.containsKey(app.uid) && appIconByUid.containsKey(app.uid)) {
                return
            }
            io {
                val appInfo = FirewallManager.getAppInfoByUid(app.uid)
                if (bindSeq != seq) return@io
                val icon = Utilities.getIcon(
                    context,
                    appInfo?.packageName.orEmpty(),
                    appInfo?.appName.orEmpty()
                ) ?: Utilities.getDefaultIcon(context)
                uiCtx {
                    if (bindSeq != seq) return@uiCtx
                    appNameByUid[app.uid] = appInfo?.appName
                    appIconByUid[app.uid] = icon
                    applyIdentity(app, icon, appInfo?.appName)
                }
            }
        }
    }

    inner class DomainViewHolder(private val b: ListItemBlocklistDomainRowBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(domain: AppConnection) {
            b.blDomainName.text = domain.appOrDnsName
            b.blDomainCount.text = domain.count.toString()
        }
    }

    private fun io(f: suspend () -> Unit) {
        (context as? LifecycleOwner)?.lifecycleScope?.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        val owner = context as? LifecycleOwner ?: return
        withContext(Dispatchers.Main.immediate) {
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@withContext
            }
            f()
        }
    }
}
