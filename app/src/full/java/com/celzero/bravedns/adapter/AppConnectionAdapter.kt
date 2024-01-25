/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemAppConnDetailsBinding
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.bottomsheet.AppConnectionBottomSheet
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppConnectionAdapter(val context: Context, val uid: Int) :
    PagingDataAdapter<AppConnection, AppConnectionAdapter.ConnectionDetailsViewHolder>(
        DIFF_CALLBACK
    ),
    AppConnectionBottomSheet.OnBottomSheetDialogFragmentDismiss {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {

                override fun areItemsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection.ipAddress == newConnection.ipAddress

                override fun areContentsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection == newConnection
            }
    }

    private lateinit var adapter: AppConnectionAdapter

    // ui component to update/toggle the buttons
    data class ToggleBtnUi(val txtColor: Int, val bgColor: Int)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppConnectionAdapter.ConnectionDetailsViewHolder {
        val itemBinding =
            ListItemAppConnDetailsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(
        holder: AppConnectionAdapter.ConnectionDetailsViewHolder,
        position: Int
    ) {
        val appConnection: AppConnection = getItem(position) ?: return
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(appConnection)
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppConnDetailsBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun update(conn: AppConnection) {
            displayTransactionDetails(conn)
            setupClickListeners(conn)
        }

        private fun setupClickListeners(appConn: AppConnection) {
            b.acdContainer.setOnClickListener {
                // open bottom sheet to apply domain/ip rules
                openBottomSheet(appConn)
            }
        }

        private fun openBottomSheet(appConn: AppConnection) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, "Error opening the app conn bottom sheet")
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet()
            // Fix: free-form window crash
            // all BottomSheetDialogFragment classes created must have a public, no-arg constructor.
            // the best practice is to simply never define any constructors at all.
            // so sending the data using Bundles
            val bundle = Bundle()
            bundle.putInt(AppConnectionBottomSheet.UID, uid)
            bundle.putString(AppConnectionBottomSheet.IP_ADDRESS, appConn.ipAddress)
            bundle.putString(
                AppConnectionBottomSheet.DOMAINS,
                beautifyDomainString(appConn.appOrDnsName ?: "")
            )
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.dismissListener(adapter, absoluteAdapterPosition)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(appConnection: AppConnection) {
            b.acdCount.text = appConnection.count.toString()
            b.acdFlag.text = appConnection.flag
            b.acdIpAddress.text = appConnection.ipAddress
            io {
                val rule =
                    IpRulesManager.isIpRuleAvailable(
                        uid,
                        appConnection.ipAddress,
                        null // don't check for port as adding rule from this screen port is null
                    )
                uiCtx { updateStatusUi(rule) }
            }
            if (!appConnection.appOrDnsName.isNullOrEmpty()) {
                b.acdDomainName.visibility = View.VISIBLE
                b.acdDomainName.text = beautifyDomainString(appConnection.appOrDnsName)
            } else {
                b.acdDomainName.visibility = View.GONE
            }
        }

        private fun beautifyDomainString(d: String): String {
            // replace two commas in the string to one
            // add space after all the commas
            return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
        }

        private fun updateStatusUi(status: IpRulesManager.IpRuleStatus) {
            when (status) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.acdFlag.text = context.getString(R.string.ci_no_rule_initial)
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.acdFlag.text = context.getString(R.string.ci_blocked_initial)
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    b.acdFlag.text = context.getString(R.string.ci_bypass_universal_initial)
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    b.acdFlag.text = context.getString(R.string.ci_trust_initial)
                }
            }

            // returns the text and background color for the button
            val t = getToggleBtnUiParams(status)
            b.acdFlag.setTextColor(t.txtColor)
            b.acdFlag.backgroundTintList = ColorStateList.valueOf(t.bgColor)
        }

        private fun getToggleBtnUiParams(id: IpRulesManager.IpRuleStatus): ToggleBtnUi {
            return when (id) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextNeutral),
                        fetchColor(context, R.attr.chipBgColorNeutral)
                    )
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextNegative),
                        fetchColor(context, R.attr.chipBgColorNegative)
                    )
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextPositive),
                        fetchColor(context, R.attr.chipBgColorPositive)
                    )
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    ToggleBtnUi(
                        fetchColor(context, R.attr.chipTextPositive),
                        fetchColor(context, R.attr.chipBgColorPositive)
                    )
                }
            }
        }
    }

    override fun notifyDataset(position: Int) {
        this.notifyItemChanged(position)
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
