package com.celzero.bravedns.util

import android.os.Handler
import android.os.Message

class ThrowingHandler:Handler() {
    override fun handleMessage(mesg: Message) {
        throw RuntimeException()
    }
}