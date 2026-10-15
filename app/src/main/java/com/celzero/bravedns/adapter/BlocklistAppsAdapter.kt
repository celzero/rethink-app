/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.statistics.BlocklistStatisticsAppRow
import com.celzero.bravedns.ui.compose.statistics.BlocklistStatisticsDomainRow
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filter submissions reset expansion and cached children, as in the original adapter. */
class BlocklistAppsAdapter internal constructor(private val context: Context,
    private val identityResolver: (suspend (Int) -> Pair<String?, Drawable?>)?,
    private val fetchDomains: suspend (uid: Int) -> List<AppConnection>) {
    constructor(context: Context, fetchDomains: suspend (uid: Int) -> List<AppConnection>) : this(context, null, fetchDomains)
    private class Group(val app: AppConnection) {
        var expanded by mutableStateOf(false)
        var domains by mutableStateOf<List<AppConnection>?>(null)
    }
    private var groups by mutableStateOf<List<Group>>(emptyList())
    private val identities = mutableMapOf<Int, Pair<String?, Drawable?>>()
    val itemCount: Int get() = groups.size
    fun submitApps(apps: List<AppConnection>) { groups = apps.map { Group(it) } }
    @Composable
    fun GroupAt(index: Int) {
        val group = groups.getOrNull(index) ?: return
        val app = group.app
        val identity by produceState(identities[app.uid], app.uid) {
            if (value == null) value = withContext(Dispatchers.IO) {
                if (identityResolver != null) identityResolver.invoke(app.uid) else {
                    val info = FirewallManager.getAppInfoByUid(app.uid)
                    info?.appName to (Utilities.getIcon(context, info?.packageName.orEmpty(), info?.appName.orEmpty()) ?: Utilities.getDefaultIcon(context))
                }
            }.also { identities[app.uid] = it }
        }
        val description = stringResource(if (group.expanded) R.string.statistics_expanded else R.string.statistics_collapsed)
        Column(Modifier.testTag("blocklist_group_${app.uid}").semantics { stateDescription = description }) {
            BlocklistStatisticsAppRow(identity?.first ?: app.appOrDnsName ?: stringResource(R.string.network_log_app_name_unnamed, app.uid.toString()),
                app.count.toString(), icon = identity?.second ?: Utilities.getDefaultIcon(context),
                expanded = group.expanded, tag = "blocklist_app_${app.uid}") { toggle(group) }
            if (group.expanded) group.domains.orEmpty().forEach { domain ->
                BlocklistStatisticsDomainRow(domain.appOrDnsName.orEmpty(), domain.count.toString())
            }
        }
    }
    private fun toggle(group: Group) {
        group.expanded = !group.expanded
        if (group.expanded && group.domains == null) {
            val owner = context as? LifecycleOwner ?: return
            owner.lifecycleScope.launch(Dispatchers.IO) {
                val domains = fetchDomains(group.app.uid)
                withContext(Dispatchers.Main.immediate) {
                    if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext
                    if (group in groups) group.domains = domains
                }
            }
        }
    }
}
