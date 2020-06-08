package com.celzero.bravedns.util

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.celzero.bravedns.R

class AdminReceiver : DeviceAdminReceiver() {

    private fun showToast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onEnabled(context: Context, intent: Intent) =
        showToast(context, context.getString(R.string.admin_receiver_status_enabled))

    override fun onDisabled(context: Context, intent: Intent) =
        showToast(context, context.getString(R.string.admin_receiver_status_disabled))
}
