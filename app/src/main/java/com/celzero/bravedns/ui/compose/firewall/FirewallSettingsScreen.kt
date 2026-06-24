package com.celzero.bravedns.ui.compose.firewall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallSettingsScreen(
    onUniversalFirewallClick: () -> Unit,
    onCustomIpDomainClick: () -> Unit,
    onAppWiseIpDomainClick: () -> Unit,
    initialFocusKey: String? = null,
    onBackClick: (() -> Unit)? = null
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val initialFocus = initialFocusKey?.trim().orEmpty()
    var pendingFocusKey by rememberSaveable(initialFocus) { mutableStateOf(initialFocus) }
    var activeFocusKey by rememberSaveable(initialFocus) {
        mutableStateOf(initialFocus.ifBlank { null })
    }

    LaunchedEffect(pendingFocusKey) {
        val key = pendingFocusKey.trim()
        if (key.isBlank()) return@LaunchedEffect
        activeFocusKey = key
        val target =
            when (key) {
                "firewall_universal",
                "firewall_universal_main" -> 0 to 0
                "firewall_universal_blocked" -> 0 to 108
                "firewall_apps",
                "firewall_apps_rules" -> 1 to 0
                else -> null
            }
        if (target != null) {
            val (index, offsetDp) = target
            val offsetPx = with(density) { offsetDp.dp.toPx().roundToInt() }
            listState.animateScrollToItem(index, offsetPx)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
        }
        pendingFocusKey = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(id = R.string.firewall_mode_info_title),
                subtitle = stringResource(id = R.string.universal_firewall_explanation),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingMd,
                bottom = Dimensions.spacing3xl
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            item {
                SectionHeader(title = stringResource(id = R.string.firewall_act_universal_tab))
                RethinkListGroup {
                    RethinkActionListItem(
                        title = stringResource(id = R.string.univ_firewall_heading),
                        description = stringResource(id = R.string.universal_firewall_explanation),
                        icon = Icons.Rounded.Public,
                        position = CardPosition.First,
                        highlighted = activeFocusKey == "firewall_universal_main",
                        onClick = onUniversalFirewallClick
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.univ_view_blocked_ip),
                        description = stringResource(id = R.string.univ_view_blocked_ip_desc),
                        icon = Icons.Rounded.GppBad,
                        position = CardPosition.Last,
                        highlighted = activeFocusKey == "firewall_universal_blocked",
                        onClick = onCustomIpDomainClick
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.lbl_app_wise))
                RethinkListGroup {
                    RethinkActionListItem(
                        title = stringResource(id = R.string.app_ip_domain_rules),
                        description = stringResource(id = R.string.app_ip_domain_rules_desc),
                        icon = Icons.Rounded.Apps,
                        position = CardPosition.Single,
                        highlighted = activeFocusKey == "firewall_apps_rules",
                        onClick = onAppWiseIpDomainClick
                    )
                }
            }
        }
    }
}
