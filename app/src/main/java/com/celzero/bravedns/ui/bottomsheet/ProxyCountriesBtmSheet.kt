package com.celzero.bravedns.ui.bottomsheet

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.ui.compose.networksheets.ProxyCountriesSheetState
import com.celzero.bravedns.ui.compose.networksheets.ProxyCountriesSheetContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class ProxyCountriesBtmSheet :
    BaseBottomSheetDialogFragment() {
    private var _binding: ProxyCountriesSheetState? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    private lateinit var type: InputType
    private var obj: Any? = null
    private lateinit var confs: List<String>
    private var listener: CountriesDismissListener? = null

    private val cd: CustomDomain?
        get() = if (type == InputType.DOMAIN) obj as? CustomDomain else null
    private val ci: CustomIp?
        get() = if (type == InputType.IP) obj as? CustomIp else null
    private val ai: AppInfo?
        get() = if (type == InputType.APP) obj as? AppInfo else null

    enum class InputType(val id: Int) {
        DOMAIN (0),
        IP (1),
        APP (2)
    }

    companion object {
        private const val TAG = "PCCBtmSheet"
        private const val ARG_INPUT_TYPE = "input_type"
        private const val ARG_OBJECT = "object"
        private const val ARG_CONFS = "confs"

        fun newInstance(input: InputType, obj: Any?, data: List<String>, listener: CountriesDismissListener): ProxyCountriesBtmSheet {
            val fragment = ProxyCountriesBtmSheet()
            fragment.listener = listener
            val args = Bundle()
            args.putInt(ARG_INPUT_TYPE, input.id)
            when (obj) {
                is CustomDomain -> args.putSerializable(ARG_OBJECT, obj)
                is CustomIp -> args.putSerializable(ARG_OBJECT, obj)
                is AppInfo -> args.putSerializable(ARG_OBJECT, obj)
            }
            args.putStringArrayList(ARG_CONFS, ArrayList(data))
            fragment.arguments = args
            return fragment
        }
    }

    interface CountriesDismissListener {
        fun onDismissCC(obj: Any?)
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ProxyCountriesSheetState(inflater.context)
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { ProxyCountriesSheetContent(b) } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Retrieve arguments
        val typeId = arguments?.getInt(ARG_INPUT_TYPE) ?: run {
            Logger.e(LOG_TAG_UI, "$TAG InputType not found in arguments, dismissing")
            dismiss()
            return
        }
        type = InputType.entries.firstOrNull { it.id == typeId } ?: run {
            Logger.e(LOG_TAG_UI, "$TAG Invalid InputType: $typeId, dismissing")
            dismiss()
            return
        }

        obj = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            when (type) {
                InputType.DOMAIN -> arguments?.getSerializable(ARG_OBJECT, CustomDomain::class.java)
                InputType.IP -> arguments?.getSerializable(ARG_OBJECT, CustomIp::class.java)
                InputType.APP -> arguments?.getSerializable(ARG_OBJECT, AppInfo::class.java)
            }
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(ARG_OBJECT)
        }

        confs = arguments?.getStringArrayList(ARG_CONFS) ?: emptyList()

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        init()
    }

    private fun init() {
        b.title.text = "Select Proxy Country"

        when (type) {
            InputType.IP -> {
                b.ipDomainInfo.visibility = View.VISIBLE
                b.ipDomainInfo.text = ci?.ipAddress.orEmpty()
            }
            InputType.DOMAIN -> {
                b.ipDomainInfo.visibility = View.VISIBLE
                b.ipDomainInfo.text = cd?.domain.orEmpty()
            }
            InputType.APP -> {
                // TODO: Implement this
            }
        }

        b.rows = confs.map { it }
        b.supportsCountryDetails = type != InputType.APP
        b.flagFor = { getFlag(it) }
        b.selection = { when (type) {
            InputType.DOMAIN -> cd?.proxyCC
            InputType.IP -> ci?.proxyCC
            InputType.APP -> null
        } }
        b.onClick = { conf -> handleOnItemClicked(conf) }
    }

    private fun handleOnItemClicked(conf: String) {
        Logger.v(LOG_TAG_UI, "$TAG: Item clicked: $conf")
        Logger.v(LOG_TAG_UI, "$TAG: country selected: $conf")
        // TODO: Implement the action to be taken when an item is selected
        // returns a pair of boolean and error message
        val pair = Pair(true, "")//RpnProxyManager.canSelectCountryCode(conf)
        if (!pair.first) {
            Utilities.showToastUiCentered(
                requireContext(),
                pair.second,
                Toast.LENGTH_SHORT
            )
            Logger.w(LOG_TAG_UI, "$TAG: err on selecting cc: ${pair.second}")
            return
        }
        io {
            when (type) {
                InputType.DOMAIN -> {
                    processDomain(conf)
                }
                InputType.IP -> {
                    processIp(conf)
                }
                InputType.APP -> {
                    // processApp(config)
                }
            }
        }
    }

    private suspend fun processDomain(conf: String) {
        val domain = cd
        if (domain == null) {
            Logger.w(LOG_TAG_UI, "$TAG: custom domain is null")
            return
        }
        DomainRulesManager.setCC(domain, conf)
        domain.proxyCC = conf
        uiCtx {
            Utilities.showToastUiCentered(
                requireContext(),
                "Country code updated for ${domain.domain}",
                Toast.LENGTH_SHORT
            )
        }
    }

    private suspend fun processIp(conf: String) {
        val ip = ci
        if (ip == null) {
            Logger.w(LOG_TAG_UI, "$TAG: custom ip is null")
            return
        }
        IpRulesManager.updateProxyCC(ip, conf)
        ip.proxyCC = conf
        uiCtx {
            Utilities.showToastUiCentered(
                requireContext(),
                "Country code updated for ${ip.ipAddress}",
                Toast.LENGTH_SHORT
            )
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        listener?.let { l ->
            when (type) {
                InputType.DOMAIN -> {
                    l.onDismissCC(cd)
                }
                InputType.IP -> {
                    l.onDismissCC(ci)
                }
                InputType.APP -> {
                    l.onDismissCC(ai)
                }
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        (requireContext() as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isAdded && _binding != null) { f() }
        }
    }

}
