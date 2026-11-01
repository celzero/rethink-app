/* Copyright 2024 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.chrome.homeNavigationClearance
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
/** F-Droid preserves its coming-soon screen and never initializes billing. */
class RethinkPlusFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(bottom = homeNavigationClearance(60.dp)).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.rpn_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.premium_subtitle), style = MaterialTheme.typography.bodySmall)
                        Icon(painterResource(R.drawable.ic_rethink_plus_sparkle), null, Modifier.size(48.dp).align(Alignment.CenterHorizontally))
                        Text(stringResource(R.string.coming_soon_toast).capitalizeWords(), Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            } }
        }
}
