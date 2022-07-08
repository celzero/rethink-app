package com.celzero.bravedns.ui

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.celzero.bravedns.service.VpnController

class PrepareVpnActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                VpnController.start(this)
            }
            finish()
        }.launch(
            VpnService.prepare(this)
        )
    }
}