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

    override fun onEnabled(context: Context, intent: Intent) = showToast(context, context.getString(
        R.string.admin_receiver_status_enabled))

    override fun onDisabled(context: Context, intent: Intent) = showToast(context,
                                                                          context.getString(
                                                                              R.string.admin_receiver_status_disabled))
}
