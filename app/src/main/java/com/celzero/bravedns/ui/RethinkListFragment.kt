/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.DialogSetRethinkBinding
import com.celzero.bravedns.databinding.FragmentRethinkListBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity.Companion.UID
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Constants.Companion.RETHINK_BASE_URL
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.MalformedURLException
import java.net.URL


class RethinkListFragment : Fragment(R.layout.fragment_rethink_list) {
    private val b by viewBinding(FragmentRethinkListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()

    // rethink doh ui elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: RethinkEndpointAdapter? = null
    private val viewModel: RethinkEndpointViewModel by viewModel()

    private var uid: Int = Constants.MISSING_UID

    private var isDownloadInitiated = false

    data class ModifiedStamp(val name: String, val stamp: String, val count: Int)

    companion object {
        fun newInstance() = RethinkListFragment()
        var modifiedStamp: MutableLiveData<ModifiedStamp?> = MutableLiveData()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val bundle = this.arguments
        uid = bundle?.getInt(UID, Constants.MISSING_UID) ?: Constants.MISSING_UID
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
        observeModifiedStamp()
    }

    private fun observeModifiedStamp() {
        modifiedStamp.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            if (it.name.isNotEmpty()) {
                updateRethinkEndpoint(it.name, getUrlForStamp(it.stamp), it.count)
                return@observe
            }

            showAddCustomDohDialog(it.stamp, it.count)
        }
    }

    private fun showBlocklistVersionUi() {
        if (getDownloadTimeStamp() == INIT_TIME_MS) {
            b.dohFabAddServerIcon.visibility = View.GONE
            return
        }

        b.lbVersion.text = getString(R.string.settings_local_blocklist_version,
                                     Utilities.convertLongToTime(getDownloadTimeStamp(),
                                                                 Constants.TIME_FORMAT_2))
    }

    private fun showProgress(chip: Chip) {
        val cpDrawable = CircularProgressDrawable(requireContext())
        cpDrawable.setStyle(CircularProgressDrawable.DEFAULT)
        val color = fetchColor(requireContext(), R.attr.chipTextPositive)
        cpDrawable.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(color,
                                                                                             BlendModeCompat.SRC_ATOP)
        cpDrawable.start()

        chip.chipIcon = cpDrawable
        chip.isChipIconVisible = true
    }

    private fun hideProgress() {
        b.bslbCheckUpdateBtn.isChipIconVisible = false
        b.bslbRedownloadBtn.isChipIconVisible = false
        b.bslbUpdateAvailableBtn.isChipIconVisible = false
    }

    private fun showUpdateCheckUi() {
        if (isBlocklistUpdateAvailable()) {
            b.bslbUpdateAvailableBtn.visibility = View.VISIBLE
            b.bslbRedownloadBtn.visibility = View.GONE
            b.bslbCheckUpdateBtn.visibility = View.GONE
            return
        }

        b.bslbCheckUpdateBtn.visibility = View.VISIBLE
        b.bslbRedownloadBtn.visibility = View.GONE
        b.bslbUpdateAvailableBtn.visibility = View.GONE
        return
    }

    private fun isBlocklistUpdateAvailable(): Boolean {
        return persistentState.newestRemoteBlocklistTimestamp != INIT_TIME_MS
    }

    private fun checkBlocklistUpdate() {
        appDownloadManager.isDownloadRequired(AppDownloadManager.DownloadType.REMOTE,
                                              retryCount = 0)
    }

    private fun getDownloadTimeStamp(): Long {
        return persistentState.remoteBlocklistTimestamp
    }

