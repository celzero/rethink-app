/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.*
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.trafficlogs.*
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration.Companion.milliseconds

class RethinkLogFragment : Fragment() {
    private val viewModel: RethinkLogViewModel by viewModel()
    private val rethinkLogRepository by inject<RethinkLogRepository>()
    private val persistentState by inject<PersistentState>()
    private lateinit var state: LogScreenState
    private var presenter: RethinkLogAdapter? = null
    companion object {
        private const val QUERY_TEXT_DELAY = 1000L
        fun newInstance(param: String) = RethinkLogFragment().apply { arguments = Bundle().apply { putString(Constants.SEARCH_QUERY, param) } }
    }
    private fun resetViewPresentation() { presenter = null }

    private var viewSearchSnapshot: String? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        state = LogScreenState()
        resetViewPresentation()
        return ComposeView(requireContext()).apply { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("traffic.search", if (::state.isInitialized) state.search.query else viewSearchSnapshot)
    }
    override fun onDestroyView() {
        viewSearchSnapshot = state.search.query
        super.onDestroyView()
    }
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // TextView hierarchy restoration happens after arguments and listener installation.
        (savedInstanceState?.getString("traffic.search") ?: viewSearchSnapshot)?.let { state.search.setQuery(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!persistentState.logsEnabled) { state.showSearch = false; state.message = getString(R.string.show_logs_disabled_network_message) }
        else {
            val adapter = RethinkLogAdapter(requireContext()); presenter = adapter
            viewModel.rlogList.observe(viewLifecycleOwner) { adapter.submitData(lifecycle, it) }
            state.search.listener = { onQueryTextChange(it) }; state.search.submitListener = { onQueryTextSubmit(it) }
            setQueryFilter()
        }
        arguments?.getString(Constants.SEARCH_QUERY)?.let { state.search.setQuery(it, true) }
        (view as ComposeView).setContent { RethinkTheme { Surface(color = themeColor(R.attr.background), contentColor = themeColor(R.attr.primaryTextColor)) { Content() } } }
    }
    override fun onResume() { super.onResume(); state.search.setQuery(""); state.resumeGeneration++ }
    @OptIn(FlowPreview::class)
    private fun setQueryFilter() {
        lifecycleScope.launch {
            searchQuery
                .debounce(QUERY_TEXT_DELAY)
                .distinctUntilChanged()
                .collect { query ->
                    viewModel.setFilter(query)
                }
        }
    }
    fun onQueryTextSubmit(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    fun onQueryTextChange(query: String): Boolean {
        searchQuery.value = query
        return true
    }
    private fun showDeleteDialog() {
        val builder = WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.conn_track_clear_logs_title)
        builder.setMessage(R.string.conn_track_clear_logs_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
            io { rethinkLogRepository.clearAllData() }
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ -> }
        builder.create().show()
    }
    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    val searchQuery = MutableStateFlow("")
    @Composable private fun Content() {
        Column(Modifier.fillMaxSize().background(themeColor(R.attr.background))) {
            LogSearchBar(state, stringResource(R.string.search_firewall_network_log), actions = { LogAction(R.drawable.ic_delete, stringResource(R.string.lbl_delete), ::showDeleteDialog) })
            presenter?.let { LogList(state, it, Modifier.weight(1f), timestamp = { row -> row.timeStamp }) } ?: state.message?.let { Text(it) }
        }
    }
}
