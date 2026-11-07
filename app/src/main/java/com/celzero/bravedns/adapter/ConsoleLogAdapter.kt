/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.recyclerview.widget.DiffUtil
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.ui.compose.trafficlogs.*
class ConsoleLogAdapter(private val context: Context) : LogPagingPresenter<ConsoleLog>(DIFF_CALLBACK) {
    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ConsoleLog>() {
            override fun areItemsTheSame(old: ConsoleLog, new: ConsoleLog) = old.id == new.id
            override fun areContentsTheSame(old: ConsoleLog, new: ConsoleLog) = old == new
        }
    }
    override fun itemKey(value: ConsoleLog): Any = value.id
    @Composable override fun Row(value: ConsoleLog, generation: Int) { ConsoleLogRow(value) }
}
