package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Required single selection; initialization precedes listener registration, as in the host. */
class RpnChoiceState {
    var checkedId by mutableStateOf(-1)
        private set
    private var listener: ((RpnChoiceState, Int, Boolean) -> Unit)? = null
    fun check(id: Int) {
        if (checkedId == id) return
        val previous = checkedId
        checkedId = id
        if (previous != -1) listener?.invoke(this, previous, false)
        listener?.invoke(this, id, true)
    }
    fun addOnButtonCheckedListener(callback: (RpnChoiceState, Int, Boolean) -> Unit) { listener = callback }
}
