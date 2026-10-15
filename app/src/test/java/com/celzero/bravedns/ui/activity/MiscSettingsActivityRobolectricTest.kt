/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.LauncherSwitcher
import com.celzero.bravedns.ui.compose.networksettings.MiscSettingsState
import com.celzero.bravedns.ui.compose.networksettings.NetworkNativeDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.PcapMode
import com.celzero.bravedns.util.Utilities
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The actual MiscSettingsActivity, with the production Application excluded explicitly.
 * Every original case name remains. All selectors address mounted native semantics.
 * There is no resource-error catch, nullable launch, relaxed mock or skipped assertion.
 *
 * Safety: all injected objects are constructor-free strict MockK doubles. Logger's sinks,
 * Firebase and launcher-alias mutations are replaced before create(); BiometricManager.from
 * returns a strict capability fake. At API 28 permission/bubble branches cannot run. The two
 * API 33 persistent-notification cases keep bubbles false and never request permissions.
 * No PCAP destination, import/export sheet, real scheduler, service or account is opened.
 * See docs/compose-migration/app-settings-host-tests.md for the boundary and case mapping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp")
class MiscSettingsActivityRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var persistent: PersistentState
    private lateinit var config: AppConfig
    private lateinit var refresh: RefreshDatabase
    private lateinit var events: EventLogger
    private lateinit var scheduler: WorkScheduler
    private lateinit var biometric: BiometricManager
    private var controller: ActivityController<MiscSettingsActivity>? = null
    private var logs = false
    private var boot = false
    private var ipInfo = false
    private var tombstone = false
    private var notification = false
    private var theme = 0
    private var pcap = PcapMode.NONE.id
    private var notificationAction = 0
    private var biometricType = 0
    private var biometricTime = 0L
    private var canAuthenticate = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

    @Before fun setUp() {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        check(GlobalContext.getOrNull() == null) { "This fixture must own an empty Koin graph" }
        persistent = mockk()
        config = mockk()
        refresh = mockk()
        events = mockk()
        scheduler = mockk()
        biometric = mockk()
        every { persistent.theme } answers { theme }
        every { persistent.theme = any() } answers { theme = firstArg() }
        every { persistent.logsEnabled } answers { logs }
        every { persistent.logsEnabled = any() } answers { logs = firstArg() }
        every { persistent.prefAutoStartBootUp } answers { boot }
        every { persistent.prefAutoStartBootUp = any() } answers { boot = firstArg() }
        every { persistent.downloadIpInfo } answers { ipInfo }
        every { persistent.downloadIpInfo = any() } answers { ipInfo = firstArg() }
        every { persistent.tombstoneApps } answers { tombstone }
        every { persistent.tombstoneApps = any() } answers { tombstone = firstArg() }
        every { persistent.persistentNotification } answers { notification }
        every { persistent.persistentNotification = any() } answers { notification = firstArg() }
        every { persistent.pcapMode } answers { pcap }
        every { persistent.notificationActionType } answers { notificationAction }
        every { persistent.biometricAuthType } answers { biometricType }
        every { persistent.biometricAuthType = any() } answers { biometricType = firstArg() }
        every { persistent.biometricAuthTime = any() } answers { biometricTime = firstArg() }
        every { persistent.firewallBubbleEnabled } returns false
        every { persistent.firebaseErrorReportingEnabled } returns false
        every { persistent.checkForAppUpdate } returns false
        every { persistent.goLoggerLevel } returns 4L
        every { persistent.logLifespan } returns 6L
        every { persistent.includeFileTrace } returns false
        every { persistent.firebaseUserToken } returns "synthetic-local-token"
        every { persistent.firebaseUserTokenTimestamp } returns System.currentTimeMillis()
        every { persistent.appTriggerPackages } returns ""
        every { persistent.shouldRequestNotificationPermission } returns false
        every { persistent.shouldRequestNotificationPermission = any() } just Runs
        every { persistent.enableStabilityDependentSettings() } returns false
        // The omitted production callback is a compiler-created suspend lambda. Its identity is
        // not a refresh contract; match it explicitly and never invoke it. Action/cardinality
        // remain exact, and the strict fake never enters RefreshDatabase's actor or repository.
        coEvery { refresh.refresh(RefreshDatabase.ACTION_REFRESH_FORCE, any()) } just Runs
        every { events.log(EventType.UI_TOGGLE, Severity.LOW, "Misc settings", EventSource.UI, false, any()) } just Runs
        startKoin { modules(module {
            single { persistent }; single { config }; single { refresh }; single { events }; single { scheduler }
        }) }
        // Neither object has an eager I/O initializer. Stub their external sinks explicitly.
        mockkObject(Logger, FirebaseErrorReporting, LauncherSwitcher)
        every { Logger.v(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { FirebaseErrorReporting.setUserId(any()) } just Runs
        every { LauncherSwitcher.switchLauncherAlias(any(), any(), any()) } just Runs
        mockkStatic(BiometricManager::class)
        every { BiometricManager.from(any()) } returns biometric
        every { biometric.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) } answers { canAuthenticate }
        ShadowDialog.reset()
    }

    @After fun tearDown() {
        // Close windows before destroying the owner; no cleanup exception is swallowed.
        ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.dismiss() }
        controller?.let { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        controller?.let { host -> runBlocking { withTimeout(5000) { requireNotNull(host.get().lifecycleScope.coroutineContext[Job]).join() } } }
        controller = null
        verify { config wasNot Called }
        verify { scheduler wasNot Called }
        unmockkStatic(BiometricManager::class)
        unmockkObject(Logger, FirebaseErrorReporting, LauncherSwitcher)
        stopKoin()
        ShadowDialog.reset()
    }

    private fun launch(saved: Bundle? = null): MiscSettingsActivity {
        check(controller == null)
        val host = Robolectric.buildActivity(MiscSettingsActivity::class.java)
        controller = host
        host.create(saved).start().resume().visible()
        compose.waitForIdle()
        return host.get()
    }
    private fun host() = requireNotNull(controller).get()
    private fun node(id: String) = compose.onNodeWithTag("network.$id", useUnmergedTree = true)
    private fun click(id: String) { node(id).performScrollTo().assertIsDisplayed().performClick() }
    private fun text(id: String, expected: String) { node(id).performScrollTo().assertTextEquals(expected) }
    private fun state(): MiscSettingsState = MiscSettingsActivity::class.java.getDeclaredField("b\$delegate").let {
        it.isAccessible = true
        (it.get(host()) as Lazy<*>).value as MiscSettingsState
    }
    private fun setThemeChanged(value: Boolean) {
        MiscSettingsActivity::class.java.getDeclaredField("isThemeChanged").apply { isAccessible = true }.setBoolean(host(), value)
    }
    private fun themeChanged() = MiscSettingsActivity::class.java.getDeclaredField("isThemeChanged").let {
        it.isAccessible = true; it.getBoolean(host())
    }
    private fun save(): Bundle = Bundle().also { requireNotNull(controller).saveInstanceState(it) }
    private fun invokeImportExport() {
        MiscSettingsActivity::class.java.getDeclaredMethod("invokeImportExport").apply { isAccessible = true }.invoke(host())
    }
    private fun dialog(): Dialog = requireNotNull(ShadowDialog.getLatestDialog()).also {
        assertTrue("The actual native dialog must be open", it.isShowing)
        assertTrue(it is NetworkNativeDialog)
    }
    private fun dialogText(value: String) = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("network.dialog")))
    private fun openDialog(id: String, title: Int): Dialog {
        click(id)
        dialogText(host().getString(title)).assertIsDisplayed()
        return dialog()
    }
    private fun verifyLog(details: String) = verify(exactly = 1) {
        events.log(EventType.UI_TOGGLE, Severity.LOW, "Misc settings", EventSource.UI, false, details)
    }
    private fun assertDisabledAfterClick(id: String, title: Int) {
        launch(); node(id).performScrollTo().assertIsEnabled(); openDialog(id, title)
        node(id).assertIsNotEnabled()
    }
    private fun destroyWithDialog(id: String, title: Int) {
        launch(); val window = openDialog(id, title)
        requireNotNull(controller).pause().stop().destroy()
        assertTrue(host().isDestroyed)
        assertEquals(Lifecycle.State.DESTROYED, host().lifecycle.currentState)
        // Baseline promised safe destruction, not auto-dismissal; close the still-owned test window.
        window.dismiss()
        assertFalse(window.isShowing)
        verify { events wasNot Called }
    }

    @Test fun activityCreation_withMockedDependencies_doesNotCrash() {
        val activity = launch()
        assertFalse(activity.isFinishing)
        node("settings_activity_enable_logs_rl").assertExists()
    }
    @Test fun activityFullLifecycle_createStartResumePauseStop_doesNotCrash() {
        launch(); requireNotNull(controller).pause().stop().destroy()
        assertTrue(host().isDestroyed)
        assertEquals(Lifecycle.State.DESTROYED, host().lifecycle.currentState)
    }
    @Test fun activityClass_isAssignableFromActivity() { assertTrue(Activity::class.java.isAssignableFrom(MiscSettingsActivity::class.java)) }
    @Test fun koinModule_persistentState_isMockInstance() { assertSame(persistent, GlobalContext.get().get<PersistentState>()) }
    @Test fun koinModule_allDependencies_areResolvable() {
        assertSame(persistent, GlobalContext.get().get<PersistentState>())
        assertSame(config, GlobalContext.get().get<AppConfig>())
        assertSame(refresh, GlobalContext.get().get<RefreshDatabase>())
        assertSame(events, GlobalContext.get().get<EventLogger>())
        assertSame(scheduler, GlobalContext.get().get<WorkScheduler>())
    }
    @Test fun onSaveInstanceState_persistsThemeChangedFlagTrue() { launch(); setThemeChanged(true); assertTrue(save().getBoolean("key_theme_change")) }
    @Test fun onSaveInstanceState_persistsThemeChangedFlagFalse() { launch(); assertFalse(save().getBoolean("key_theme_change", true)) }
    @Test fun onCreate_withSavedInstanceState_restoresThemeChangedTrue() { launch(Bundle().apply { putBoolean("key_theme_change", true) }); assertTrue(themeChanged()) }
    @Test fun onCreate_withoutSavedInstanceState_isThemeChangedStartsFalse() { launch(); assertFalse(themeChanged()) }
    @Test fun backPress_withThemeChanged_setsThemeChangedResult() {
        launch(); setThemeChanged(true); host().onBackPressedDispatcher.onBackPressed()
        assertEquals(MiscSettingsActivity.THEME_CHANGED_RESULT, shadowOf(host()).resultCode)
        assertTrue(host().isFinishing)
    }
    @Test fun backPress_withoutThemeChange_doesNotSetThemeChangedResult() {
        launch(); host().onBackPressedDispatcher.onBackPressed()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(host()).resultCode)
        assertTrue(host().isFinishing)
    }
    @Test fun enableLogsRl_click_togglesEnableLogsSwitch() {
        launch(); click("settings_activity_enable_logs_rl"); node("settings_activity_enable_logs_switch").assertIsOn()
        verify(exactly = 1) { persistent.logsEnabled = true }; verifyLog("Logs enabled set to true")
    }
    @Test fun enableLogsSwitch_checkedTrue_setsLogsEnabled() {
        launch(); click("settings_activity_enable_logs_switch"); node("settings_activity_enable_logs_switch").assertIsOn()
        verify(exactly = 1) { persistent.logsEnabled = true }
    }
    @Test fun enableLogsSwitch_checkedFalse_setsLogsEnabledFalse() {
        logs = true; launch(); click("settings_activity_enable_logs_switch"); node("settings_activity_enable_logs_switch").assertIsOff()
        verify(exactly = 1) { persistent.logsEnabled = false }; verifyLog("Logs enabled set to false")
    }
    @Test fun autoStartRl_click_togglesAutoStartSwitch() {
        launch(); click("settings_activity_auto_start_rl"); node("settings_activity_auto_start_switch").assertIsOn()
        verify(exactly = 1) { persistent.prefAutoStartBootUp = true }
    }
    @Test fun autoStartSwitch_checkedTrue_setsPrefAutoStartBootUp() {
        launch(); click("settings_activity_auto_start_switch"); verify(exactly = 1) { persistent.prefAutoStartBootUp = true }
        verify(exactly = 1) { persistent.enableStabilityDependentSettings() }
    }
    @Test fun ipInfoRl_click_togglesDvIpInfoSwitch() {
        launch(); click("settings_ip_info_rl"); node("dv_ip_info_switch").assertIsOn()
        verify(exactly = 1) { persistent.downloadIpInfo = true }; verifyLog("Download ipinfo inc set to true")
    }
    @Test fun dvIpInfoSwitch_checkedTrue_setsDownloadIpInfo() {
        launch(); click("dv_ip_info_switch"); verify(exactly = 1) { persistent.downloadIpInfo = true }
    }
    @Test fun tombstoneAppRl_click_togglesTombstoneAppSwitch() {
        launch(); click("tombstone_app_rl"); node("tombstone_app_switch").assertIsOn()
        verify(exactly = 1) { persistent.tombstoneApps = true }
        coVerify(timeout = 2000, exactly = 1) { refresh.refresh(RefreshDatabase.ACTION_REFRESH_FORCE, any()) }
    }
    @Test fun tombstoneAppSwitch_checkedTrue_setsTombstoneApps() {
        launch(); click("tombstone_app_switch"); verify(exactly = 1) { persistent.tombstoneApps = true }
        coVerify(timeout = 2000, exactly = 1) { refresh.refresh(RefreshDatabase.ACTION_REFRESH_FORCE, any()) }
    }
    @Test fun tombstoneAppSwitch_checked_triggersRefreshDatabase() {
        launch(); click("tombstone_app_switch")
        coVerify(timeout = 2000, exactly = 1) { refresh.refresh(RefreshDatabase.ACTION_REFRESH_FORCE, any()) }
        verifyLog("Tombstone apps set to true")
    }
    @Test fun tombstoneAppSwitch_toggle_thenActivityDestroyed_doesNotCrash() {
        launch(); click("tombstone_app_switch")
        coVerify(timeout = 2000, exactly = 1) { refresh.refresh(RefreshDatabase.ACTION_REFRESH_FORCE, any()) }
        requireNotNull(controller).pause().stop().destroy(); assertTrue(host().isDestroyed)
        verify(exactly = 1) { persistent.tombstoneApps = true }
    }
    @Test @Config(sdk = [33]) fun persistentNotificationRl_click_togglesSwitch() {
        launch(); click("settings_activity_app_notification_persistent_rl")
        node("settings_activity_app_notification_persistent_switch").assertIsOn()
        verify(exactly = 1) { persistent.persistentNotification = true }
    }
    @Test @Config(sdk = [33]) fun persistentNotificationSwitch_checkedTrue_setsPersistentNotification() {
        launch(); click("settings_activity_app_notification_persistent_switch")
        verify(exactly = 1) { persistent.persistentNotification = true }; verifyLog("Persistent notification set to true")
    }
    @Test fun pcapRl_click_createsAlertDialog() { launch(); openDialog("settings_activity_pcap_rl", R.string.settings_pcap_dialog_title) }
    @Test fun pcapDialog_dismiss_closesWithoutCrash() {
        launch(); val window = openDialog("settings_activity_pcap_rl", R.string.settings_pcap_dialog_title)
        window.dismiss(); assertFalse(window.isShowing); verify { events wasNot Called }
    }
    @Test fun themeRl_click_createsAlertDialog() { launch(); openDialog("settings_activity_theme_rl", R.string.settings_theme_dialog_title) }
    @Test fun notificationRl_click_createsAlertDialog() { launch(); openDialog("settings_activity_notification_rl", R.string.settings_notification_dialog_title) }
    @Test fun goLogRl_click_createsAlertDialog() { launch(); openDialog("settings_go_log_rl", R.string.settings_go_log_heading) }
    @Test fun taskerRl_click_createsAlertDialog() { launch(); openDialog("settings_tasker_rl", R.string.adv_taster_title) }
    @Test fun showAppTriggerPackageDialog_isDisplayed() {
        launch(); host().showAppTriggerPackageDialog(host()) { error("Showing a dialog cannot save a package") }
        dialogText(host().getString(R.string.adv_taster_title)).assertIsDisplayed(); dialog()
    }
    @Test fun showAppTriggerPackageDialog_negativeButton_doesNotTriggerCallback() {
        launch(); val captured = mutableListOf<String>()
        host().showAppTriggerPackageDialog(host()) { captured.add(it) }; val window = dialog()
        dialogText(host().getString(R.string.lbl_cancel)).performClick()
        assertFalse(window.isShowing); assertTrue(captured.isEmpty()); verify { events wasNot Called }
    }
    @Test fun importExportRl_whenActivityIsFinishing_doesNotShowBottomSheet() {
        launch(); host().finish(); val before = host().supportFragmentManager.fragments.toList()
        // A finishing window no longer accepts native input. Exercise its retained callback guard.
        state().settingsActivityImportExportRl.performClick()
        assertEquals(before, host().supportFragmentManager.fragments)
        assertTrue(host().isFinishing); assertNull(ShadowDialog.getLatestDialog())
    }
    @Test fun invokeImportExport_whenActivityIsDestroyed_doesNotShowBottomSheet() {
        launch(); requireNotNull(controller).pause().stop().destroy(); invokeImportExport()
        assertTrue(host().isDestroyed); assertTrue(host().supportFragmentManager.fragments.isEmpty())
        assertNull(ShadowDialog.getLatestDialog())
    }
    @Test fun onResume_api28_notificationSectionIsHidden() {
        launch(); node("settings_activity_app_notification_rl").assertDoesNotExist()
        node("divider_notification").assertDoesNotExist(); node("settings_activity_app_notification_persistent_rl").assertDoesNotExist()
    }
    @Test fun onResume_api28_bubbleSectionIsHidden() { launch(); node("settings_firewall_bubble_rl").assertDoesNotExist() }
    @Test fun fdroidFlavour_firebaseSectionIsGone() {
        launch()
        if (Utilities.isPlayStoreFlavour()) node("settings_firebase_error_reporting_rl").assertExists()
        else node("settings_firebase_error_reporting_rl").assertDoesNotExist()
        if (Utilities.isFdroidFlavour()) node("settings_activity_check_update_rl").assertDoesNotExist()
        else node("settings_activity_check_update_rl").assertExists()
    }
    @Test fun displayPcapUi_noneMode_showsOptionOneText() { launch(); text("settings_activity_pcap_desc", host().getString(R.string.settings_pcap_dialog_option_1)) }
    @Test fun displayPcapUi_logcatMode_showsOptionTwoText() { pcap = PcapMode.LOGCAT.id; launch(); text("settings_activity_pcap_desc", host().getString(R.string.settings_pcap_dialog_option_2)) }
    @Test fun displayPcapUi_externalFileMode_showsOptionThreeText() { pcap = PcapMode.EXTERNAL_FILE.id; launch(); text("settings_activity_pcap_desc", host().getString(R.string.settings_pcap_dialog_option_3)) }
    @Test fun displayNotificationActionUi_pauseStop_showsDesc1() { launch(); node("gen_settings_notification_desc").assertTextContains(host().getString(R.string.settings_notification_desc1), substring = true) }
    @Test fun displayNotificationActionUi_dnsFirewall_showsDesc2() { notificationAction = 1; launch(); node("gen_settings_notification_desc").assertTextContains(host().getString(R.string.settings_notification_desc2), substring = true) }
    @Test fun displayNotificationActionUi_none_showsDesc3() { notificationAction = 2; launch(); node("gen_settings_notification_desc").assertTextContains(host().getString(R.string.settings_notification_desc3), substring = true) }
    @Test fun displayAppThemeUi_systemDefault_showsTheme1Text() { launch(); node("gen_settings_theme_desc").assertTextContains(host().getString(R.string.settings_theme_dialog_themes_1), substring = true) }
    @Test fun displayAppThemeUi_light_showsTheme2Text() { theme = 1; launch(); node("gen_settings_theme_desc").assertTextContains(host().getString(R.string.settings_theme_dialog_themes_2), substring = true) }
    @Test fun displayAppThemeUi_dark_showsTheme3Text() { theme = 2; launch(); node("gen_settings_theme_desc").assertTextContains(host().getString(R.string.settings_theme_dialog_themes_3), substring = true) }
    @Test fun pcapRl_afterClick_isDisabledImmediately() { assertDisabledAfterClick("settings_activity_pcap_rl", R.string.settings_pcap_dialog_title) }
    @Test fun themeRl_afterClick_isDisabledImmediately() { assertDisabledAfterClick("settings_activity_theme_rl", R.string.settings_theme_dialog_title) }
    @Test fun notificationRl_afterClick_isDisabledImmediately() { assertDisabledAfterClick("settings_activity_notification_rl", R.string.settings_notification_dialog_title) }
    @Test fun goLogRl_afterClick_isDisabledImmediately() { assertDisabledAfterClick("settings_go_log_rl", R.string.settings_go_log_heading) }
    @Test fun pcapDialog_activityDestroyedWhileShowing_doesNotCrash() { destroyWithDialog("settings_activity_pcap_rl", R.string.settings_pcap_dialog_title) }
    @Test fun themeDialog_activityDestroyedWhileShowing_doesNotCrash() { destroyWithDialog("settings_activity_theme_rl", R.string.settings_theme_dialog_title) }
    @Test fun notificationDialog_activityDestroyedWhileShowing_doesNotCrash() { destroyWithDialog("settings_activity_notification_rl", R.string.settings_notification_dialog_title) }
    @Test fun setFirebaseUserId_withValidToken_doesNotThrow() { launch(); host().setFirebaseUserId("synthetic-token"); verify(exactly = 1) { FirebaseErrorReporting.setUserId("synthetic-token") } }
    @Test fun setFirebaseUserId_withEmptyToken_doesNotThrow() { launch(); host().setFirebaseUserId(""); verify(exactly = 1) { FirebaseErrorReporting.setUserId("") } }
    @Test fun activityRecreation_preservesThemeChangedFlag() {
        launch(); setThemeChanged(true); assertTrue(save().getBoolean("key_theme_change"))
        requireNotNull(controller).recreate(); compose.waitForIdle()
        assertTrue(themeChanged()); node("settings_activity_enable_logs_rl").assertExists()
    }
    @Test fun pcapRl_rapidDoubleClick_onlyOneDialogCreated() {
        launch(); click("settings_activity_pcap_rl"); val first = dialog()
        node("settings_activity_pcap_rl").assertIsNotEnabled().performClick()
        assertSame(first, ShadowDialog.getLatestDialog())
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
    }
    @Test fun enableLogsSwitch_initialState_trueWhenLogsEnabled() { logs = true; launch(); node("settings_activity_enable_logs_switch").assertIsOn(); verify(exactly = 0) { persistent.logsEnabled = any() } }
    @Test fun autoStartSwitch_initialState_trueWhenPrefAutoStartBootUpTrue() { boot = true; launch(); node("settings_activity_auto_start_switch").assertIsOn(); verify(exactly = 0) { persistent.prefAutoStartBootUp = any() } }
    @Test fun dvIpInfoSwitch_initialState_trueWhenDownloadIpInfoTrue() { ipInfo = true; launch(); node("dv_ip_info_switch").assertIsOn(); verify(exactly = 0) { persistent.downloadIpInfo = any() } }
    @Test fun tombstoneSwitch_initialState_trueWhenTombstoneAppsTrue() { tombstone = true; launch(); node("tombstone_app_switch").assertIsOn(); verify(exactly = 0) { persistent.tombstoneApps = any() }; coVerify(exactly = 0) { refresh.refresh(any(), any()) } }
    @Test fun tombstoneSwitch_toggleAfterPause_doesNotCrash() {
        launch(); requireNotNull(controller).pause()
        // Preserve the original programmatic-listener contract while the Activity is STARTED.
        state().tombstoneAppSwitch.isChecked = true
        coVerify(timeout = 2000, exactly = 1) { refresh.refresh(RefreshDatabase.ACTION_REFRESH_FORCE, any()) }
        verify(exactly = 1) { persistent.tombstoneApps = true }
        requireNotNull(controller).resume(); node("tombstone_app_switch").assertIsOn()
    }

    @Test fun biometricUnavailable_hidesNativeRow_withoutRequestingAuthentication() {
        launch(); node("settings_biometric_rl").assertDoesNotExist()
        verify(exactly = 1) { biometric.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) }
        verify(exactly = 0) { LauncherSwitcher.switchLauncherAlias(any(), any(), any()) }
    }
    @Test fun biometricChoice_immediate_writesOnce_andOnlyRecordsAliasChange() {
        canAuthenticate = BiometricManager.BIOMETRIC_SUCCESS; launch(); click("settings_biometric_rl")
        dialogText(host().getString(R.string.settings_biometric_dialog_option_1)).performClick()
        verify(exactly = 1) { persistent.biometricAuthType = 1 }
        assertEquals(Constants.INIT_TIME_MS, biometricTime)
        verify(exactly = 1) { LauncherSwitcher.switchLauncherAlias(any(), AppLockActivity.APP_LOCK_ALIAS, AppLockActivity.HOME_ALIAS) }
        node("settings_biometric_desc").assertTextContains(host().getString(R.string.settings_biometric_dialog_option_1), substring = true)
    }
}
