package com.celzero.bravedns.ui

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppListAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.databinding.FragmentFirewallAppListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.google.android.material.chip.Chip
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class FirewallAppsFragment : Fragment(R.layout.fragment_firewall_app_list),
                             SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentFirewallAppListBinding::bind)

    private val appInfoViewModel: AppInfoViewModel by viewModel()
    private val persistentState by inject<PersistentState>()

    private var layoutManager: RecyclerView.LayoutManager? = null

    private var searchString: String = ""
    private var categoryFilters: MutableSet<String> = mutableSetOf()
    private var topLevelFilter =  TopLevelFilter.INSTALLED

    enum class TopLevelFilter(val id: Int) {
        ALL(0), INSTALLED(1), SYSTEM(2)
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        this.searchString = query
        appInfoViewModel.setFilter(searchString, categoryFilters, topLevelFilter)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        this.searchString = query
        appInfoViewModel.setFilter(searchString, categoryFilters, topLevelFilter)
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    companion object {
        fun newInstance() = FirewallAppsFragment()
    }

    private fun initView() {
        b.ffaAppList.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        b.ffaAppList.layoutManager = layoutManager
        val recyclerAdapter = FirewallAppListAdapter(requireContext(), viewLifecycleOwner, persistentState)
        appInfoViewModel.appInfos.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))
        b.ffaAppList.adapter = recyclerAdapter

        b.ffaSearch.setOnQueryTextListener(this)
        b.ffaSearch.setOnClickListener {
            showParentChipsUi()
        }

        b.ffaFilterIcon.setOnClickListener {
            toggleParentChipsUi()
        }

        remakeParentFilterChipsUi()
        remakeChildFilterChipsUi(FirewallManager.getAllCategories())
    }

    private fun toggleParentChipsUi() {
        if (b.ffaParentChipGroup.isVisible) {
            hideParentChipsUi()
            hideChildChipsUi()
        } else {
            showParentChipsUi()
            showChildChipsUi()
        }
    }

    private fun showChildChipsUi() {
        b.ffaChipGroup.visibility = View.VISIBLE
    }

    private fun hideChildChipsUi() {
        b.ffaChipGroup.visibility = View.GONE
    }

    private fun showParentChipsUi() {
        b.ffaParentChipGroup.visibility = View.VISIBLE
    }

    private fun hideParentChipsUi() {
        b.ffaParentChipGroup.visibility = View.GONE
    }

    private fun remakeParentFilterChipsUi() {
        b.ffaParentChipGroup.removeAllViews()

        val all = makeParentChip(TopLevelFilter.ALL.id,
                                 getString(R.string.fapps_filter_parent_all), false)
        val allowed = makeParentChip(TopLevelFilter.INSTALLED.id,
                                     getString(R.string.fapps_filter_parent_installed), true)
        val blocked = makeParentChip(TopLevelFilter.SYSTEM.id,
                                     getString(R.string.fapps_filter_parent_system), false)

        b.ffaParentChipGroup.addView(all)
        b.ffaParentChipGroup.addView(allowed)
        b.ffaParentChipGroup.addView(blocked)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applyParentFilter(button.tag)
            } else {
                // no-op
                // no action needed for checkState: false
            }
        }

        return chip
    }

    private fun applyParentFilter(tag: Any) {
        when (tag) {
            TopLevelFilter.ALL.id -> {
                categoryFilters.clear()
                topLevelFilter = TopLevelFilter.ALL
                appInfoViewModel.setFilter(searchString, categoryFilters, topLevelFilter)
                remakeChildFilterChipsUi(FirewallManager.getAllCategories())
            }
            TopLevelFilter.INSTALLED.id -> {
                categoryFilters.clear()
                topLevelFilter = TopLevelFilter.INSTALLED
                appInfoViewModel.setFilter(searchString, categoryFilters, topLevelFilter)
                remakeChildFilterChipsUi(FirewallManager.getCategoriesForInstalledApps())
            }
            TopLevelFilter.SYSTEM.id -> {
                categoryFilters.clear()
                topLevelFilter = TopLevelFilter.SYSTEM
                appInfoViewModel.setFilter(searchString, categoryFilters, topLevelFilter)
                remakeChildFilterChipsUi(FirewallManager.getCategoriesForSystemApps())
            }
        }
    }

    private fun remakeChildFilterChipsUi(categories: List<String>) {
        b.ffaChipGroup.removeAllViews()
        for (c in categories) {
            b.ffaChipGroup.addView(makeChildChip(c))
        }
    }

    private fun makeChildChip(title: String): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
        chip.text = title
        chip.tag = title

        chip.setOnCheckedChangeListener { compoundButton: CompoundButton, isSelected: Boolean ->
            applyChildFilter(compoundButton.tag, isSelected)
        }
        return chip
    }

    private fun applyChildFilter(tag: Any, show: Boolean) {
        if (show) {
            categoryFilters.add(tag.toString())
        } else {
            categoryFilters.remove(tag.toString())
        }
        appInfoViewModel.setFilter(searchString, categoryFilters, topLevelFilter)
    }

}