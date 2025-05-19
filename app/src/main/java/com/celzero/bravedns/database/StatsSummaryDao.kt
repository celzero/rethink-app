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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import com.celzero.bravedns.data.AppConnection

@Dao
interface StatsSummaryDao {


    @Query(
        """
            SELECT uid AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              '' AS flag, 
              0 AS blocked, 
              appOrDnsName, 
              Sum(downloadBytes) AS downloadBytes, 
              Sum(uploadBytes) AS uploadBytes, 
              Sum(uploadBytes + downloadBytes) AS totalBytes 
            FROM 
              (
                -- From DnsLogs
                SELECT uid AS uid, 
                  appName AS appOrDnsName, 
                  Count(uid) AS count, 
                  0 AS downloadBytes, 
                  0 AS uploadBytes
                FROM DnsLogs 
                WHERE isBlocked = 0 
                  AND status = 'COMPLETE' 
                  AND queryStr != '' 
                  AND time > :to 
                GROUP BY uid
                
                UNION ALL 
                
                -- From ConnectionTracker
                SELECT uid AS uid, 
                  appName AS appOrDnsName, 
                  COUNT(uid) AS count, 
                  sum(downloadBytes) as downloadBytes, 
                  sum(uploadBytes) as uploadBytes 
                FROM ConnectionTracker 
                WHERE isBlocked = 0 
                  AND timeStamp > :to
                  AND dnsQuery != '' 
                GROUP BY uid
              ) AS combined 
            GROUP BY uid 
            ORDER BY totalBytes DESC 
            LIMIT 7
        """
    )
    fun getMostAllowedApps(to: Long): PagingSource<Int, AppConnection>


    @Query(
        """
            SELECT uid AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              '' as flag, 
              0 AS blocked, 
              appOrDnsName, 
              sum(downloadBytes) as downloadBytes, 
              sum(uploadBytes) as uploadBytes, 
              sum(uploadBytes + downloadBytes) as totalBytes 
            FROM 
              (
                -- From DnsLogs
                SELECT uid as uid, 
                  appName AS appOrDnsName, 
                  COUNT(uid) AS count, 
                  0 as downloadBytes, 
                  0 as uploadBytes 
                FROM DnsLogs 
                WHERE isBlocked = 0 
                  AND status = 'COMPLETE' 
                  AND queryStr != '' 
                  AND time > :to 
                GROUP BY uid 
                  
                UNION ALL 
                  
                  -- From ConnectionTracker
                SELECT uid as uid, 
                  appName AS appOrDnsName, 
                  COUNT(uid) AS count, 
                  sum(downloadBytes) as downloadBytes, 
                  sum(uploadBytes) as uploadBytes 
                FROM ConnectionTracker 
                WHERE isBlocked = 0 
                  AND timeStamp > :to 
                  AND dnsQuery != '' 
                GROUP BY uid
              ) AS combined 
            GROUP BY uid 
            ORDER BY totalBytes DESC
        """
    )
    fun getAllAllowedApps(to: Long): PagingSource<Int, AppConnection>


    @Query(
        """
            SELECT uid AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              '' as flag, 
              1 AS blocked, 
              appOrDnsName 
            FROM 
              (
                -- From DnsLogs
                SELECT uid as uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count 
                FROM DnsLogs 
                WHERE isBlocked = 1 
                  AND time > :to 
                GROUP BY uid
                   
                UNION ALL 
                  
                  -- From ConnectionTracker
                SELECT uid as uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count 
                FROM ConnectionTracker 
                WHERE isBlocked = 1 
                  AND timeStamp > :to 
                GROUP BY uid
              ) AS combined 
            GROUP BY uid 
            ORDER BY count DESC 
            LIMIT 7 
        """
    )
    fun getMostBlockedApps(to: Long): PagingSource<Int, AppConnection>


    @Query(
        """
            SELECT 
              uid AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              '' as flag, 
              1 AS blocked, 
              appOrDnsName 
            FROM 
              (
                -- From DnsLogs
                SELECT uid as uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count 
                FROM DnsLogs 
                WHERE isBlocked = 1 
                  AND time > :to 
                GROUP BY uid
                   
                UNION ALL 
                  
                  -- From ConnectionTracker
                SELECT uid as uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count 
                FROM ConnectionTracker 
                WHERE isBlocked = 1 
                  AND timeStamp > :to 
                GROUP BY uid
              ) AS combined 
            GROUP BY uid 
            ORDER BY count DESC
        """
    )
    fun getAllBlockedApps(to: Long): PagingSource<Int, AppConnection>

