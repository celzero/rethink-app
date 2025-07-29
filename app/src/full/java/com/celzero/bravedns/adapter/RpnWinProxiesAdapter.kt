/*
 * Copyright 2025 RethinkDNS and its authors
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
 *//*

package com.celzero.bravedns.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemRpnCountriesBinding
import com.celzero.bravedns.databinding.ListItemRpnWinProxyBinding
import com.celzero.bravedns.databinding.ListItemWgHopBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.getFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RpnWinProxiesAdapter(
    private val context: Context,
    private val servers: List<RpnProxyManager.RpnWinServer>,
    private var selectedIds: List<String>
) : RecyclerView.Adapter<RpnWinProxiesAdapter.RpnWinServersViewHolder>() {

    companion object {
        private const val TAG = "RpnWinAdapter"
    }

    private var isAttached = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RpnWinServersViewHolder {
        val itemBinding =
            ListItemRpnWinProxyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RpnWinServersViewHolder(itemBinding)
    }

    override fun getItemCount(): Int {
        return servers.size
    }

    override fun onBindViewHolder(holder: RpnWinServersViewHolder, position: Int) {
        holder.update(servers[position])
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        isAttached = true
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        isAttached = false
    }

    inner class RpnWinServersViewHolder(private val b: ListItemRpnWinProxyBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(server: RpnProxyManager.RpnWinServer) {
            b.rwpCcTv.text = server.countryCode
            val selected = selectedIds.contains(server.countryCode)
            b.rwpCheckbox.isChecked =  selectedIds.contains(server.countryCode)
            setCardStroke(selected, true)
            b.rwpNamesTv.text = server.names
            b.rwpAddrTv.text = server.address
            b.rwpFlag.text = getFlag(server.countryCode)
        }

        private fun setCardStroke(isSelected: Boolean, isActive: Boolean) {
            val strokeColor = if (isSelected && isActive) {
                b.rwpCard.strokeWidth = 2
                fetchColor(context, R.attr.chipTextPositive)
            } else if (isSelected) { // selected but not active
                b.rwpCard.strokeWidth = 2
                fetchColor(context, R.attr.chipTextNegative)
            } else {
                b.rwpCard.strokeWidth = 0
                fetchColor(context, R.attr.chipTextNegative)
            }
            b.rwpCard.strokeColor = strokeColor
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
*/
