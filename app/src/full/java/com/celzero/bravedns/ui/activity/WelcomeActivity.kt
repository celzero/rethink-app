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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.ui.theme.BravednsTheme
import com.celzero.bravedns.util.Themes
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class WelcomeActivity : ComponentActivity() {
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        changeStatusBarColor()

        enableEdgeToEdge()
        setContent {
            BravednsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->

                    val coroutineScope = rememberCoroutineScope()
                    val pagerState = rememberPagerState(pageCount = { 2 })

                    val slideContents = listOf(
                        Triple(
                            R.string.slide_1_title,
                            R.string.slide_1_desc,
                            R.drawable.illustrations_welcome_1
                        ), Triple(
                            R.string.slide_2_title,
                            R.string.slide_2_desc,
                            R.drawable.illustrations_welcome_2
                        )
                    )

                    Column(Modifier.padding(padding)) {

                        HorizontalPager(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .padding(16.dp),
                            state = pagerState
                        ) { _ ->

                            val triple = slideContents[pagerState.currentPage]

                            Column(
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    text = getString(triple.first),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.headlineLarge
                                )
                                Text(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    text = getString(triple.second),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                Image(
                                    modifier = Modifier.padding(32.dp),
                                    painter = painterResource(triple.third),
                                    contentDescription = ""
                                )
                            }
                        }

                        Spacer(
                            modifier = Modifier
                                .height(1.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.onSurface)
                        )

                        Row(
                            modifier = Modifier,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(modifier = Modifier
                                .weight(1f)
                                .padding(8.dp), onClick = {
                                launchHomeScreen()
                            }) {
                                Text(
                                    text = "SKIP",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }

                            Row(
                                Modifier
                                    .weight(1f)
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                repeat(pagerState.pageCount) { iteration ->
                                    val color =
                                        if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else Color.Transparent
                                    val borderColor =
                                        if (pagerState.currentPage == iteration) Color.Transparent else MaterialTheme.colorScheme.onBackground
                                    Box(
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .clip(CircleShape)
                                            .border(BorderStroke(1.dp, borderColor), CircleShape)
                                            .background(color)
                                            .size(8.dp)
                                    )
                                }
                            }

                            TextButton(modifier = Modifier
                                .weight(1f)
                                .padding(8.dp), onClick = {
                                coroutineScope.launch {
                                    val currentPage = pagerState.currentPage

                                    if (currentPage == slideContents.size - 1) {
                                        launchHomeScreen()
                                    } else {
                                        pagerState.scrollToPage(currentPage + 1)
                                    }
                                }
                            }) {
                                Text(
                                    text = "NEXT", style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }

                    }
                }
            }
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun changeStatusBarColor() {
        val window: Window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.Transparent.toArgb()
    }

    private fun launchHomeScreen() {
        persistentState.firstTimeLaunch = false
        startActivity(Intent(this, HomeScreenActivity::class.java))
        finish()
    }
}