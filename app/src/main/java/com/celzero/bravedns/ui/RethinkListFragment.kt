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
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.DialogSetRethinkBinding
import com.celzero.bravedns.databinding.FragmentDohListBinding
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.MalformedURLException
import java.net.URL

class RethinkListFragment : Fragment(R.layout.fragment_rethink_list) {
    private val b by viewBinding(FragmentDohListBinding::bind)

    private val appConfig by inject<AppConfig>()

    // rethink doh ui elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: RethinkEndpointAdapter? = null
    private val viewModel: RethinkEndpointViewModel by viewModel()

    private var uid: Int = Constants.MISSING_UID

    companion object {
        fun newInstance() = RethinkListFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val bundle = this.arguments
        uid = bundle?.getInt("UID", Constants.MISSING_UID) ?: Constants.MISSING_UID
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        recyclerAdapter = RethinkEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        viewModel.setFilter(uid)
        viewModel.rethinkEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter!!::submitList))
        b.recyclerDohConnections.adapter = recyclerAdapter
    }

    private fun initClickListeners() {
        b.dohFabAddServerIcon.setOnClickListener {
            // decide whether to show the dialog or open rethink configure screen
            // showAddCustomDohDialog()
            val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
            requireContext().startActivity(intent)
        }
    }

    /**
     * Shows dialog for custom DNS endpoint configuration
     * If entered DNS end point is valid, then the DNS queries are forwarded to that end point
     * else, it will revert back to default end point
     */
    private fun showAddCustomDohDialog() {
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

        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getDohCount().plus(1)
            uiCtx {
                dialogBinding.dialogCustomNameEditText.setText("RethinkDNS " + nextIndex,
                                                               TextView.BufferType.EDITABLE)
            }
        }

        dialogBinding.dialogCustomNameEditText.setText("RethinkDNS", TextView.BufferType.EDITABLE)
        dialogBinding.dialogCustomUrlOkBtn.setOnClickListener {
            val url = dialogBinding.dialogCustomUrlEditText.text.toString()
            val name = dialogBinding.dialogCustomNameEditText.text.toString()

            if (checkUrl(url)) {
                insertRethinkEndpoint(name, url)
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

        dialogBinding.dialogCustomUrlConfigureBtn.setOnClickListener {
            val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
            requireContext().startActivity(intent)
        }

        dialogBinding.dialogCustomUrlCancelBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun insertRethinkEndpoint(name: String, url: String) {
        io {
            var dohName: String = name
            if (name.isBlank()) {
                dohName = url
            }
            val endpoint = RethinkDnsEndpoint(dohName, url, uid = Constants.MISSING_UID, desc = "", isActive = false,
                                              isCustom = true, latency = 0, modifiedDataTime = Constants.INIT_TIME_MS)
            appConfig.insertReplaceEndpoint(endpoint)
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

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

}
