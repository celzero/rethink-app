/* Copyright 2020 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.utilities.WelcomeScreen
import com.celzero.bravedns.ui.compose.utilities.WelcomeUiState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class WelcomeActivity : BaseActivity() {
    private val persistentState by inject<PersistentState>()
    private lateinit var presentation: WelcomeUiState
    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)
        enableEdgeToEdge()
        presentation = WelcomeUiState(savedInstanceState?.getInt("welcome.page") ?: 0)
        setContent { RethinkTheme { WelcomeScreen(presentation, ::launchHomeScreen) } }
        onBackPressedDispatcher.addCallback(this) { launchHomeScreen() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("welcome.page", presentation.currentItem)
        super.onSaveInstanceState(outState)
    }
    private fun isDarkThemeOn(): Boolean = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun launchHomeScreen() {
        persistentState.firstTimeLaunch = false
        val intent = Intent(this, HomeScreenActivity::class.java)
        intent.setPackage(this.packageName)
        startActivity(intent)
        finish()
    }

}
