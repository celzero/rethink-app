package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_UI
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.databinding.BottomSheetProxiesListBinding
import com.celzero.bravedns.databinding.ListItemEndpointBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class ProxyCountriesBtmSheet(val input: InputType, val obj: Any?, val data: List<String>, val listener: CountriesDismissListener) :
    BottomSheetDialogFragment() {
    private var _binding: BottomSheetProxiesListBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

    private val cd: CustomDomain? = if (input == InputType.DOMAIN) obj as CustomDomain else null
    private val ci: CustomIp? = if (input == InputType.IP) obj as CustomIp else null
    private val appInfo: AppInfo? = if (input == InputType.APP) obj as AppInfo else null

    enum class InputType(val id: Int) {
        DOMAIN (0),
        IP (1),
        APP (2)
    }

    companion object {
        fun newInstance(input: InputType, obj: Any?, data: List<String>, listener: CountriesDismissListener): ProxyCountriesBtmSheet {
            return ProxyCountriesBtmSheet(input, obj, data, listener)
        }

        private const val TAG = "PCCBtmSheet"
    }

    interface CountriesDismissListener {
        fun onDismissCC(obj: Any?)
    }

    override fun getTheme(): Int =
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetProxiesListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        b.title.text = "Select Proxy Country"
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val lst = data.map { it }
        val adapter = RecyclerViewAdapter(lst) { selectedItem ->
            Logger.v(LOG_TAG_UI, "$TAG: country selected: $selectedItem")
            // TODO: Implement the action to be taken when an item is selected
            val selectedCountry = selectedItem
            val canSelectCC = RpnProxyManager.canSelectCountryCode(selectedCountry)
            if (!canSelectCC) {
                Utilities.showToastUiCentered(
                    requireContext(),
                    "Country code limit reached for the selected endpoint",
                    Toast.LENGTH_SHORT
                )
                Logger.w(LOG_TAG_UI, "$TAG: Country code limit reached for the selected endpoint")
                return@RecyclerViewAdapter
            }
            io {
                when (input) {
                    InputType.DOMAIN -> {
                        if (cd == null) {
                            Logger.w(LOG_TAG_UI, "$TAG: custom domain is null")
                            return@io
                        }
                        DomainRulesManager.setCC(cd, selectedCountry)
                        cd.proxyCC = selectedCountry
                        uiCtx {
                            Utilities.showToastUiCentered(
                                requireContext(),
                                "Country code updated for ${cd.domain}",
                                Toast.LENGTH_SHORT
                            )
                         }
                    }
                    InputType.IP -> {
                        if (ci == null) {
                            Logger.w(LOG_TAG_UI, "$TAG: custom ip is null")
                            return@io
                        }
                        IpRulesManager.updateProxyCC(ci, selectedCountry)
                        ci.proxyCC = selectedCountry
                        uiCtx {
                            Utilities.showToastUiCentered(
                                requireContext(),
                                "Country code updated for ${ci.ipAddress}",
                                Toast.LENGTH_SHORT
                            )
                        }
                    }
                    InputType.APP -> TODO()
                }
            }
        }

        b.recyclerView.adapter = adapter
    }

    inner class RecyclerViewAdapter(
        private val data: List<String>,
        private val onItemClicked: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                ListItemEndpointBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount(): Int = data.size

        inner class ViewHolder(private val bb: ListItemEndpointBinding) :
            RecyclerView.ViewHolder(bb.root) {

            fun bind(item: String) {
                when (input) {
                    InputType.DOMAIN -> {
                        bb.endpointName.text = item
                        bb.endpointCheck.isChecked = item == cd?.proxyCC
                        if (item == cd?.proxyCC) {
                            bb.endpointDesc.text = "Selected"
                        }
                    }
                    InputType.IP -> {
                        bb.endpointName.text = item
                        bb.endpointCheck.isChecked = item == ci?.proxyCC
                        if (item == ci?.proxyCC) {
                            bb.endpointDesc.text = "Selected"
                        }
                    }
                    InputType.APP -> {
                        // TODO: Implement this
                    }
                }

                bb.endpointListContainer.setOnClickListener {
                    notifyDataSetChanged()
                    onItemClicked(item)
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        when (input) {
            InputType.DOMAIN -> {
                listener.onDismissCC(cd)
            }
            InputType.IP -> {
                listener.onDismissCC(ci)
            }
            InputType.APP -> {
                listener.onDismissCC(appInfo)
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

}