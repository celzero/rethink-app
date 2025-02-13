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
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class WireguardListBtmSheet(val input: InputType, val obj: Any?, val data: List<Config>, val listener: WireguardDismissListener) :
    BottomSheetDialogFragment() {
    private var _binding: BottomSheetProxiesListBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

    private val cd: CustomDomain? = if (input == InputType.DOMAIN) obj as CustomDomain else null
    private val ci: CustomIp? = if (input == InputType.IP) obj as CustomIp else null
    private val appInfo: AppInfo? = if (input == InputType.APP) obj as AppInfo else null

    companion object {
        fun newInstance(input: InputType, obj: Any?, data: List<Config>, listener: WireguardDismissListener): WireguardListBtmSheet {
            return WireguardListBtmSheet(input, obj, data, listener)
        }

        private const val TAG = "WglBtmSht"
    }

    interface WireguardDismissListener {
        fun onDismissWg(obj: Any?)
    }

    enum class InputType(val id: Int) {
        DOMAIN (0),
        IP (1),
        APP (2)
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
        Logger.v(LOG_TAG_UI, "$TAG: view created")
        init()
    }

    private fun init() {
        b.title.text = "Select Wireguard Endpoint"
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val lst = data.map { it }
        val adapter = RecyclerViewAdapter(lst) { selectedItem ->
            Logger.v(LOG_TAG_UI, "$TAG: Item clicked: ${selectedItem.getName()}")
            when (input) {
                InputType.DOMAIN -> {
                    processDomain(selectedItem)
                }
                InputType.IP -> {
                    processIp(selectedItem)
                }
                InputType.APP -> {
                    // processApp(selectedItem)
                }
            }
        }

        b.recyclerView.adapter = adapter
    }

    private fun processDomain(config: Config) {
        io {
            if (cd == null) {
                Logger.w(LOG_TAG_UI, "$TAG: Custom domain is null")
                return@io
            }
            val id = ID_WG_BASE + config.getId()
            DomainRulesManager.setProxyId(cd, id)
            Logger.v(LOG_TAG_UI, "$TAG: wg-endpoint set to ${config.getName()} for ${cd.domain}")
            cd.proxyId = id
            uiCtx {
                Utilities.showToastUiCentered(
                    requireContext(),
                    "Wireguard endpoint set to ${config.getName()}",
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun processIp(config: Config) {
        io {
            if (ci == null) {
                Logger.w(LOG_TAG_UI, "$TAG: Custom IP is null")
                return@io
            }
            val id = ID_WG_BASE + config.getId()
            IpRulesManager.updateProxyId(ci, id)
            Logger.v(LOG_TAG_UI, "$TAG: wg-endpoint set to ${config.getName()} for ${ci.ipAddress}")
            ci.proxyId = id
            uiCtx {
                Utilities.showToastUiCentered(
                    requireContext(),
                    "Wireguard endpoint set to ${config.getName()}",
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    inner class RecyclerViewAdapter(
        private val data: List<Config>,
        private val onItemClicked: (Config) -> Unit
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

            fun bind(item: Config) {
                bb.endpointName.text = item.getName()
                //bb.endpointCheck.isChecked = position == 0
                when (input) {
                    InputType.DOMAIN -> {
                        val id = ID_WG_BASE + item.getId()
                        if (id == cd?.proxyId) {
                            bb.endpointCheck.isChecked = true
                            bb.endpointDesc.text = "Selected"
                        } else {
                            bb.endpointCheck.isChecked = false
                            bb.endpointDesc.text = ""
                        }
                    }
                    InputType.IP -> {
                        val id = ID_WG_BASE + item.getId()
                        if (id == ci?.proxyId) {
                            bb.endpointCheck.isChecked = true
                            bb.endpointDesc.text = "Selected"
                        } else {
                            bb.endpointCheck.isChecked = false
                            bb.endpointDesc.text = ""
                        }
                    }
                    InputType.APP -> {
                        // bb.endpointCheck.isChecked = item == appInfo?.proxyId
                    }
                }
                bb.endpointListContainer.setOnClickListener {
                    onItemClicked(item)
                    notifyDataSetChanged()
                }
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        Logger.v(LOG_TAG_UI, "$TAG: Dismissed, input: ${input.name}")
        when (input) {
            InputType.DOMAIN -> {
                listener.onDismissWg(cd)
            }
            InputType.IP -> {
                listener.onDismissWg(ci)
            }
            InputType.APP -> {
                listener.onDismissWg(appInfo)
            }
        }
    }

}
