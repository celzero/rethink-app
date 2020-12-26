package com.celzero.bravedns.receiver

import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object ReceiverHelper:KoinComponent {
    val persistentState by inject<PersistentState>()
}