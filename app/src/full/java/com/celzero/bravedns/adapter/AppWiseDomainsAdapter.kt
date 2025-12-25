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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemAppDomainDetailsBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.AppDomainRulesBottomSheet
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.log2

class AppWiseDomainsAdapter(
    val context: Context,
    val lifecycleOwner: LifecycleOwner,
    val uid: Int,
    val isActiveConn: Boolean = false
) :
    PagingDataAdapter<AppConnection, AppWiseDomainsAdapter.ConnectionDetailsViewHolder>(
        DIFF_CALLBACK
    ),
    AppDomainRulesBottomSheet.OnBottomSheetDialogFragmentDismiss {

    private var maxValue: Int = 0
    private var minPercentage: Int = 100

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {

                override fun areItemsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection == newConnection

                override fun areContentsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection == newConnection
            }

        private const val TAG = "AppWiseDomainsAdapter"
    }

    private lateinit var adapter: AppWiseDomainsAdapter

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ConnectionDetailsViewHolder {
        val itemBinding =
            ListItemAppDomainDetailsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(
        holder: ConnectionDetailsViewHolder,
        position: Int
    ) {
        val appConnection: AppConnection = getItem(position) ?: return
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(appConnection)
    }

    private fun calculatePercentage(c: Double): Int {
        val value = (log2(c) * 100).toInt()
        // maxValue will be based on the count returned by db query (order by count desc)
        if (value > maxValue) {
            maxValue = value
        }
        return if (maxValue == 0) {
            0
        } else {
            val percentage = (value * 100 / maxValue)
            // minPercentage is used to show the progress bar when the percentage is 0
            if (percentage < minPercentage && percentage != 0) {
                minPercentage = percentage
            }
            percentage
        }
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppDomainDetailsBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun update(conn: AppConnection) {
            displayTransactionDetails(conn)
            setupClickListeners(conn)
        }

        private fun displayTransactionDetails(conn: AppConnection) {
            // handle active connections specially, no need to show progress bar,
            // asn info will be added in the appOrDnsName field
            if (isActiveConn) {
                b.progress.visibility = View.GONE
                b.acdCount.text = conn.count.toString()
                b.acdDomain.text = beautifyIpString(conn.ipAddress)
                if (conn.appOrDnsName.isNullOrEmpty()) {
                    b.acdIpAddress.text = ""
                } else {
                    b.acdIpAddress.text = conn.appOrDnsName
                }
                b.acdFlag.visibility = View.VISIBLE
                b.acdFlag.text = conn.flag
                return
            }

            b.acdCount.text = conn.count.toString()
            b.acdDomain.text = conn.appOrDnsName
            b.acdFlag.text = conn.flag
            if (conn.ipAddress.isNotEmpty()) {
                b.acdIpAddress.visibility = View.VISIBLE
                b.acdIpAddress.text = beautifyIpString(conn.ipAddress)
            } else {
                b.acdIpAddress.visibility = View.GONE
            }
            updateStatusUi(conn)
        }

        private fun setupClickListeners(conn: AppConnection) {
            b.acdContainer.setOnClickListener {
                if (isActiveConn) {
                    showCloseConnectionDialog(conn)
                    return@setOnClickListener
                }
                // open bottom sheet to apply domain/ip rules
                openBottomSheet(conn)
            }
        }

        private fun showCloseConnectionDialog(appConn: AppConnection) {
            if (context !is AppCompatActivity) {
                Logger.w(LOG_TAG_UI, "$TAG err showing close connection dialog")
                return
            }

            /*if (isRethink) {
                Logger.i(LOG_TAG_UI, "$TAG rethink connection - no close connection dialog")
                return
            }*/
            Logger.v(LOG_TAG_UI, "$TAG show close connection dialog for uid: $uid")
            val dialog = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
                .setTitle(context.getString(R.string.close_conns_dialog_title))
                .setMessage(context.getString(R.string.close_conns_dialog_desc, appConn.ipAddress))
                .setPositiveButton(R.string.lbl_proceed) { _, _ ->
                    // close the connection
                    VpnController.closeConnectionsByUidDomain(appConn.uid, appConn.ipAddress, "app-wise-domains-manual-close")
                    Logger.i(
                        LOG_TAG_UI,
                        "$TAG closed connection for uid: ${appConn.uid}, domain: ${appConn.appOrDnsName}"
                    )
                    showToastUiCentered(
                        context,
                        context.getString(R.string.config_add_success_toast),
                        Toast.LENGTH_LONG
                    )
                }
                .setNegativeButton(R.string.lbl_cancel, null)
                .create()
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        }

        private fun openBottomSheet(appConn: AppConnection) {
            if (context !is AppCompatActivity) {
                Logger.w(LOG_TAG_UI, "$TAG err opening the app conn bottom sheet")
                return
            }

            /*if (isRethink) {
                Logger.i(LOG_TAG_UI, "$TAG rethink connection - no bottom sheet")
                return
            }*/

            if (isActiveConn) {
                Logger.i(LOG_TAG_UI, "$TAG active connection - no bottom sheet")
                return
            }

            Logger.v(LOG_TAG_UI, "$TAG open bottom sheet for uid: $uid, ip: ${appConn.ipAddress}, domain: ${appConn.appOrDnsName}")
            val bottomSheetFragment = AppDomainRulesBottomSheet()
            // Fix: free-form window crash
            // all BottomSheetDialogFragment classes created must have a public, no-arg constructor.
            // the best practice is to simply never define any constructors at all.
            // so sending the data using Bundles
            val bundle = Bundle()
            bundle.putInt(AppDomainRulesBottomSheet.UID, uid)
            bundle.putString(AppDomainRulesBottomSheet.DOMAIN, appConn.appOrDnsName)
            bottomSheetFragment.arguments = bundle
            // Fix: Validate position before passing to avoid IndexOutOfBoundsException
            val currentPosition = absoluteAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) {
                bottomSheetFragment.dismissListener(adapter, currentPosition)
            } else {
                // Position is invalid, pass -1 to indicate refresh should be used
                Logger.w(LOG_TAG_UI, "$TAG invalid adapter position when opening bottom sheet")
                bottomSheetFragment.dismissListener(adapter, RecyclerView.NO_POSITION)
            }
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun beautifyIpString(d: String): String {
            // replace two commas in the string to one
            // add space after all the commas
            return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
        }

        private fun updateStatusUi(conn: AppConnection) {
            if (conn.appOrDnsName.isNullOrEmpty()) {
                b.progress.visibility = View.GONE
                return
            }
            val status = DomainRulesManager.status(conn.appOrDnsName, uid)
            Logger.vv(LOG_TAG_UI, "$TAG domain: ${conn.appOrDnsName}, status: $status")
            when (status) {
                DomainRulesManager.Status.NONE -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.chipTextNeutral)
                    )
                }
                DomainRulesManager.Status.BLOCK -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.accentBad)
                    )
                }
                DomainRulesManager.Status.TRUST -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.accentGood)
                    )
                }
            }

            var p = calculatePercentage(conn.count.toDouble())
            if (p == 0) {
                p = minPercentage / 2
            }

            if (Utilities.isAtleastN()) {
                b.progress.setProgress(p, true)
            } else {
                b.progress.progress = p
            }
        }
    }

    override fun notifyDataset(position: Int) {
        // Fix: IndexOutOfBoundsException - validate position before notifying
        try {
            if (position >= 0 && position < itemCount) {
                notifyItemChanged(position)
            } else {
                // Position is invalid, refresh the entire dataset instead
                Logger.w(LOG_TAG_UI, "$TAG invalid position: $position, itemCount: $itemCount, refreshing adapter")
                refresh()
            }
        } catch (e: Exception) {
            // If notification fails, refresh the adapter to ensure consistency
            Logger.e(LOG_TAG_UI, "$TAG error notifying position $position: ${e.message}", e)
            refresh()
        }
    }
}
