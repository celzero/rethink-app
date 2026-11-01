/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Logger
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The original sixteen onboarding obligations, on the actual Activity and native UI.
 * A plain Application and strict, memory-only PersistentState replace production startup.
 * Robolectric records the Home Intent; HomeScreenActivity is never created. No permissions,
 * services, manager, database, account, worker, tunnel or network boundary is installed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class WelcomeActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var persistent: PersistentState
    private var controller: ActivityController<WelcomeActivity>? = null
    private var firstLaunch = true
    private var themeId = Themes.LIGHT.id
    private val writes = mutableListOf<Boolean>()

    @Before fun setUp() {
        stopKoin()
        persistent = mockk()
        every { persistent.theme } answers { themeId }
        every { persistent.firstTimeLaunch } answers { firstLaunch }
        every { persistent.firstTimeLaunch = any() } answers {
            firstLaunch = firstArg(); writes += firstArg<Boolean>()
        }
        startKoin { modules(module { single { persistent } }) }
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.v(any(), any()) } just Runs
    }

    @After fun tearDown() { closeHost(); unmockkObject(Logger); stopKoin() }

    private fun launch(saved: Bundle? = null): WelcomeActivity {
        check(controller == null)
        val next = Robolectric.buildActivity(WelcomeActivity::class.java)
        controller = next
        next.create(saved).start().resume().visible()
        compose.waitForIdle()
        return next.get()
    }
    private fun closeHost() {
        controller?.let { it.pause().stop().destroy() }
        controller = null
    }
    private fun next(times: Int = 1) {
        repeat(times) { compose.onNodeWithTag("welcome.next").performClick(); compose.waitForIdle() }
    }
    private fun assertPage(index: Int) {
        compose.onNodeWithContentDescription("${index + 1} / 4").assertIsSelected()
        (0..3).filter { it != index }.forEach {
            compose.onNodeWithContentDescription("${it + 1} / 4").assertIsNotSelected()
        }
    }
    private fun assertCompletion(activity: WelcomeActivity) {
        assertEquals(listOf(false), writes)
        assertFalse(firstLaunch)
        assertTrue(activity.isFinishing)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Completion must dispatch Home", intent)
        assertEquals(HomeScreenActivity::class.java.name, intent.component?.className)
        assertEquals(activity.packageName, intent.`package`)
        assertNull("Completion must not dispatch a second destination", shadowOf(activity).nextStartedActivity)
    }

    @Test fun testPersistentState_MockBehavior() {
        launch()
        assertTrue(persistent.firstTimeLaunch)
        assertTrue("Opening onboarding must not mark completion", writes.isEmpty())
    }
    @Test fun testWelcomeActivity_CanBeCreated() {
        val activity = launch()
        assertEquals(WelcomeActivity::class.java, activity.javaClass)
        assertFalse(activity.isFinishing)
        compose.onNodeWithTag("welcome.pager").assertIsDisplayed()
    }
    @Test fun testWelcomeActivity_KoinIntegration() {
        launch()
        assertSame(persistent, GlobalContext.get().get<PersistentState>())
        verify(atLeast = 1) { persistent.theme }
    }
    @Test fun testLaunchHomeScreen() {
        val activity = launch()
        WelcomeActivity::class.java.getDeclaredMethod("launchHomeScreen")
            .apply { isAccessible = true }.invoke(activity)
        assertCompletion(activity)
    }
    @Test fun testIsDarkThemeOn() {
        val activity = launch()
        val actual = WelcomeActivity::class.java.getDeclaredMethod("isDarkThemeOn")
            .apply { isAccessible = true }.invoke(activity)
        val expected = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        assertEquals(expected, actual)
    }
    @Test fun testWelcomeActivity_MockInteractions() {
        launch(); next(3)
        assertTrue(firstLaunch)
        assertTrue("Page changes are not completion", writes.isEmpty())
        verify(exactly = 0) { persistent.firstTimeLaunch = any() }
    }
    @Test fun testLayoutsArray() {
        launch()
        (1..4).forEach { compose.onNodeWithContentDescription("$it / 4").assertExists() }
        compose.onNodeWithContentDescription("5 / 4").assertDoesNotExist()
    }
    @Test fun testPersistentStateInjection() {
        themeId = Themes.DARK.id
        launch()
        assertSame(persistent, GlobalContext.get().get<PersistentState>())
        verify(atLeast = 1) { persistent.theme }
        assertEquals(Themes.DARK.id, persistent.theme)
    }
    @Test fun testBackPressHandling() {
        val activity = launch()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertCompletion(activity)
    }
    @Test fun testPageChangeListener_LastPageBehavior() {
        launch(); next(3); assertPage(3)
        compose.onNodeWithTag("welcome.next").assertTextEquals(controller!!.get().getString(R.string.finish))
        compose.onNodeWithTag("welcome.skip").assertDoesNotExist()
    }
    @Test fun testViewPager2_ButtonVisibilityOnLastPage() {
        launch(); next(3)
        compose.onNodeWithTag("welcome.next").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("welcome.skip").assertDoesNotExist()
        assertTrue(writes.isEmpty())
    }
    @Test fun testViewPager2_ButtonVisibilityOnFirstPage() {
        val activity = launch(); assertPage(0)
        compose.onNodeWithTag("welcome.next").assertIsDisplayed().assertTextEquals(activity.getString(R.string.next))
        compose.onNodeWithTag("welcome.skip").assertIsDisplayed().assertTextEquals(activity.getString(R.string.skip))
    }
    @Test fun testSkipButton_LaunchesHomeScreen() {
        val activity = launch()
        compose.onNodeWithTag("welcome.skip").performClick()
        assertCompletion(activity)
    }
    @Test fun testNextButton_AdvancesPage() {
        launch(); assertPage(0)
        for (page in 1..3) { next(); assertPage(page) }
        assertTrue(writes.isEmpty())
    }
    @Test fun testNextButton_OnLastPage_LaunchesHomeScreen() {
        val activity = launch(); next(3)
        assertTrue(writes.isEmpty()); next()
        assertCompletion(activity)
    }
    @Test fun testWelcomePagerAdapter_ItemCount() {
        launch()
        compose.onAllNodes(hasContentDescription("1 / 4") or hasContentDescription("2 / 4") or
            hasContentDescription("3 / 4") or hasContentDescription("4 / 4")).assertCountEquals(4)
        assertPage(0)
    }
    @Test fun actualActivitySaveRestoreRetainsPageWithoutCompleting() {
        launch(); next(2)
        val saved = Bundle()
        controller!!.pause().saveInstanceState(saved).stop().destroy(); controller = null
        assertEquals(2, saved.getInt("welcome.page"))
        launch(saved); assertPage(2)
        assertTrue(writes.isEmpty())
        next(); assertPage(3)
    }
    @Test fun allStoredThemesRecreateTheActualHostAndRetainOnboardingPage() {
        launch(); next()
        for (theme in Themes.entries) {
            val saved = Bundle()
            controller!!.pause().saveInstanceState(saved).stop().destroy(); controller = null
            themeId = theme.id
            launch(saved); assertPage(1)
            compose.onNodeWithTag("welcome.next").assertHasClickAction()
            assertTrue(writes.isEmpty())
        }
    }
    // Original device suite contracts merged here to capture, rather than launch, Home.
    @Test fun testWelcomeOnboardingFlow() {
        val activity = launch()
        compose.onNodeWithTag("welcome.next").assertTextEquals(activity.getString(R.string.next))
        for (page in 1..3) { next(); assertPage(page) }
        compose.onNodeWithTag("welcome.next").assertTextEquals(activity.getString(R.string.finish))
        assertTrue(writes.isEmpty())
    }
    @Test fun testSkipButton() {
        val activity = launch()
        compose.onNodeWithTag("welcome.skip").assertIsDisplayed().performClick()
        assertCompletion(activity)
    }

}