    private fun initView() {
        showBlocklistVersionUi()
        showUpdateCheckUi()
        updateMaxSwitchUi()

        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        recyclerAdapter = RethinkEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        viewModel.setFilter(uid)
        viewModel.rethinkEndpointList.observe(viewLifecycleOwner) {
            recyclerAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDohConnections.adapter = recyclerAdapter
    }

    private fun updateMaxSwitchUi() {
        ui {
            var endpointUrl: String? = ""
            ioCtx {
                endpointUrl = appConfig.getRethinkPlusEndpoint().url
            }
            b.frlSwitchMax.isChecked = (endpointUrl?.contains(MAX_ENDPOINT) == true)
        }
    }

    private fun initClickListeners() {
        b.dohFabAddServerIcon.setOnClickListener {
            emptyTempStampInfo()

            val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
            intent.putExtra(RETHINK_BLOCKLIST_TYPE,
                            RethinkBlocklistFragment.RethinkBlocklistType.REMOTE)
            requireContext().startActivity(intent)
        }

        b.bslbCheckUpdateBtn.setOnClickListener {
            isDownloadInitiated = true
            b.bslbCheckUpdateBtn.isEnabled = false
            showProgress(b.bslbCheckUpdateBtn)
            checkBlocklistUpdate()
        }

        b.bslbUpdateAvailableBtn.setOnClickListener {
            isDownloadInitiated = true
            b.bslbUpdateAvailableBtn.isEnabled = false
            val timestamp = getDownloadableTimestamp()

            if (getDownloadTimeStamp() < timestamp) {
                // show dialog if the download type is local
                showProgress(b.bslbUpdateAvailableBtn)
                download(timestamp)
            } else {
                showUpdateCheckUi()
                Utilities.showToastUiCentered(requireContext(),
                                              getString(R.string.blocklist_update_check_failure),
                                              Toast.LENGTH_SHORT)
            }
        }

        b.bslbRedownloadBtn.setOnClickListener {
            isDownloadInitiated = true
            b.bslbRedownloadBtn.isEnabled = false
            showProgress(b.bslbRedownloadBtn)
            download(getDownloadTimeStamp())
        }

        b.frlSwitchMax.setOnClickListener {
            if (b.frlSwitchMax.isChecked) {
                io {
                    appConfig.switchRethinkDnsToMax()
                }
            } else {
                io {
                    appConfig.switchRethinkDnsToBasic()
                }
            }
        }

    }

    private fun getDownloadableTimestamp(): Long {
        return persistentState.newestRemoteBlocklistTimestamp
    }

    private fun download(timestamp: Long) {
        appDownloadManager.downloadRemoteBlocklist(timestamp)
    }

    private fun getUrlForStamp(stamp: String): String {
        return RETHINK_BASE_URL + stamp
    }

    /**
     * Shows dialog for custom DNS endpoint configuration
     * If entered DNS end point is valid, then the DNS queries are forwarded to that end point
     * else, it will revert back to default end point
     */
    private fun showAddCustomDohDialog(stamp: String, count: Int) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_doh_dialog_title))
        val dialogBinding = DialogSetRethinkBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        dialogBinding.dialogCustomUrlConfigureBtn.visibility = View.GONE
        dialogBinding.dialogCustomUrlEditText.append(RETHINK_BASE_URL + stamp)

        dialogBinding.dialogCustomNameEditText.setText(getString(R.string.rt_rethink_dns),
                                                       TextView.BufferType.EDITABLE)

        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getRethinkCount().plus(1).toString()
            uiCtx {
                val name = getString(R.string.rethink_dns_txt, nextIndex)
                dialogBinding.dialogCustomNameEditText.setText(name, TextView.BufferType.EDITABLE)
            }
        }

        dialogBinding.dialogCustomUrlOkBtn.setOnClickListener {
            val url = dialogBinding.dialogCustomUrlEditText.text.toString()
            val name = dialogBinding.dialogCustomNameEditText.text.toString()

            if (checkUrl(url)) {
                insertRethinkEndpoint(name, url, count)
                dialog.dismiss()
            } else {
                dialogBinding.dialogCustomUrlFailureText.text = resources.getString(
                    R.string.custom_url_error_invalid_url)
                dialogBinding.dialogCustomUrlFailureText.visibility = View.VISIBLE
                dialogBinding.dialogCustomUrlCancelBtn.visibility = View.VISIBLE
                dialogBinding.dialogCustomUrlOkBtn.visibility = View.VISIBLE
                dialogBinding.dialogCustomUrlLoading.visibility = View.INVISIBLE
            }
        }

        dialogBinding.dialogCustomUrlCancelBtn.setOnClickListener {
            emptyTempStampInfo()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun initObservers() {
        appDownloadManager.remoteDownloadStatus.observe(viewLifecycleOwner) {
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD, "Remote blocklist download status id: $it")
            if (!isDownloadInitiated) return@observe

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id) {
                // no-op
                return@observe
            }
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    hideProgress()
                    onRemoteDownloadFailure()
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                    requireActivity().finish()
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                ui {
                    // no-op for remote download
                    // onDownloadStart()
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.SUCCESS.id) {
                ui {
                    hideProgress()
                    onDownloadSuccess()
                }
                // reset live-data value to initial state, as previous state is completed
                appDownloadManager.remoteDownloadStatus.postValue(
                    AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id)
            }
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD,
                  "Remote blocklist, Is download successful? $it(timestamp/status)")
        }

        appDownloadManager.timeStampToDownload.observe(viewLifecycleOwner) {
            if (!isDownloadInitiated) return@observe

            Log.i(LoggerConstants.LOG_TAG_DNS, "Check for blocklist update, status: $it")
            if (it == AppDownloadManager.DownloadManagerStatus.NOT_STARTED.id) {
                // no-op
                return@observe
            }
            if (it == AppDownloadManager.DownloadManagerStatus.FAILURE.id) {
                ui {
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED.id) {
                ui {
                    showRedownloadUi()
                    Utilities.showToastUiCentered(requireContext(), getString(
                        R.string.blocklist_update_check_not_required), Toast.LENGTH_SHORT)
                }
                return@observe
            }

            if (it == AppDownloadManager.DownloadManagerStatus.IN_PROGRESS.id) {
                // no-op
                return@observe
            }

            if (it == getDownloadTimeStamp()) {
                hideProgress()
                showRedownloadUi()
                return@observe
            }

            if (getDownloadTimeStamp() == INIT_TIME_MS) {
                download(it)
                return@observe
            }

            if (getDownloadTimeStamp() != it) {
                hideProgress()
                showNewUpdateUi(it)
                return@observe
            }

            download(it)
        }
    }

    private fun showNewUpdateUi(t: Long) {
        enableChips()
        b.bslbUpdateAvailableBtn.tag = t
        b.bslbUpdateAvailableBtn.visibility = View.VISIBLE
        b.bslbCheckUpdateBtn.visibility = View.GONE
    }

    private fun showRedownloadUi() {
        enableChips()
        b.bslbUpdateAvailableBtn.visibility = View.GONE
        b.bslbCheckUpdateBtn.visibility = View.GONE
        b.bslbRedownloadBtn.visibility = View.VISIBLE
    }

    private fun onRemoteDownloadFailure() {
        isDownloadInitiated = false
        enableChips()
    }

    private fun enableChips() {
        b.bslbUpdateAvailableBtn.isEnabled = true
        b.bslbCheckUpdateBtn.isEnabled = true
        b.bslbRedownloadBtn.isEnabled = true
    }

    private fun onDownloadSuccess() {
        isDownloadInitiated = false
        b.lbVersion.text = getString(R.string.settings_local_blocklist_version,
                                     Utilities.convertLongToTime(getDownloadTimeStamp(),
                                                                 Constants.TIME_FORMAT_2))
        enableChips()
        showRedownloadUi()
        Utilities.showToastUiCentered(requireContext(),
                                      getString(R.string.download_update_dialog_message_success),
                                      Toast.LENGTH_SHORT)
    }

    private fun emptyTempStampInfo() {
        modifiedStamp.postValue(null)
        RethinkBlocklistFragment.selectedFileTags.postValue(mutableSetOf())
    }

    private fun insertRethinkEndpoint(name: String, url: String, count: Int) {
        io {
            var dohName: String = name
            if (name.isBlank()) {
                dohName = url
            }
            val endpoint = RethinkDnsEndpoint(dohName, url, uid = Constants.MISSING_UID, desc = "",
                                              isActive = false, isCustom = true, latency = 0, count,
                                              modifiedDataTime = Constants.INIT_TIME_MS)
            appConfig.insertReplaceEndpoint(endpoint)
            endpoint.isActive = true
            appConfig.handleRethinkChanges(endpoint)
            emptyTempStampInfo()
        }
    }

    private fun updateRethinkEndpoint(name: String, url: String, count: Int) {
        io {
            appConfig.updateRethinkEndpoint(name, url, count)
            appConfig.enableRethinkDnsPlus()
            emptyTempStampInfo()
        }
    }

    // check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" && parsed.host.isNotEmpty() && parsed.path.isNotEmpty() && parsed.query == null && parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) {
            f()
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                f()
            }
        }
    }

}
