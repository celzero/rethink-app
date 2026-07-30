/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared helper for the DEBUG-only "Import Rules" feature used by
 * [CustomIpFragment] and [CustomDomainFragment].
 *
 * Responsibilities:
 *  1. Parse a plain-text file (one rule per line, '#' comments, blank lines skipped).
 *  2. Validate each line against the target import type (IP vs domain).
 *  3. Insert valid, non-duplicate entries via the existing service-layer APIs.
 *
 * All public methods are suspend functions intended to be called from an IO coroutine.
 * This object intentionally lives in the fragment package and is never referenced from
 * production (non-debug) code paths — callers must guard access with BuildConfig.DEBUG.
 */
object RulesImportHelper {

    /** Determines which rule type a fragment is importing. */
    enum class ImportType { IP, DOMAIN }

    /**
     * The result of parsing a file before the user confirms the import.
     *
     * @param fileName     Display name of the selected file.
     * @param valid        Pre-validated rule strings ready for insertion.
     * @param invalidCount Number of lines that failed validation (skipped during parse).
     */
    data class ParsedFile(
        val fileName: String,
        val valid: List<String>,
        val invalidCount: Int
    )

    /**
     * Summary returned after the actual DB insertion completes.
     *
     * @param imported   Rules successfully written to the database.
     * @param duplicates Rules that already existed (same UID + rule string) and were skipped.
     * @param invalid    Entries that could not be parsed during insertion (should normally be 0
     *                   since entries were pre-validated during [parseFile]).
     */
    data class ImportSummary(
        val imported: Int,
        val duplicates: Int,
        val invalid: Int
    )

    // -----------------------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------------------

    /**
     * Opens the file at [uri] and parses it into a [ParsedFile].
     *
     * File format:
     * - One rule per line.
     * - Lines beginning with '#' are treated as comments and ignored.
     * - Blank / whitespace-only lines are ignored.
     * - For [ImportType.IP]: valid IPv4, IPv6, and CIDR strings are accepted.
     * - For [ImportType.DOMAIN]: plain domains and wildcard domains (*.example.com) are accepted.
     *   URLs with schemas (https://…) are normalised to their host portion.
     *
     * @return [ParsedFile] on success, or null if the file could not be opened/read.
     */
    suspend fun parseFile(
        context: Context,
        uri: Uri,
        importType: ImportType
    ): ParsedFile? = withContext(Dispatchers.IO) {
        val fileName = resolveFileName(context, uri)

        val lines: List<String>
        try {
            lines = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readLines() }
                ?: return@withContext null
        } catch (_: Exception) {
            return@withContext null
        }

        val valid = mutableListOf<String>()
        var invalidCount = 0

        for (rawLine in lines) {
            val line = rawLine.trim()

            // Skip blank lines and comment lines (lines starting with '#')
            if (line.isEmpty() || line.startsWith("#")) continue

            when (importType) {
                ImportType.IP -> {
                    // Use the same parsing path as manual entry: getIpNetPort returns null
                    // for the IPAddress component when the string is not a valid IP/CIDR.
                    val (ip, _) = IpRulesManager.getIpNetPort(line)
                    if (ip != null) {
                        valid.add(line)
                    } else {
                        invalidCount++
                    }
                }

                ImportType.DOMAIN -> {
                    // extractHost normalises URLs (strips schema, removes www. prefix) and
                    // returns null for formats we don't support (e.g. wildcards with schema).
                    val host = DomainRulesManager.extractHost(line)
                    if (host != null && isAcceptableDomain(host)) {
                        // Store the already-extracted host so insertDomain() doesn't re-parse
                        valid.add(host)
                    } else {
                        invalidCount++
                    }
                }
            }
        }

        ParsedFile(fileName, valid.toList(), invalidCount)
    }

    /**
     * Inserts [entries] into the rule database.
     *
     * Each entry is checked against existing rules for [uid] before insertion.
     * If a rule already exists (same UID + rule string), it is counted as a duplicate and skipped.
     * Insertion uses the same service-layer methods as manual rule creation so that the in-memory
     * trees / caches are kept consistent.
     *
     * @param uid          Target UID (-1000 / [UID_EVERYBODY] for global rules).
     * @param ipStatus     IP rule status to apply (BLOCK or TRUST / BYPASS_UNIVERSAL).
     * @param domainStatus Domain rule status to apply (BLOCK or TRUST).
     */
    suspend fun importRules(
        entries: List<String>,
        importType: ImportType,
        uid: Int,
        ipStatus: IpRulesManager.IpRuleStatus = IpRulesManager.IpRuleStatus.BLOCK,
        domainStatus: DomainRulesManager.Status = DomainRulesManager.Status.BLOCK
    ): ImportSummary = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0
        var invalid = 0

        for (entry in entries) {
            when (importType) {
                ImportType.IP -> {
                    val (ip, port) = IpRulesManager.getIpNetPort(entry)
                    if (ip == null) {
                        // Guard: should not happen since entries were pre-validated in parseFile,
                        // but a defensive check prevents any crash from malformed state.
                        invalid++
                        continue
                    }
                    // isIpRuleExists normalises the IP the same way addIpRule does, so the
                    // DB lookup reliably detects duplicates regardless of CIDR notation.
                    if (IpRulesManager.isIpRuleExists(uid, ip, port)) {
                        duplicates++
                    } else {
                        IpRulesManager.addIpRule(uid, ip, port, ipStatus, proxyId = "", proxyCC = "")
                        imported++
                    }
                }

                ImportType.DOMAIN -> {
                    // Entries arriving here are already extracted hosts from parseFile,
                    // so a leading "*." reliably identifies wildcards.
                    val type = if (entry.startsWith("*.")) {
                        DomainRulesManager.DomainType.WILDCARD
                    } else {
                        DomainRulesManager.DomainType.DOMAIN
                    }
                    // getObj queries by (uid, domain) primary key — null means no existing rule.
                    if (DomainRulesManager.getObj(uid, entry) != null) {
                        duplicates++
                    } else {
                        DomainRulesManager.addDomainRule(entry, domainStatus, type, uid)
                        imported++
                    }
                }
            }
        }

        ImportSummary(imported, duplicates, invalid)
    }

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Returns true if [host] passes at least one of the domain validators exposed by
     * [DomainRulesManager]. Wildcard entries (*.example.com) use the wildcard validator;
     * everything else uses the standard domain validator.
     */
    private fun isAcceptableDomain(host: String): Boolean {
        return if (host.startsWith("*.")) {
            DomainRulesManager.isWildCardEntry(host)
        } else {
            DomainRulesManager.isValidDomain(host)
        }
    }

    /**
     * Resolves a human-readable file name from a content [Uri].
     * Falls back to the URI's last path segment, then to "unknown".
     */
    private fun resolveFileName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: "unknown"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }
}
