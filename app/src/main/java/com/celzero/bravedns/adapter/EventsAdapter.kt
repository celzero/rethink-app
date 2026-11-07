/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.adapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.recyclerview.widget.DiffUtil
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.ui.compose.trafficlogs.*
class EventsAdapter(private val context: Context) : LogPagingPresenter<Event>(EventDiffCallback()) {
    override fun itemKey(value: Event): Any = value.id
    @Composable override fun Row(value: Event, generation: Int) {
        EventLogRow(value) { message ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Event Message", message))
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }
    class EventDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem == newItem
        }
    }

}
