/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import androidx.compose.runtime.*
import com.celzero.bravedns.ui.bottomsheet.SummaryStatsFilterBottomSheet.SectionFilter

/** Emulates the six XML checked chips, including selectionRequired and child-order restoration. */
internal class SummaryFilterSelection(seed: Set<SectionFilter>, private val publish: (Set<SectionFilter>) -> Unit) {
    var selected by mutableStateOf(SectionFilter.entries.toSet())
        private set
    init { replay(seed, notify = false) }
    fun toggle(filter: SectionFilter) = setChecked(filter, filter !in selected, true)
    fun selectAll() { SectionFilter.entries.forEach { setChecked(it, true, true) } }
    fun restore(saved: Set<SectionFilter>) = replay(saved, notify = true)
    private fun replay(target: Set<SectionFilter>, notify: Boolean) {
        // Hidden providers still participate; every child restores its own boolean, not one ID.
        SectionFilter.entries.forEach { setChecked(it, it in target, notify) }
    }
    private fun setChecked(filter: SectionFilter, checked: Boolean, notify: Boolean) {
        if ((filter in selected) == checked) return
        val next = if (checked) selected + filter else selected - filter
        if (next.isEmpty()) return
        selected = SectionFilter.entries.filter { it in next }.toSet()
        if (notify) publish(selected)
    }
}
