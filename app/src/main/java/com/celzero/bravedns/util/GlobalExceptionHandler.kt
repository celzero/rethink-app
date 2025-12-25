/*
 * Copyright 2025 RethinkDNS and its authors
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
import Logger.LOG_TAG_APP
import android.content.Context
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Global Exception Handler for catching all uncaught exceptions
 * and reporting them to Firebase Analytics/Crashlytics
 */
class GlobalExceptionHandler private constructor(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    contextRef: Context?
) : Thread.UncaughtExceptionHandler, KoinComponent {

    private val contextRef: WeakReference<Context>? = contextRef?.let { WeakReference(it) }
    private val persistentState by inject<PersistentState>()
    companion object {
        private var instance: GlobalExceptionHandler? = null

        /**
         * Initialize the global exception handler
         * This should be called early in the application lifecycle
         */
        fun initialize(ctx: Context) {
            if (instance != null) {
                Logger.w(LOG_TAG_APP, "err-handler already initialized")
                return
            }

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            instance = GlobalExceptionHandler(defaultHandler, ctx.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(instance)

            Logger.i(LOG_TAG_APP, "err-handler initialized successfully")
        }

        /**
         * Get the current instance of the global exception handler
         */
        fun getInstance(): GlobalExceptionHandler? = instance
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val exception = Logger.throwableToException(throwable)
            Logger.e(LOG_TAG_APP, "uncaught exception in thread ${thread.name}", exception)

            // Report to Firebase if available
            reportToFirebase(exception)

            // Add some custom context information
            logExceptionContext(thread, exception)

        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while handling uncaught exception", e)
        } finally {
            // Call the default handler to ensure proper app termination
            defaultHandler?.uncaughtException(thread, throwable) ?: run {
                // If no default handler, terminate the process
                Logger.e(LOG_TAG_APP, "no default exception handler found, terminating process")
                exitProcess(1)
            }
        }
    }

    /**
     * Report the uncaught exception to Firebase
     */
    private fun reportToFirebase(exception: Exception) {
        try {
            FirebaseErrorReporting.recordException(exception)
        } catch (e: Exception) {
            // Firebase might not be available in all build variants (e.g., fdroid)
            Logger.w(LOG_TAG_APP, "crashlytics reporting not available: ${e.message}")
        }
    }

    /**
     * Log additional context information about the exception
     */
    private fun logExceptionContext(thread: Thread, exception: Throwable) {
        try {
            val stackTrace = exception.stackTraceToString()
            @Suppress("DEPRECATION")
            val threadInfo = "Thread: ${thread.name} (ID: ${thread.id}, State: ${thread.state})"

            val stringBuilder = StringBuilder()
            stringBuilder.appendLine("---Uncaught Exception ${thread.name}---")
            stringBuilder.appendLine("Exception Type: ${exception.javaClass.name}")
            stringBuilder.appendLine("Exception Message: ${exception.message}")
            stringBuilder.appendLine(threadInfo)
            stringBuilder.appendLine("Stack Trace:")
            stringBuilder.appendLine(stackTrace)
            stringBuilder.appendLine("--------------------------------------------")
            val msg = stringBuilder.toString()

            // Log cause chain if available
            var cause = exception.cause
            var causeLevel = 1
            while (cause != null && causeLevel <= 5) { // Limit to 5 levels to avoid infinite loops
                Logger.e(LOG_TAG_APP, "caused by (level $causeLevel): ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                causeLevel++
            }
            val ex = Logger.throwableToException(exception)
            Logger.crash(LOG_TAG_APP, msg, ex)

            // Try to write logs to file with context
            writeLogsToFileWithFallback(msg)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while logging exception context", e)
        }
    }

    /**
     * Attempt to write logs to file with fallback options when context is unavailable
     */
    private fun writeLogsToFileWithFallback(msg: String) {
        try {
            // First try: get context from WeakReference
            val context = contextRef?.get()
            if (context != null) {
                val token = persistentState.firebaseUserToken
                EnhancedBugReport.writeLogsToFile(context, token,msg)
                Logger.i(LOG_TAG_APP, "crash logs written to file successfully")
                return
            }

            // Fallback: log warning and ensure the crash info is at least logged
            Logger.w(LOG_TAG_APP, "context is null or has been garbage collected during crash handling")
            Logger.w(LOG_TAG_APP, "attempting to preserve crash info in system logs")

            // Additional fallback: try to write to standard error as last resort
            try {
                System.err.println("=== CRITICAL CRASH INFO (Context Unavailable) ===")
                System.err.println(msg)
                System.err.println("=== END CRITICAL CRASH INFO ===")
            } catch (e: Exception) {
                Logger.e(LOG_TAG_APP, "failed to write crash info to stderr", e)
            }

        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err in writeLogsToFileWithFallback", e)
        }
    }
}
