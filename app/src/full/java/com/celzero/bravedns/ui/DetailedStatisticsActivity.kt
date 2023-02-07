package com.celzero.bravedns.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.paging.PagingData
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ActivityDetailedStatisticsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.viewmodel.DetailedStatisticsViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DetailedStatisticsActivity : AppCompatActivity(R.layout.activity_detailed_statistics) {
    private val b by viewBinding(ActivityDetailedStatisticsBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val viewModel: DetailedStatisticsViewModel by viewModel()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    companion object {
        const val INTENT_TYPE = "STATISTICS_TYPE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        val type =
            intent.getIntExtra(
                INTENT_TYPE,
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS.tid
            )
        val statType = SummaryStatisticsFragment.SummaryStatisticsType.getType(type)
        setRecyclerView(statType)
    }

    private fun setRecyclerView(type: SummaryStatisticsFragment.SummaryStatisticsType) {
        b.dsaRecycler.setHasFixedSize(true)
        val layoutManager = CustomLinearLayoutManager(this)
        b.dsaRecycler.layoutManager = layoutManager

        val recyclerAdapter = SummaryStatisticsAdapter(this, persistentState, appConfig, type)

        handleStatType(type).observe(this) { recyclerAdapter.submitData(this.lifecycle, it) }

        // remove the view if there is no data
        recyclerAdapter.addLoadStateListener {
            if (it.append.endOfPaginationReached) {
                if (recyclerAdapter.itemCount < 1) {
                    b.dsaRecycler.visibility = View.GONE
                    b.dsaNoDataRl.visibility = View.VISIBLE
                }
            }
        }
        b.dsaRecycler.adapter = recyclerAdapter
    }

    private fun handleStatType(
        type: SummaryStatisticsFragment.SummaryStatisticsType
    ): LiveData<PagingData<AppConnection>> {
        viewModel.setData(type)
        return when (type) {
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                b.dsaTitle.text = getString(R.string.ssv_app_network_activity_heading)
                viewModel.getAllAllowedAppNetworkActivity
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                b.dsaTitle.text = getString(R.string.ssv_app_blocked_heading)
                viewModel.getAllBlockedAppNetworkActivity
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_contacted_domain_heading)
                viewModel.getAllContactedDomains
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_blocked_domain_heading)
                viewModel.getAllBlockedDomains
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_contacted_ips_heading)
                viewModel.getAllContactedIps
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                b.dsaTitle.text = getString(R.string.ssv_most_blocked_ips_heading)
                viewModel.getAllBlockedIps
            }
        }
    }
}
