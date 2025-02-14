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
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.databinding.BottomSheetProxiesListBinding
import com.celzero.bravedns.databinding.ListItemProxyCcWgBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class WireguardListBtmSheet(val type: InputType, val obj: Any?, val confs: List<WgConfigFilesImmutable>, val listener: WireguardDismissListener) :
    BottomSheetDialogFragment() {
    private var _binding: BottomSheetProxiesListBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private val persistentState by inject<PersistentState>()

    private val cd: CustomDomain? = if (type == InputType.DOMAIN) obj as CustomDomain else null
    private val ci: CustomIp? = if (type == InputType.IP) obj as CustomIp else null
    private val ai: AppInfo? = if (type == InputType.APP) obj as AppInfo else null

    companion object {
        fun newInstance(input: InputType, obj: Any?, data: List<WgConfigFilesImmutable>, listener: WireguardDismissListener): WireguardListBtmSheet {
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
        when (type) {
            InputType.DOMAIN -> {
                b.ipDomainInfo.visibility = View.VISIBLE
                b.ipDomainInfo.text = cd?.domain
            }
            InputType.IP -> {
                b.ipDomainInfo.visibility = View.VISIBLE
                b.ipDomainInfo.text = ci?.ipAddress
            }
            InputType.APP -> {
                // initApp()
            }
        }

        val lst = confs.map { it }
        b.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        val adapter = RecyclerViewAdapter(lst) { conf ->
            Logger.v(LOG_TAG_UI, "$TAG: Item clicked: ${conf.name}")
            when (type) {
                InputType.DOMAIN -> {
                    processDomain(conf)
                }
                InputType.IP -> {
                    processIp(conf)
                }
                InputType.APP -> {
                    // processApp(selectedItem)
                }
            }
        }

        b.recyclerView.adapter = adapter
    }

    private fun processDomain(conf: WgConfigFilesImmutable) {
        io {
            if (cd == null) {
                Logger.w(LOG_TAG_UI, "$TAG: Custom domain is null")
                return@io
            }
            val id = ID_WG_BASE + conf.id
            DomainRulesManager.setProxyId(cd, id)
            Logger.v(LOG_TAG_UI, "$TAG: wg-endpoint set to ${conf.name} for ${cd.domain}")
            cd.proxyId = id
            uiCtx {
                Utilities.showToastUiCentered(
                    requireContext(),
                    "Wireguard endpoint set to ${conf.name}",
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun processIp(conf: WgConfigFilesImmutable) {
        io {
            if (ci == null) {
                Logger.w(LOG_TAG_UI, "$TAG: Custom IP is null")
                return@io
            }
            val id = ID_WG_BASE + conf.id
            IpRulesManager.updateProxyId(ci, id)
            Logger.v(LOG_TAG_UI, "$TAG: wg-endpoint set to ${conf.name} for ${ci.ipAddress}")
            ci.proxyId = id
            uiCtx {
                Utilities.showToastUiCentered(
                    requireContext(),
                    "Wireguard endpoint set to ${conf.name}",
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    inner class RecyclerViewAdapter(
        private val data: List<WgConfigFilesImmutable>,
        private val onItemClicked: (WgConfigFilesImmutable) -> Unit
    ) : RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                ListItemProxyCcWgBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount(): Int = data.size

        inner class ViewHolder(private val bb: ListItemProxyCcWgBinding) :
            RecyclerView.ViewHolder(bb.root) {

            fun bind(conf: WgConfigFilesImmutable) {
                bb.proxyNameCc.text = conf.name
                bb.proxyDescCc.text = if (conf.isActive) "Active" else "Inactive"

                val id = ID_WG_BASE + conf.id
                when (type) {
                    InputType.DOMAIN -> {
                        bb.proxyRadioCc.isChecked = id == cd?.proxyId
                    }
                    InputType.IP -> {
                        bb.proxyRadioCc.isChecked = id == ci?.proxyId
                    }
                    InputType.APP -> {
                        // bb.endpointCheck.isChecked = item == appInfo?.proxyId
                    }
                }
                bb.lipCcWgParent.setOnClickListener {
                    onItemClicked(conf)
                    notifyDataSetChanged()
                }

                bb.proxyRadioCc.setOnClickListener {
                    onItemClicked(conf)
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
        Logger.v(LOG_TAG_UI, "$TAG: Dismissed, input: ${type.name}")
        when (type) {
            InputType.DOMAIN -> {
                listener.onDismissWg(cd)
            }
            InputType.IP -> {
                listener.onDismissWg(ci)
            }
            InputType.APP -> {
                listener.onDismissWg(ai)
            }
        }
    }

}
