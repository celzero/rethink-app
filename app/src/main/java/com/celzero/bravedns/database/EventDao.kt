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
package com.celzero.bravedns.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for Event entities.
 * Provides methods for inserting, querying, and deleting event logs.
 */
@Dao
interface EventDao {

    /**
     * Inserts a new event into the database.
     * If a conflict occurs, the new event will replace the old one.
     *
     * @param event The event to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event)

    /**
     * Deletes all events older than the specified cutoff timestamp.
     * Used for automatic purging of old logs.
     *
     * @param cutoff Unix timestamp in milliseconds. Events before this time will be deleted.
     * @return Number of events deleted
     */
    @Query("DELETE FROM Events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Retrieves the most recent events, ordered by timestamp descending.
     *
     * @param limit Maximum number of events to retrieve
     * @return List of recent events
     */
    @Query("SELECT * FROM Events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<Event>

    /**
     * Retrieves events filtered by event type.
     *
     * @param eventType The type of events to retrieve
     * @param limit Maximum number of events to retrieve
     * @return List of events matching the specified type
     */
    @Query("SELECT * FROM Events WHERE eventType = :eventType ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(eventType: EventType, limit: Int): List<Event>

    /**
     * Retrieves events filtered by severity level.
     *
     * @param severity The severity level to filter by
     * @param limit Maximum number of events to retrieve
     * @return List of events matching the specified severity
     */
    @Query("SELECT * FROM Events WHERE severity = :severity ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySeverity(severity: Severity, limit: Int): List<Event>

    /**
     * Retrieves events filtered by source component.
     *
     * @param source The source component to filter by
     * @param limit Maximum number of events to retrieve
     * @return List of events from the specified source
     */
    @Query("SELECT * FROM Events WHERE source = :source ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySource(source: EventSource, limit: Int): List<Event>

    /**
     * Retrieves events within a specific time range.
     *
     * @param startTime Start of the time range (inclusive)
     * @param endTime End of the time range (inclusive)
     * @return List of events within the specified time range
     */
    @Query("SELECT * FROM Events WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getByTimeRange(startTime: Long, endTime: Long): List<Event>

    /**
     * Gets the total count of events in the database.
     *
     * @return Total number of events
     */
    @Query("SELECT COUNT(*) FROM Events")
    suspend fun getCount(): Int

    /**
     * Deletes all events from the database.
     * Use with caution - this operation cannot be undone.
     */
    @Query("DELETE FROM Events")
    suspend fun deleteAll()

    // PagingSource methods for efficient pagination

    /**
     * Retrieves all events with pagination support.
     */
    @Query("SELECT * FROM Events ORDER BY timestamp DESC")
    fun getAllEventsPaged(): androidx.paging.PagingSource<Int, Event>

    /**
     * Retrieves events filtered by search query with pagination.
     */
    @Query("SELECT * FROM Events WHERE LOWER(message) LIKE LOWER(:searchQuery) OR LOWER(details) LIKE LOWER(:searchQuery) OR LOWER(eventType) LIKE LOWER(:searchQuery) OR LOWER(source) LIKE LOWER(:searchQuery) ORDER BY timestamp DESC")
    fun getEventsBySearchPaged(searchQuery: String): androidx.paging.PagingSource<Int, Event>

    /**
     * Retrieves events filtered by severity with pagination.
     */
    @Query("SELECT * FROM Events WHERE severity = :severity ORDER BY timestamp DESC")
    fun getEventsBySeverityPaged(severity: Severity): androidx.paging.PagingSource<Int, Event>

    /**
     * Retrieves events filtered by severity and search query with pagination.
     */
    @Query("SELECT * FROM Events WHERE severity = :severity AND (LOWER(message) LIKE LOWER(:searchQuery) OR LOWER(details) LIKE LOWER(:searchQuery) OR LOWER(eventType) LIKE LOWER(:searchQuery) OR LOWER(source) LIKE LOWER(:searchQuery)) ORDER BY timestamp DESC")
    fun getEventsBySeverityAndSearchPaged(severity: Severity, searchQuery: String): androidx.paging.PagingSource<Int, Event>

    /**
     * Retrieves events filtered by sources with pagination.
     */
    @Query("SELECT * FROM Events WHERE source IN (:sources) ORDER BY timestamp DESC")
    fun getEventsBySourcesPaged(sources: List<EventSource>): androidx.paging.PagingSource<Int, Event>

    /**
     * Retrieves events filtered by sources and search query with pagination.
     */
    @Query("SELECT * FROM Events WHERE source IN (:sources) AND (LOWER(message) LIKE LOWER(:searchQuery) OR LOWER(details) LIKE LOWER(:searchQuery) OR LOWER(eventType) LIKE LOWER(:searchQuery) OR LOWER(source) LIKE LOWER(:searchQuery)) ORDER BY timestamp DESC")
    fun getEventsBySourcesAndSearchPaged(sources: List<EventSource>, searchQuery: String): androidx.paging.PagingSource<Int, Event>
}