    @Query(
        """
          SELECT 0 AS uid, 
            '' AS ipAddress, 
            0 AS port, 
            SUM(count) AS count, 
            flag, 
            1 AS blocked, 
            appOrDnsName 
          FROM 
            (
              -- From DnsLogs
              SELECT RTRIM(queryStr, '.') AS appOrDnsName, 
                COUNT(id) AS count, 
                flag 
              FROM DnsLogs 
              WHERE isBlocked = 1 
                AND time > :to 
                AND queryStr != '' 
              GROUP BY RTRIM(queryStr, '.')
                 
              UNION ALL 
                
                -- From ConnectionTracker
              SELECT dnsQuery AS appOrDnsName, 
                COUNT(id) AS count, 
                flag 
              FROM ConnectionTracker 
              WHERE isBlocked = 1 
                AND timeStamp > :to 
                AND blockedByRule LIKE 'Rule #2G%' 
              GROUP BY dnsQuery
            ) AS combined 
          GROUP BY appOrDnsName 
          ORDER BY count DESC 
          LIMIT 7
        """
        )
    fun getMostBlockedDomains(to: Long): PagingSource<Int, AppConnection>
    
    @Query(
        """
            SELECT 0 AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              flag, 
              1 AS blocked, 
              appOrDnsName 
            FROM 
              (
                -- From DnsLogs
                SELECT RTRIM(queryStr, '.') AS appOrDnsName, 
                  COUNT(id) AS count, 
                  flag 
                FROM DnsLogs 
                WHERE isBlocked = 1 
                  AND time > :to 
                  AND queryStr != '' 
                GROUP BY RTRIM(queryStr, '.')
                 
                UNION ALL 
                
                -- From ConnectionTracker
                SELECT dnsQuery AS appOrDnsName, 
                  COUNT(id) AS count, 
                  flag 
                FROM ConnectionTracker 
                WHERE isBlocked = 1 
                  AND timeStamp > :to 
                  AND blockedByRule LIKE 'Rule #2G%' 
                GROUP BY 
                  dnsQuery
              ) AS combined 
            GROUP BY appOrDnsName 
            ORDER BY count DESC 
        """
    )
    fun getAllBlockedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query(
        """
            SELECT 0 AS uid, 
                '' AS ipAddress, 
                0 AS port,  
                SUM(count) AS count, 
                flag, 
                0 AS blocked, 
                appOrDnsName
            FROM (
                -- From DnsLogs
                SELECT RTRIM(queryStr, '.') AS appOrDnsName, 
                    COUNT(id) AS count, 
                    flag
                FROM DnsLogs
                WHERE isBlocked = 0 
                      AND status = 'COMPLETE'
                      AND queryStr != '' 
                      AND time > :to 
                GROUP BY RTRIM(queryStr, '.')
                
                UNION ALL 
            
                -- From ConnectionTracker
                SELECT dnsQuery AS appOrDnsName, 
                    COUNT(id) AS count, 
                    flag
                FROM ConnectionTracker 
                WHERE isBlocked = 0 
                      AND timeStamp > :to 
                      AND dnsQuery != '' 
                GROUP BY dnsQuery 
            ) AS combined 
            GROUP BY appOrDnsName
            ORDER BY count DESC 
            LIMIT 7
            """
    )
    fun getMostContactedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query(
        """
            SELECT 0 AS uid, 
                '' AS ipAddress, 
                0 AS port,  
                SUM(count) AS count, 
                flag, 
                0 AS blocked, 
                appOrDnsName
            FROM (
                -- From DnsLogs
                SELECT RTRIM(queryStr, '.') AS appOrDnsName, 
                    COUNT(id) AS count, 
                    flag
                FROM DnsLogs 
                WHERE isBlocked = 0 
                      AND status = 'COMPLETE'
                      AND queryStr != '' 
                      AND time > :to  
                GROUP BY RTRIM(queryStr, '.')
                
                UNION ALL
                
                -- From ConnectionTracker
                SELECT dnsQuery AS appOrDnsName, 
                    COUNT(id) AS count, 
                    flag
                FROM ConnectionTracker 
                WHERE isBlocked = 0 
                    AND timeStamp > :to 
                    AND dnsQuery != '' 
                GROUP BY dnsQuery
            ) AS combined
            GROUP BY appOrDnsName 
            ORDER BY count DESC
            """
    )
    fun getAllContactedDomains(to: Long): PagingSource<Int, AppConnection>

