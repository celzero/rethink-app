/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.bottomsheet.CustomIpRulesBtmSheet
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rules.RulesPage
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class CustomIpFragment : Fragment() {
    private val model: CustomIpViewModel by viewModel()
    private val logger by inject<EventLogger>()
    companion object {
        fun newInstance(uid: Int, rules: CustomRulesActivity.RULES) = CustomIpFragment().apply {
            arguments = Bundle().apply { putInt(INTENT_UID, uid); putInt(CustomRulesActivity.INTENT_RULES, rules.type) }
        }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val uid = arguments?.getInt(INTENT_UID, UID_EVERYBODY) ?: UID_EVERYBODY
        val rules = CustomRulesActivity.RULES.getType(arguments?.getInt(CustomRulesActivity.INTENT_RULES, CustomRulesActivity.RULES.APP_SPECIFIC_RULES.type) ?: CustomRulesActivity.RULES.APP_SPECIFIC_RULES.type)
        val app = rules == CustomRulesActivity.RULES.APP_SPECIFIC_RULES
        model.setUid(uid)
        val source = if (app) model.customIpDetails else model.allIpRules
        val count = if (app) model.ipRulesCount(uid) else model.allIpRulesCount()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RulesPage(false, uid, rules, source, count, model::setFilter, logger) { item ->
                CustomIpRulesBtmSheet.newInstance(item).let { it.show(parentFragmentManager, it.tag) }
            } } }
        }
    }
}
