/*
 * Copyright 2020 RethinkDNS and its authors
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
 */
package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_APP_OPS
import Logger.LOG_TAG_FIREWALL
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.MicCamAccessIndicatorBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Utilities.isAtleastN
import com.celzero.bravedns.util.Utilities.isAtleastP
import com.celzero.bravedns.util.Utilities.isAtleastT
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

// cam and mic access is still not working as expected, need to test it
// commented out the ui code for now, will enable it once the feature is working
// for cam and mic access ref: github.com/NitishGadangi/Privacy-Indicator-App/blob/master/app/src/main/java/com/nitish/privacyindicator
// see: developer.android.com/guide/topics/media/camera#kotlin
// see: developer.android.com/guide/topics/media/audio-capture
class BackgroundAccessibilityService : AccessibilityService(), KoinComponent {

    private val persistentState by inject<PersistentState>()
    private lateinit var windowManager: WindowManager
    private lateinit var b: MicCamAccessIndicatorBinding
    private lateinit var lp: WindowManager.LayoutParams

    private var cameraManager: CameraManager? = null
    private var audioManager: AudioManager? = null
    private var micCallback: AudioManager.AudioRecordingCallback? = null
    private var cameraCallback: CameraManager.AvailabilityCallback? = null

    private var cameraOn = false
    private var micOn = false
    private var notifManager: NotificationManagerCompat? = null
    private var notifBuilder: NotificationCompat.Builder? = null
    private var possibleUid: Int? = null
    private var possibleAppName: String? = null
    private val notificationID = 7897

    companion object {
        private const val NOTIF_CHANNEL_ID = "MIC_CAM_ACCESS"
    }

