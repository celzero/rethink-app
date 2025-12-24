package com.celzero.bravedns.receiver

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresApi
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.BubbleHelper
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives bubble notification dismissal / actions.
 *
 * We use this to keep the in-app toggle in sync with the real system state:
 * if the user dismisses the bubble (or taps the disable action), we disable the
 * feature in PersistentState and remove the notification/channel shortcuts.
 */
class BubbleDismissReceiver : BroadcastReceiver(), KoinComponent {

    private val persistentState by inject<PersistentState>()

    companion object {
        const val ACTION_BUBBLE_DISMISSED = "com.celzero.bravedns.action.BUBBLE_DISMISSED"
        const val ACTION_BUBBLE_DISABLE = "com.celzero.bravedns.action.BUBBLE_DISABLE"
    }

    @RequiresApi(29)
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            ACTION_BUBBLE_DISMISSED, ACTION_BUBBLE_DISABLE -> {
                Logger.i(LOG_TAG_FIREWALL, "Bubble dismissed/disabled by user")
                persistentState.firewallBubbleEnabled = false
                BubbleHelper.resetBubbleState(context)
            }
        }
    }
}

