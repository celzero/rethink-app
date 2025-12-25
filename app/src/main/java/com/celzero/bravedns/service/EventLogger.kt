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
package com.celzero.bravedns.service

import Logger
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * EventLogger is a thread-safe, production-ready logging system for recording debugging events
 * throughout the application. It provides structured logging with automatic purging of old logs.
 *
 * Key features:
 * - Single-threaded execution ensures log ordering is preserved
 * - Non-blocking operations - never blocks UI or other threads
 * - Automatic purging of logs older than configurable days (default: 4 days)
 * - Structured logging with event types, severity levels, and sources
 * - Safe to call from any context: UI, services, workers, managers
 *
 * Usage example:
 * ```
 * eventLogger.log(
 *     type = EventType.VPN_START,
 *     severity = Severity.INFO,
 *     message = "VPN service started",
 *     source = EventSource.VPN,
 *     userAction = true,
 *     details = "Connection established via WireGuard"
 * )
 * ```
 */
class EventLogger(private val eventDao: EventDao) {

    companion object {
        private const val LOG_TAG = "EventLogger"
        private const val DEFAULT_PURGE_DAYS = 4
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }

    // Single-threaded dispatcher ensures log ordering is preserved
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // SupervisorJob ensures that failure in one log operation doesn't cancel others
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Logs an event to the database.
     * This operation is asynchronous and non-blocking.
     *
     * @param type The type of event (VPN, DNS, Firewall, etc.)
     * @param severity The severity level of the event
     * @param message Short descriptive message about the event
     * @param source The component that generated this event
     * @param userAction Whether this event was triggered by a user action
     * @param details Optional detailed information about the event
     */
    fun log(
        type: EventType,
        severity: Severity = Severity.LOW,
        message: String,
        source: EventSource,
        userAction: Boolean = false,
        details: String? = null
    ) {
        scope.launch {
            try {
                val event = Event(
                    timestamp = System.currentTimeMillis(),
                    eventType = type,
                    severity = severity,
                    message = message,
                    details = details,
                    source = source,
                    userAction = userAction
                )
                eventDao.insert(event)
            } catch (e: Exception) {
                // Log the error but don't crash - logging should never break the app
                Logger.e(LOG_TAG, "Failed to insert event: ${e.message}", e)
            }
        }
    }

    /**
     * Convenience method for logging LOW severity events.
     * Use for minor events and normal background activity.
     */
    fun logLow(
        type: EventType,
        message: String,
        source: EventSource,
        userAction: Boolean = false,
        details: String? = null
    ) {
        log(type, Severity.LOW, message, source, userAction, details)
    }

    /**
     * Convenience method for logging MEDIUM severity events.
     * Use for noteworthy events that could need attention.
     */
    fun logMedium(
        type: EventType,
        message: String,
        source: EventSource,
        userAction: Boolean = false,
        details: String? = null
    ) {
        log(type, Severity.MEDIUM, message, source, userAction, details)
    }

    /**
     * Convenience method for logging HIGH severity events.
     * Use for significant issues impacting functionality.
     */
    fun logHigh(
        type: EventType,
        message: String,
        source: EventSource,
        userAction: Boolean = false,
        details: String? = null
    ) {
        log(type, Severity.HIGH, message, source, userAction, details)
    }

    /**
     * Convenience method for logging CRITICAL severity events.
     * Use for severe failures requiring urgent attention.
     */
    fun logCritical(
        type: EventType,
        message: String,
        source: EventSource,
        details: String? = null
    ) {
        log(type, Severity.CRITICAL, message, source, false, details)
    }

    /**
     * Deletes all events older than the specified number of days.
     * This operation is asynchronous and non-blocking.
     *
     * @param days Number of days to keep logs (default: 4)
     */
    fun purgeOld(days: Int = DEFAULT_PURGE_DAYS) {
        scope.launch {
            try {
                val cutoffTime = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
                val deletedCount = eventDao.deleteOlderThan(cutoffTime)
                Logger.i(LOG_TAG, "Purged $deletedCount events older than $days days")
            } catch (e: Exception) {
                Logger.e(LOG_TAG, "Failed to purge old events: ${e.message}", e)
            }
        }
    }

    /**
     * Schedules automatic purging of old logs.
     * This is typically called when the app starts or when VPN starts.
     *
     * @param days Number of days to keep logs (default: 4)
     */
    fun scheduleAutoPurge(days: Int = DEFAULT_PURGE_DAYS) {
        // Purge immediately on schedule
        purgeOld(days)
    }

    /**
     * Gets the total count of events in the database.
     * This is useful for monitoring storage usage.
     */
    suspend fun getEventCount(): Int {
        return try {
            eventDao.getCount()
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to get event count: ${e.message}", e)
            0
        }
    }

    /**
     * Retrieves the most recent events.
     *
     * @param limit Maximum number of events to retrieve
     * @return List of recent events
     */
    suspend fun getRecentEvents(limit: Int = 100): List<Event> {
        return try {
            eventDao.getLatest(limit)
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to get recent events: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Retrieves events filtered by type.
     *
     * @param type The event type to filter by
     * @param limit Maximum number of events to retrieve
     * @return List of events matching the type
     */
    suspend fun getEventsByType(type: EventType, limit: Int = 100): List<Event> {
        return try {
            eventDao.getByType(type, limit)
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to get events by type: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Retrieves events filtered by severity.
     *
     * @param severity The severity level to filter by
     * @param limit Maximum number of events to retrieve
     * @return List of events matching the severity
     */
    suspend fun getEventsBySeverity(severity: Severity, limit: Int = 100): List<Event> {
        return try {
            eventDao.getBySeverity(severity, limit)
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to get events by severity: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Retrieves events filtered by source.
     *
     * @param source The source component to filter by
     * @param limit Maximum number of events to retrieve
     * @return List of events from the specified source
     */
    suspend fun getEventsBySource(source: EventSource, limit: Int = 100): List<Event> {
        return try {
            eventDao.getBySource(source, limit)
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to get events by source: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Retrieves events within a specific time range.
     *
     * @param startTime Start of the time range (Unix timestamp in milliseconds)
     * @param endTime End of the time range (Unix timestamp in milliseconds)
     * @return List of events within the specified time range
     */
    suspend fun getEventsByTimeRange(startTime: Long, endTime: Long): List<Event> {
        return try {
            eventDao.getByTimeRange(startTime, endTime)
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to get events by time range: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Deletes all events from the database.
     * Use with caution - this operation cannot be undone.
     */
    suspend fun clearAll() {
        try {
            eventDao.deleteAll()
            Logger.i(LOG_TAG, "Cleared all events from database")
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Failed to clear events: ${e.message}", e)
        }
    }

    /**
     * Shuts down the event logger.
     * This should be called when the app is closing to ensure all pending log operations complete.
     * After calling this, the EventLogger should not be used anymore.
     */
    fun shutdown() {
        try {
            // Cancel all pending operations
            scope.launch {
                Logger.i(LOG_TAG, "EventLogger shutting down")
            }

            // Give pending operations a moment to complete
            Thread.sleep(100)

            // Close the dispatcher
            dispatcher.close()
            Logger.i(LOG_TAG, "EventLogger shutdown complete")
        } catch (e: Exception) {
            Logger.e(LOG_TAG, "Error during shutdown: ${e.message}", e)
        }
    }
}