    override fun onServiceConnected() {
        if (isAtleastN() && persistentState.micCamAccess) {
            overlay()
            callBacks()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun callBacks() {
        if (!persistentState.micCamAccess)  return

        try {
            if (cameraManager == null) cameraManager =
                getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager!!.registerAvailabilityCallback(getCameraCallback(), null)

            if (audioManager == null) audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager!!.registerAudioRecordingCallback(getMicCallback(), null)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "Error in registering callbacks: ${e.message}")
        }
    }

    private fun getCameraCallback(): CameraManager.AvailabilityCallback {
        cameraCallback =
            object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    super.onCameraAvailable(cameraId)
                    cameraOn = false
                    hideCam()
                    dismissNotification()
                }

                override fun onCameraUnavailable(cameraId: String) {
                    super.onCameraUnavailable(cameraId)
                    cameraOn = true
                    showCam()
                    showNotification()
                }
            }
        return cameraCallback as CameraManager.AvailabilityCallback
    }

    private fun getMicCallback(): AudioManager.AudioRecordingCallback {
        micCallback =
            @RequiresApi(Build.VERSION_CODES.N)
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    if (configs.isNotEmpty()) {
                        micOn = true
                        showMic()
                        showNotification()
                    } else {
                        micOn = false
                        hideMic()
                        dismissNotification()
                    }
                }
            }
        return micCallback as AudioManager.AudioRecordingCallback
    }

    private fun overlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        lp = WindowManager.LayoutParams()
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = layoutGravity
        b = MicCamAccessIndicatorBinding.inflate(LayoutInflater.from(this))
        windowManager.addView(b.root, lp)
    }

    private fun showMic() {
        Logger.e(LOG_TAG_APP_OPS, "Mic is being used: ${persistentState.micCamAccess}")
        if (persistentState.micCamAccess) {
            updateIndicatorProperties()
            b.ivMic.visibility = View.VISIBLE
        }
    }

    private fun hideMic() {
        b.ivMic.visibility = View.GONE
    }

    private fun showCam() {
        Logger.e(LOG_TAG_APP_OPS, "Camera is being used: ${persistentState.micCamAccess}")
        if (persistentState.micCamAccess) {
            updateIndicatorProperties()
            b.ivCam.visibility = View.VISIBLE
        }
    }

    private fun hideCam() {
        b.ivCam.visibility = View.GONE
    }


    private val layoutGravity: Int
        get() = Gravity.TOP or Gravity.END

    private fun updateIndicatorProperties() {
        updateLayoutGravity()
    }

    private fun updateLayoutGravity() {
        lp.gravity = layoutGravity
        windowManager.updateViewLayout(b.root, lp)
    }

    private fun setupNotification() {
        createNotificationChannel()
        notifBuilder =
            NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(notificationTitle)
                .setContentText(notificationDescription)
                .setContentIntent(getPendingIntent())
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
        notifManager = NotificationManagerCompat.from(applicationContext)
    }

    private val notificationTitle: String
        get() {
            if (cameraOn && micOn) return "Your Camera and Mic is ON"
            if (cameraOn) return "Your Camera is ON"
            return if (micOn) "Your MIC is ON" else "Your Camera or Mic is ON"
        }

    private val notificationDescription: String
        get() {
            if (cameraOn && micOn)
                return "A third-party app($possibleAppName) is using your Camera and Microphone"
            if (cameraOn) return "A third-party app($possibleAppName) is using your Camera"
            return if (micOn) "A third-party app($possibleAppName) is using your Microphone"
            else "A third-party app($possibleAppName) is using your Camera or Microphone"
        }

    private fun showNotification() {
        setupNotification()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            // notification permission request and handling are done in the HomeScreenFragment
            // so no need to handle it here
            return
        }
        if (notifManager != null)
            notifManager!!.notify(notificationID, notifBuilder!!.build())
    }

    private fun dismissNotification() {
        if (cameraOn || micOn) {
            showNotification()
        } else {
            if (notifManager != null) notifManager!!.cancel(notificationID)
        }
    }

    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, AppLockActivity::class.java)
        return PendingIntent.getActivity(
            applicationContext, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        val notificationChannel = "Notifications for Camera and Mic access"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                NotificationChannel(NOTIF_CHANNEL_ID, notificationChannel, importance)
            val description = "Notification for Camera and Mic access"
            channel.description = description
            channel.lightColor = Color.RED
            val notificationManager =
                applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onInterrupt() {
        Logger.w(LOG_TAG_FIREWALL, "BackgroundAccessibilityService Interrupted")
    }

    private fun unRegisterCameraCallBack() {
        try {
            if (cameraManager != null && cameraCallback != null) {
                cameraManager!!.unregisterAvailabilityCallback(cameraCallback!!)
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "Error in unregistering camera callback: ${e.message}")
        }
    }

    private fun unRegisterMicCallback() {
        try {
            if (isAtleastN()) {
                if (audioManager != null && micCallback != null) {
                    audioManager!!.unregisterAudioRecordingCallback(micCallback!!)
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_FIREWALL, "Error in unregistering mic callback: ${e.message}")
        }
    }

    override fun onDestroy() {
        unRegisterCameraCallBack()
        unRegisterMicCallback()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Commenting out the below code - warning during the build process
        /*
        if(DEBUG) {
            val eventText = when (event.eventType) {
                TYPE_VIEW_CLICKED -> "Clicked: "
                TYPE_WINDOWS_CHANGED -> "WindowsChanged: "
                TYPE_ANNOUNCEMENT -> "Announcement: "
                TYPE_ASSIST_READING_CONTEXT -> "AssitReading: "
                TYPE_GESTURE_DETECTION_END -> "GestureDetectionEnd: "
                TYPE_GESTURE_DETECTION_START -> "GestureDetectionStart: "
                TYPE_NOTIFICATION_STATE_CHANGED -> "NotificationStateChanged: "
                TYPE_VIEW_CONTEXT_CLICKED -> "ViewContextClicked: "
                TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "ViewAccessibilityFocused: "
                TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "ViewAccessibilityFocus: "
                TYPE_VIEW_FOCUSED -> "ViewFocused: "
                TYPE_VIEW_HOVER_ENTER -> "ViewHoverEnter: "
                TYPE_VIEW_HOVER_EXIT -> "ViewHoverExit: "
                TYPE_VIEW_LONG_CLICKED -> "ViewLongClicked: "
                TYPE_VIEW_SCROLLED -> "ViewScrolled: "
                CONTENTS_FILE_DESCRIPTOR -> "ContentsFileDesc: "
                CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION -> "ContentChangeTypeDesc: "
                CONTENT_CHANGE_TYPE_PANE_APPEARED -> "ContentChangeTypeAppeared: "
                CONTENT_CHANGE_TYPE_PANE_DISAPPEARED -> "ContentChangeTypeDisappeared: "
                CONTENT_CHANGE_TYPE_PANE_TITLE -> "ContentChangeTypePaneTitle: "
                CONTENT_CHANGE_TYPE_SUBTREE -> "ContentChangeTypeSubtree: "
                CONTENT_CHANGE_TYPE_TEXT -> "ContentChangeTypeText: "
                CONTENT_CHANGE_TYPE_UNDEFINED -> "ContentChangeTypeUndefined: "
                INVALID_POSITION -> "InvalidPosition: "
                PARCELABLE_WRITE_RETURN_VALUE -> "ParacelableWriteReturnValue: "
                TYPE_VIEW_SELECTED -> "TypeViewSelected: "
                TYPE_VIEW_TEXT_CHANGED -> "TypeViewTextChanged: "
                TYPE_VIEW_CONTEXT_CLICKED -> "TypeViewContextClicked: "
                TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TypeViewTextSelectionChanged: "
                TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TypeViewTextTraversedAtGran: "
                TYPE_WINDOW_CONTENT_CHANGED -> "TypeWindowContentChanged: "
                TYPE_WINDOW_STATE_CHANGED -> "TypeWindowStateChanged: "
                TYPE_WINDOWS_CHANGED -> "TypeWindowChanged: "
                WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED -> "WindowsChangedAccFocused: "
                WINDOWS_CHANGE_ACTIVE -> "WindowsChangeActive: "
                WINDOWS_CHANGE_ADDED -> "WindowsChangeAdded: "
                WINDOWS_CHANGE_BOUNDS -> "WindowsChangeBounds: "
                WINDOWS_CHANGE_CHILDREN -> "WindowsChangeChildren: "
                WINDOWS_CHANGE_FOCUSED -> "WindowsChangeFocused: "
                WINDOWS_CHANGE_LAYER -> "WindowsChangeLayer: "
                WINDOWS_CHANGE_PARENT -> "WindowsChangeParent: "
                WINDOWS_CHANGE_PIP -> "WindowsChangePip: "
                WINDOWS_CHANGE_REMOVED -> "WindowsChangeRemoved: "
                WINDOWS_CHANGE_TITLE -> "WindowsChangedTite: "
                TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TypeViewTextSelectionChanged: "
                else -> "Default: "
            } + event.contentDescription + " " + event.text
        }
        Log.w(LOG_TAG_FIREWALL, "onAccessibilityEvent: sourcePkg? " + event.source?.packageName +
                " text? " + eventText + " class? " + event.className + " eventPkg? " + event.packageName)
        */
        handleAccessibilityEvent(event)
    }

    // Handle the received event.
    // Earlier the event handling is taken care in FirewallManager.
    // Now, the firewall manager usage is modified, so moving this part here.
    private fun handleAccessibilityEvent(event: AccessibilityEvent) {

        // ref:
        // https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.html#lifecycle
        // see:
        // https://stackoverflow.com/questions/40433449/how-can-i-programmatically-start-and-stop-an-accessibilityservice
        // no need ot handle the events when the vpn is not running
        @Suppress("DEPRECATION")
        if (!VpnController.isOn()) return

        if (!persistentState.getBlockAppWhenBackground() && !persistentState.micCamAccess) return

        val latestTrackedPackage = getEventPackageName(event)

        if (latestTrackedPackage.isNullOrEmpty()) return

        val hasContentChanged =
            if (isAtleastP()) {
                event.eventType == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            } else {
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            }
        Logger.d(
            LOG_TAG_FIREWALL,
            "onAccessibilityEvent: ${event.packageName}, ${event.eventType}, $hasContentChanged")

        if (!hasContentChanged) return

        // If the package received is Rethink, do nothing.
        if (event.packageName == this.packageName) return

        possibleUid = getEventUid(latestTrackedPackage) ?: return

        possibleAppName = Utilities.getPackageInfoForUid(this, possibleUid!!)?.firstOrNull()

        // https://stackoverflow.com/a/27642535
        // top window is launcher? try revoke queued up permissions
        // FIXME: Figure out a fool-proof way to determine is launcher visible
        // TODO: Handle widgets on the homescreen
        if (isPackageLauncher(latestTrackedPackage)) {
            FirewallManager.untrackForegroundApps()
        } else {
            val uid = possibleUid ?: return
            FirewallManager.trackForegroundApp(uid)
        }
    }

    // https://stackoverflow.com/questions/45620584/event-getsource-returns-null-in-accessibility-service-catch-source-for-a-3rd-p
    /**
     * If the event retrieved package name is null then the check for the package name is carried
     * out in (getRootInActiveWindow)AccessibilityNodeInfo
     */
    private fun getEventPackageName(event: AccessibilityEvent): String? {
        return if (event.packageName.isNullOrBlank()) {
            this.rootInActiveWindow?.packageName?.toString()
        } else {
            event.packageName.toString()
        }
    }

    private fun getEventUid(pkgName: String): Int? {
        if (pkgName.isBlank()) return null

        // #428- Issue fix
        // there is a case where the accessibility services received event from the package
        // which is not part of Android's packageInfo. This method in utilities will handle the
        // error in that case and will return null instead of exception
        // return this.packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA).uid
        return Utilities.getApplicationInfo(this, pkgName)?.uid
    }

    private fun isPackageLauncher(packageName: String?): Boolean {
        if (TextUtils.isEmpty(packageName)) return false

        val intent = Intent("android.intent.action.MAIN")
        intent.addCategory("android.intent.category.HOME")
        // package manager returns null,
        val thisPackage =
            if (isAtleastT()) {
                this.packageManager
                    .resolveActivity(
                        intent,
                        PackageManager.ResolveInfoFlags.of(
                            PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                    ?.activityInfo
                    ?.packageName
            } else {
                this.packageManager
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo
                    ?.packageName
            }
        return thisPackage == packageName
    }
}
