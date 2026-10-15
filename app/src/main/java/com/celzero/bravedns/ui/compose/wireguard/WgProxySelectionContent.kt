/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.controls.*

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

class WgProxySelectorState(context: Context) {
    val ipDomainInfo = WgControlState(context, textSource = "bottom_sheet_proxies_list:ip_domain_info")
    val title = WgControlState(context, textSource = "bottom_sheet_proxies_list:title").apply { text = context.getString(R.string.wg_compose_choose_country); textSizeSp = 20f }
    val recyclerView = WgControlState(context)
}
class WgProxyRowState(context: Context) {
    val lipCcWgParent = WgControlState(context)
    val proxyNameCc = WgControlState(context, textSource = "list_item_proxy_cc_wg:proxy_name_cc").apply { textSizeSp = wgTextDimensionSp(context, R.dimen.large_font_text_view) }
    val proxyDescCc = WgControlState(context, textSource = "list_item_proxy_cc_wg:proxy_desc_cc").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.primaryLightColorText); textSizeSp = wgTextDimensionSp(context, R.dimen.default_font_text_view) }
    val proxyRadioCc = WgControlState(context, checkable = true, textSource = "list_item_proxy_cc_wg:proxy_radio_cc")
}

@Composable fun WgProxySelectionContent(b: WgProxySelectorState) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(60.dp).height(3.dp).background(MaterialTheme.colorScheme.outline))
            WgText(b.ipDomainInfo, Modifier.align(Alignment.CenterHorizontally))
            WgText(b.title)
            b.recyclerView.adapter?.Content(Modifier.weight(1f, fill = false))
        }
    }
}
@Composable fun WgProxyRow(b: WgProxyRowState) {
    Row(Modifier.fillMaxWidth().clickable { b.lipCcWgParent.performClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        // The baseline WG selector leaves the reused country-icon cell empty.
        Spacer(Modifier.width(50.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { WgText(b.proxyNameCc); WgText(b.proxyDescCc) }
        OriginalRadioButton("list_item_proxy_cc_wg:proxy_radio_cc", b.proxyRadioCc.isChecked, { b.proxyRadioCc.isChecked = true; b.proxyRadioCc.callOnClick() })
    }
}