    @Query(
        """
            SELECT 0 AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              flag, 
              0 AS blocked, 
              '' as appOrDnsName 
            FROM 
              (
                -- From DnsLogs
                SELECT COUNT(id) AS count, 
                  flag 
                FROM DnsLogs 
                WHERE isBlocked = 0 
                  AND status = 'COMPLETE' 
                  AND queryStr != '' 
                  AND time > :to 
                GROUP BY flag 

                UNION ALL 
                
                -- From ConnectionTracker
                SELECT COUNT(id) AS count, 
                  flag 
                FROM ConnectionTracker 
                WHERE isBlocked = 0 
                  AND timeStamp > :to 
                GROUP BY flag
              ) AS combined 
            GROUP BY flag 
            ORDER BY count DESC 
            LIMIT 7
        """
    )
    fun getMostContactedCountries(to: Long): PagingSource<Int, AppConnection>

    @Query(
        """
            SELECT 0 AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              flag, 
              0 AS blocked, 
              '' as appOrDnsName 
            FROM 
              (
                -- From DnsLogs
                SELECT COUNT(id) AS count, 
                  flag 
                FROM DnsLogs 
                WHERE isBlocked = 0 
                  AND status = 'COMPLETE' 
                  AND queryStr != '' 
                  AND time > :to 
                GROUP BY flag
                   
                UNION ALL 
                  
                -- From ConnectionTracker
                SELECT COUNT(id) AS count, 
                  flag 
                FROM ConnectionTracker 
                WHERE isBlocked = 0 
                  AND timeStamp > :to 
                GROUP BY flag
              ) AS combined 
            GROUP BY flag 
            ORDER BY count DESC 
        """
        )
        fun getAllContactedCountries(to: Long): PagingSource<Int, AppConnection>


    @Query(
        """
            SELECT uid AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              '' AS flag, 
              0 AS blocked, 
              appOrDnsName, 
              SUM(uploadBytes) AS uploadBytes, 
              SUM(downloadBytes) AS downloadBytes, 
              0 AS totalBytes 
            FROM 
              (
                -- From ConnectionTracker
                SELECT uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count, 
                  SUM(uploadBytes) AS uploadBytes, 
                  SUM(downloadBytes) AS downloadBytes 
                FROM ConnectionTracker 
                WHERE timeStamp > :to 
                  AND dnsQuery = :query 
                  AND isBlocked = 0 
                GROUP BY uid
                   
                UNION ALL 

                -- From DnsLogs
                SELECT uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count, 
                  0 AS uploadBytes, 
                  0 AS downloadBytes 
                FROM DnsLogs 
                WHERE time > :to 
                  AND queryStr like :query 
                  AND isBlocked = 0 
                  AND status = 'COMPLETE' 
                  AND queryStr != '' 
                GROUP BY uid
              ) AS combined 
            GROUP BY uid 
            ORDER BY count DESC
        """
    )
    fun getDomainDetails(query: String, to: Long): PagingSource<Int, AppConnection>


    @Query(
        """
            SELECT uid AS uid, 
              '' AS ipAddress, 
              0 AS port, 
              SUM(count) AS count, 
              flag AS flag, 
              0 AS blocked, 
              appOrDnsName AS appOrDnsName, 
              Sum(uploadbytes) AS uploadBytes, 
              Sum(downloadbytes) AS downloadBytes, 
              0 AS totalBytes 
            FROM 
              (
                -- From DnsLogs
                SELECT uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count, 
                  flag as flag, 
                  SUM(uploadBytes) AS uploadBytes, 
                  SUM(downloadBytes) AS downloadBytes 
                FROM ConnectionTracker 
                WHERE timeStamp > :to 
                  AND flag = :flag 
                  AND isBlocked = 0 
                GROUP BY uid 
                
                UNION ALL 
                
                -- From ConnectionTracker
                SELECT uid, 
                  appName AS appOrDnsName, 
                  COUNT(id) AS count, 
                  flag as flag, 
                  0 AS uploadBytes, 
                  0 AS downloadBytes 
                FROM DnsLogs 
                WHERE time > :to 
                  AND flag = :flag 
                  AND isBlocked = 0 
                  AND status = 'COMPLETE' 
                  AND queryStr != '' 
                GROUP BY uid
              ) AS combined 
            GROUP BY uid 
            ORDER BY count DESC 
        """
    )
    fun getFlagDetails(flag: String, to: Long): PagingSource<Int, AppConnection>

