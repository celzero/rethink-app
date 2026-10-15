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
package com.celzero.bravedns.adapter
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import com.celzero.bravedns.ui.compose.wireguard.WgListContent
import com.celzero.bravedns.ui.compose.appdetails.*

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_FIREWALL
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.service.DomainRulesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DomainRulesBtmSheetAdapter(
    val context: Context,
    private val uid: Int,
    private val domains: Array<String>
) : WgListContent {





    @Composable override fun Content(modifier: Modifier) {
        Column(modifier) {
            domains.forEachIndexed { index, domain ->
                key(index, domain) {
                    val state = remember { DomainRuleRowState() }
                    val holder = remember { ViewHolder(state) }
                    LaunchedEffect(holder) { holder.update(domain) }
                    DomainRuleRowContent(state)
                }
            }
        }
    }

    inner class ViewHolder(private val b: DomainRuleRowState) {

        fun update(item: String) {
            val domain = item.trim()
            b.domainText.text = domain.trim()
            when (DomainRulesManager.getDomainRule(domain, uid)) {
                DomainRulesManager.Status.TRUST -> {
                    enableTrustUi()
                }
                DomainRulesManager.Status.BLOCK -> {
                    enableBlockUi()
                }
                DomainRulesManager.Status.NONE -> {
                    noRuleUi()
                }
            }

            b.blockIcon.setOnClickListener {
                if (
                    DomainRulesManager.getDomainRule(domain, uid) == DomainRulesManager.Status.BLOCK
                ) {
                    applyDomainRule(domain, DomainRulesManager.Status.NONE)
                    noRuleUi()
                } else {
                    applyDomainRule(domain, DomainRulesManager.Status.BLOCK)
                    enableBlockUi()
                }
            }

            b.trustIcon.setOnClickListener {
                if (
                    DomainRulesManager.getDomainRule(domain, uid) == DomainRulesManager.Status.TRUST
                ) {
                    applyDomainRule(domain, DomainRulesManager.Status.NONE)
                    noRuleUi()
                } else {
                    applyDomainRule(domain, DomainRulesManager.Status.TRUST)
                    enableTrustUi()
                }
            }
        }

        private fun applyDomainRule(domain: String, domainRuleStatus: DomainRulesManager.Status) {
            Logger.i(LOG_TAG_FIREWALL, "Apply domain rule for $domain, ${domainRuleStatus.name}")
            io {
                DomainRulesManager.addDomainRule(
                    domain.trim(),
                    domainRuleStatus,
                    DomainRulesManager.DomainType.DOMAIN,
                    uid,
                )
            }
        }

        private fun enableTrustUi() {
            b.trustIcon.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_trust_accent)
            )
            b.blockIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_block))
        }

        private fun enableBlockUi() {
            b.trustIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_trust))
            b.blockIcon.setImageDrawable(
                ContextCompat.getDrawable(context, R.drawable.ic_block_accent)
            )
        }

        private fun noRuleUi() {
            b.trustIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_trust))
            b.blockIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_block))
        }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
