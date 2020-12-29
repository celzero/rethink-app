/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.receiver

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import org.koin.android.ext.android.inject
import java.util.*

class DeviceLockService  : Service(){

    private val timer = Timer()
    private var checkLockTask: CheckLockTask? = null
    private val persistentState by inject<PersistentState>()

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent != null && intent.action == ACTION_CHECK_LOCK){
            checkLock(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun checkLock(intent: Intent) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val isProtected = keyguardManager.isKeyguardSecure
        val isLocked = keyguardManager.isKeyguardLocked
        val isInteractive = powerManager.isInteractive
        val delayIndex: Int = getSafeCheckLockDelay(intent.getIntExtra(EXTRA_CHECK_LOCK_DELAY_INDEX, -1))


        if (checkLockTask != null) {
            checkLockTask!!.cancel()
        }

        if (isProtected && !isLocked && !isInteractive) {
            checkLockTask = CheckLockTask(this, delayIndex)
            timer.schedule(checkLockTask, checkLockDelays[delayIndex].toLong())
            this.stopSelf()
        } else {
            if (isProtected && isLocked) {
                if (persistentState.getFirewallModeForScreenState() && !persistentState.getScreenLockData()) {
                    if(DEBUG) Log.d(LOG_TAG,"DeviceLockService : Screen lock detected at $delayIndex")
                    persistentState.setScreenLockData(true)
                    checkLockTask?.cancel()
                    timer.cancel()
                    this.stopSelf()
                }
            }
        }
    }


    companion object{
        const val ACTION_CHECK_LOCK  = "com.celzero.bravedns.receiver.DeviceLockService.ACTION_START_SERVICE"
        const val EXTRA_CHECK_LOCK_DELAY_INDEX ="com.celzero.bravedns.receiver.DeviceLockService.EXTRA_CHECK_LOCK_DELAY_INDEX"
        const val EXTRA_STATE = "com.celzero.bravedns.receiver.DeviceLockService..EXTRA_STATE"

        private const val SECOND = 1000
        private const val MINUTE = 60 * SECOND
        val checkLockDelays = intArrayOf(
            1 * SECOND,
            5 * SECOND,
            10 * SECOND,
            20 * SECOND,
            30 * SECOND,
            1 * MINUTE,
            3 * MINUTE,
            5 * MINUTE,
            10 * MINUTE,
            30 * MINUTE
        )

        fun getSafeCheckLockDelay(delayIndex: Int): Int {
            return if (delayIndex >= checkLockDelays.size) {
                checkLockDelays.size - 1
            } else if (delayIndex < 0) {
                0
            } else {
                delayIndex
            }
        }
    }

    class CheckLockTask(val context: Context, private val delayIndex: Int) : TimerTask() {
        override fun run() {
            val newIntent = Intent(context, DeviceLockService::class.java)
            newIntent.action = ACTION_CHECK_LOCK
            newIntent.putExtra(EXTRA_CHECK_LOCK_DELAY_INDEX, getSafeCheckLockDelay(delayIndex + 1))
            context.startService(newIntent)
        }
    }

}