package com.celzero.bravedns.receiver

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.ResultReceiver
import android.util.Log
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import kotlinx.coroutines.InternalCoroutinesApi
import java.sql.Time
import java.util.*



class ScreenLockService  : Service(){

    private val timer = Timer()
    private var checkLockTask: CheckLockTask? = null

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    @InternalCoroutinesApi
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent != null && intent.action == ACTION_CHECK_LOCK){
            checkLock(intent)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @InternalCoroutinesApi
    private fun checkLock(intent: Intent) {
        val keyguardManager  =  getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val isProtected = keyguardManager.isKeyguardSecure
        val isLocked = keyguardManager.inKeyguardRestrictedInputMode()
        val isInteractive = powerManager.isInteractive
        val delayIndex: Int =  getSafeCheckLockDelay(intent.getIntExtra(EXTRA_CHECK_LOCK_DELAY_INDEX, -1))
        /*Log.i("BraveDNS", java.lang.String.format("LM.checkLock with state=%s, isProtected=%b, isLocked=%b, isInteractive=%b, delay=%d",
                if (intent != null) intent.getStringExtra(EXTRA_STATE) else "",
                isProtected, isLocked, isInteractive, checkLockDelays.get(delayIndex)))*/

        if (checkLockTask != null) {
            //Log.i("BraveDNS", String.format("LM.checkLock: cancelling CheckLockTask[%x]", System.identityHashCode(checkLockTask)))
            checkLockTask!!.cancel()
        }

        if (isProtected && !isLocked && !isInteractive) {
            checkLockTask = CheckLockTask(this, delayIndex)
            /*Log.i("BraveDNS", java.lang.String.format("LM.checkLock: scheduling CheckLockTask[%x] for %d ms",
                    System.identityHashCode(checkLockTask), checkLockDelays.get(delayIndex)))*/
            val task = CheckLockTask(this , checkLockDelays.get(delayIndex))
            timer.schedule(task, checkLockDelays.get(delayIndex).toLong())
        } else {
            //Log.d("BraveDNS", "LM.checkLock: no need to schedule CheckLockTask")
            if (isProtected && isLocked) {
                //Log.e("BraveDNS", "Block Traffic Now!")
                if(PersistentState.getFirewallModeForScreenState(this) && !PersistentState.getScreenLockData(this)) {
                    PersistentState.setScreenLockData(this,true)
                }
            }
        }

    }

    // This tracks the deltas between the actual options of 5s, 15s, 30s, 1m, 2m, 5m, 10m
    // It also includes an initial offset and some extra times (for safety)

    companion object{
        val ACTION_CHECK_LOCK  = "com.celzero.bravedns.receiver.ScreenLockService.ACTION_CHECK_LOCK"
        val EXTRA_CHECK_LOCK_DELAY_INDEX ="com.celzero.bravedns.receiver.ScreenLockService.EXTRA_CHECK_LOCK_DELAY_INDEX"
        val EXTRA_STATE = "com.celzero.bravedns.receiver.ScreenLockService.EXTRA_STATE"

        const val SECOND = 1000
        const val MINUTE = 60 * SECOND
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
        val safeDelayIndex: Int = if (delayIndex >= checkLockDelays.size) {
            checkLockDelays.size - 1
        } else if (delayIndex < 0) {
            0
        } else {
            delayIndex
        }
        return safeDelayIndex
    }
    }


    class CheckLockTask(val context: Context, val delayIndex: Int) : TimerTask() {
        override fun run() {
            val newIntent = Intent(context, ScreenLockService::class.java)
            newIntent.action = ACTION_CHECK_LOCK
            newIntent.putExtra(EXTRA_CHECK_LOCK_DELAY_INDEX, getSafeCheckLockDelay(delayIndex + 1))
            context.startService(newIntent)
        }
    }

}