    @Query("""
        SELECT :uid AS uid, 
                      '' AS ipAddress, 
                      0 AS port, 
                      SUM(count) AS count, 
                      flag AS flag, 
                      0 AS blocked, 
                      appOrDnsName, 
                      0 AS uploadBytes, 
                      0 AS downloadBytes, 
                      0 AS totalBytes 
                    FROM 
                      (
                        -- From ConnectionTracker
                        SELECT dnsQuery AS appOrDnsName, 
                          COUNT(dnsQuery) AS count,
                          flag as flag
                        FROM ConnectionTracker 
                        WHERE dnsQuery != ''  
                            AND timeStamp > :to
                            AND uid = :uid
                        GROUP BY dnsQuery
                           
                        UNION ALL 
        
                        -- From DnsLogs
                        SELECT queryStr AS appOrDnsName, 
                          COUNT(queryStr) AS count,
                          flag as flag
                        FROM DnsLogs 
                        WHERE uid = :uid
                            AND time > :to 
                          AND status = 'COMPLETE' 
                          AND queryStr != '' 
                        GROUP BY queryStr
                      ) AS combined 
                    GROUP BY appOrDnsName 
                    ORDER BY count DESC
                    LIMIT 3
    """)
    fun getMostDomainsByUid(uid: Int, to: Long): PagingSource<Int, AppConnection>

    @Query("""
            SELECT :uid AS uid, 
                          '' AS ipAddress, 
                          0 AS port, 
                          SUM(count) AS count, 
                          flag AS flag, 
                          0 AS blocked, 
                          appOrDnsName, 
                          0 AS uploadBytes, 
                          0 AS downloadBytes, 
                          0 AS totalBytes 
                        FROM 
                          (
                            -- From ConnectionTracker
                            SELECT dnsQuery AS appOrDnsName, 
                              COUNT(dnsQuery) AS count,
                              flag as flag
                            FROM ConnectionTracker 
                            WHERE dnsQuery != ''  
                                AND timeStamp > :to
                                AND uid = :uid
                                AND dnsQuery LIKE :input
                            GROUP BY dnsQuery
                               
                            UNION ALL 
            
                            -- From DnsLogs
                            SELECT queryStr AS appOrDnsName, 
                              COUNT(queryStr) AS count, 
                              flag as flag
                            FROM DnsLogs 
                            WHERE uid = :uid
                                AND time > :to 
                              AND status = 'COMPLETE' 
                              AND queryStr != '' 
                              AND queryStr LIKE :input
                            GROUP BY queryStr
                          ) AS combined 
                        GROUP BY appOrDnsName 
                        ORDER BY count DESC
        """)
        fun getAllDomainsByUid(uid: Int, to: Long, input: String): PagingSource<Int, AppConnection>

    @Query(
        """
                SELECT :uid AS uid, 
                              '' AS ipAddress, 
                              0 AS port, 
                              SUM(count) AS count, 
                              flag AS flag, 
                              0 AS blocked, 
                              appOrDnsName, 
                              0 AS uploadBytes, 
                              0 AS downloadBytes, 
                              0 AS totalBytes 
                            FROM 
                              (
                                -- From ConnectionTracker
                                SELECT dnsQuery AS appOrDnsName, 
                                  COUNT(dnsQuery) AS count,
                                  flag as flag
                                FROM ConnectionTracker 
                                WHERE dnsQuery != ''  
                                    AND timeStamp > :to
                                    AND uid = :uid
                                GROUP BY dnsQuery
                                   
                                UNION ALL 
                
                                -- From DnsLogs
                                SELECT queryStr AS appOrDnsName, 
                                  COUNT(queryStr) AS count, 
                                  flag as flag
                                FROM DnsLogs 
                                WHERE uid = :uid
                                    AND time > :to 
                                  AND status = 'COMPLETE' 
                                  AND queryStr != ''
                                GROUP BY queryStr
                              ) AS combined 
                            GROUP BY appOrDnsName 
                            ORDER BY count DESC
            """
    )
    fun getAllDomainsByUid(uid: Int, to: Long): PagingSource<Int, AppConnection>
}
