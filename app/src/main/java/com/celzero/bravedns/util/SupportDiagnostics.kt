/*
 * Copyright 2026 RethinkDNS and its authors
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

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.database.RpnLogRepository
import com.celzero.bravedns.database.SubscriptionStateHistory
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.scheduler.BugReportZipper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// builds and sends Rethink Plus support diagnostics, shared by phone and TV support screens
object SupportDiagnostics {

    private const val TAG = "SupportDiagnostics"

    const val DIAG_FILE_NAME = "rpn_support_diag.txt"
    const val SUPPORT_ZIP_FILE_NAME = "rpn_support_diagnostics.zip"
    const val MAX_HISTORY_ENTRIES = 50
    const val SUPPORT_CONSOLE_LOG_FILE_NAME = "console_logs.txt"
    const val SUPPORT_RPN_LOG_FILE_NAME = "rpn_logs.txt"
    const val SUPPORT_TRACE_LOG_FILE_NAME = "trace_logs.txt"

    private const val WIRELOG_ATTACH_LIMIT_BYTES = 1 * 1024 * 1024L  // 1 MB
    private const val OTHER_ATTACH_THRESHOLD_BYTES = 1 * 1024 * 1024L // cap wirelog at 1 MB if other attachments exceed this
    // process info (stack traces) larger than this is zipped as process_info.zip
    private const val PROC_INFO_ZIP_THRESHOLD_BYTES = 512 * 1024
    // bugreport zips larger than this keep only the newest entries when attached
    private const val BUG_ZIP_TRIM_THRESHOLD_BYTES = 8L * 1024L * 1024L // 8 MB
    // hard cap on uncompressed bytes kept from a trimmed bugreport zip
    private const val BUG_ZIP_TRIM_BUDGET_BYTES = 6L * 1024L * 1024L // 6 MB
    // reserve kept out of the log budget for zip entry overhead
    private const val SUPPORT_ZIP_OVERHEAD_RESERVE_BYTES = 64 * 1024L
    // below this budget the console logs are dropped so RPN logs still fit
    private const val MIN_CONSOLE_LOG_ATTACHMENT_BYTES = 64 * 1024L

    // ascii-only separators: unicode chars get mangled by email encoding
    fun buildDiagnosticText(
        ctx: Context,
        description: String,
        category: String?,
        statuses: List<SubscriptionStatus>,
        history: List<SubscriptionStateHistory>,
        entitlementJson: String? = null,
    ): String {
        val heavy = "===========================================================\n"
        val light = "-----------------------------------------------------------\n"
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
        val versionName = appVersionName(ctx)

        sb.append(heavy)
        sb.append("  RETHINK PLUS SUPPORT DIAGNOSTIC REPORT\n")
        sb.append(heavy)
        sb.append("Generated   : $now\n")
        sb.append("App version : $versionName\n")
        sb.append("Device      : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Android     : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Package     : ${ctx.packageName}\n")
        sb.append("Flavour     : ${appFlavour()}\n")
        sb.append("\n")

        sb.append(light)
        sb.append("  USER DESCRIPTION\n")
        sb.append(light)
        if (category != null) sb.append("Category : $category\n")
        sb.append(description.ifEmpty { "(no description provided)" })
        sb.append("\n\n")

        sb.append(light)
        sb.append("  SUBSCRIPTION STATUS (${statuses.size} row(s))\n")
        sb.append(light)
        if (statuses.isEmpty()) {
            sb.append("  No subscription records found.\n")
        } else {
            statuses.forEachIndexed { idx, s ->
                sb.append("\n  -- Row ${idx + 1} ----------------------------------\n")
                sb.append("  id               : ${s.id}\n")
                sb.append("  status           : ${SubscriptionStatus.SubscriptionState.fromId(s.status).name} (${s.status})\n")
                sb.append("  productId        : ${s.productId}\n")
                sb.append("  planId           : ${s.planId}\n")
                sb.append("  productTitle     : ${s.productTitle}\n")
                sb.append("  purchaseToken    : ${s.purchaseToken}\n")
                sb.append("  orderId          : ${s.orderId.ifEmpty { "N/A" }}\n")
                // accountId: max 12 chars visible, rest redacted
                sb.append("  accountId        : ${redactId(s.accountId)}\n")
                sb.append("  deviceId         : ${redactDid(s.deviceId)}\n")
                sb.append("  purchaseTime     : ${formatTs(s.purchaseTime)}\n")
                sb.append("  billingExpiry    : ${formatTs(s.billingExpiry)}\n")
                sb.append("  accountExpiry    : ${formatTs(s.accountExpiry)}\n")
                sb.append("  lastUpdatedTs    : ${formatTs(s.lastUpdatedTs)}\n")
                sb.append("  previousProductId: ${s.previousProductId.ifEmpty { "N/A" }}\n")
                sb.append("  previousToken    : ${s.previousPurchaseToken.ifEmpty { "N/A" }}\n")
                sb.append("  replacedAt       : ${if (s.replacedAt > 0) formatTs(s.replacedAt) else "N/A"}\n")
                // sessionToken truncated to 32 chars - it can be very long
                val tokenDisplay = if (s.sessionToken.length > 32)
                    s.sessionToken.take(32) + "..." else s.sessionToken
                sb.append("  sessionToken     : $tokenDisplay\n")
            }
        }
        sb.append("\n")

        sb.append(light)
        sb.append("  STATE HISTORY (${history.size} of last $MAX_HISTORY_ENTRIES)\n")
        sb.append(light)
        if (history.isEmpty()) {
            sb.append("  No history records found.\n")
        } else {
            history.forEach { h ->
                val from = SubscriptionStatus.SubscriptionState.fromId(h.fromState).name.removePrefix("STATE_")
                val to = SubscriptionStatus.SubscriptionState.fromId(h.toState).name.removePrefix("STATE_")
                sb.append("  ${formatTs(h.timestamp)}  $from -> $to")
                if (!h.reason.isNullOrBlank()) sb.append("  [${h.reason}]")
                sb.append("\n")
            }
        }
        sb.append("\n")

        if (!entitlementJson.isNullOrBlank()) {
            sb.append(light)
            sb.append("  ENTITLEMENT JSON\n")
            sb.append(light)
            sb.append(entitlementJson)
            sb.append("\n\n")
        }

        sb.append(heavy)
        sb.append("  END OF REPORT\n")
        sb.append(heavy)

        return sb.toString()
    }

    // all subscription data, stats, and history live in the attached diagnostic file
    fun buildEmailBody(ctx: Context, description: String, category: String?): String {
        val sb = StringBuilder()

        sb.append("Hello Rethink Support,\n\n")

        if (category != null) {
            sb.append("Issue category: $category\n\n")
        }

        sb.append(
            description.ifEmpty { "(No description provided, please see the attached diagnostic file for details.)" }
        )

        sb.append("\n\n")
        sb.append("--- Device info ---\n")
        sb.append("App version : ${appVersionName(ctx)}\n")
        sb.append("Flavour     : ${appFlavour()}\n")
        sb.append("Device      : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Android     : ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("\n")
        sb.append("Subscription status, state history, proxy statistics,\n")
        sb.append("and available recent console logs are included in the\n")
        sb.append("attached diagnostic ZIP.\n\n")
        sb.append("--\nSent via Rethink Plus Support")

        return sb.toString()
    }

    fun prepareWirelogAttachment(ctx: Context, otherAttachmentsSize: Long): ByteArray? {
        val srcFile = File(ctx.filesDir, "${Logger.WIRELOG_FOLDER_NAME}/${Logger.WIRELOG_FILE_NAME}")
        if (!srcFile.exists() || srcFile.length() == 0L) return null
        return try {
            val maxBytes = if (otherAttachmentsSize > OTHER_ATTACH_THRESHOLD_BYTES) {
                WIRELOG_ATTACH_LIMIT_BYTES
            } else {
                Logger.WIRELOG_MAX_SIZE_BYTES
            }
            readTailBytes(srcFile, maxBytes)
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_UI, "$TAG prepareWirelogAttachment error: ${e.message}", e)
            null
        }
    }

    private fun readTailBytes(file: File, maxBytes: Long): ByteArray {
        val size = file.length()
        val start = maxOf(0L, size - maxBytes)
        return java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val toRead = (size - start).toInt()
            val buf = ByteArray(toRead)
            raf.readFully(buf)
            buf
        }
    }

    fun buildSupportZip(
        ctx: Context,
        diagFile: File?,
        wirelogBytes: ByteArray?,
        procInfoBytes: ByteArray?,
        bugZip: File?,
        consoleLogs: File? = null,
        rpnLogs: File? = null,
        traceLogs: File? = null,
    ): File? {
        if (diagFile == null && wirelogBytes == null && procInfoBytes == null && bugZip == null &&
            consoleLogs == null && rpnLogs == null && traceLogs == null
        ) {
            return null
        }
        return try {
            val outFile = File(File(ctx.filesDir, "support").also { it.mkdirs() }, SUPPORT_ZIP_FILE_NAME)
            java.util.zip.ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                diagFile?.takeIf { it.exists() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry("diagnostic_report.txt"))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
                procInfoBytes?.takeIf { it.isNotEmpty() }?.let {
                    if (it.size > PROC_INFO_ZIP_THRESHOLD_BYTES) {
                    // large stack-trace snapshots ship zipped to keep the support zip lean
                    zos.putNextEntry(java.util.zip.ZipEntry("process_info.zip"))
                        zos.write(zipBytes("process_info.txt", it))
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry("process_info.txt"))
                        zos.write(it)
                    }
                    zos.closeEntry()
                }
                wirelogBytes?.takeIf { it.isNotEmpty() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry("wirelogs.txt"))
                    zos.write(it)
                    zos.closeEntry()
                }
                bugZip?.takeIf { it.exists() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry("bugreport.zip"))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
                consoleLogs?.takeIf { it.exists() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry(SUPPORT_CONSOLE_LOG_FILE_NAME))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
                rpnLogs?.takeIf { it.exists() }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry(SUPPORT_RPN_LOG_FILE_NAME))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
                traceLogs?.takeIf { it.exists() && it.length() > 0L }?.let {
                    zos.putNextEntry(java.util.zip.ZipEntry(SUPPORT_TRACE_LOG_FILE_NAME))
                    it.inputStream().use { ins -> ins.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            outFile
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_UI, "$TAG buildSupportZip error: ${e.message}", e)
            null
        }
    }

    /** Returns [bytes] compressed as a single-entry zip named [entryName]. */
    private fun zipBytes(entryName: String, bytes: ByteArray): ByteArray {
        return java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }.toByteArray()
    }

    // keeps only the newest entries within the budget when the bug zip is oversized
    fun trimBugZipIfNeeded(ctx: Context, bugZip: File): File {
        if (bugZip.length() <= BUG_ZIP_TRIM_THRESHOLD_BYTES) return bugZip

        val trimmed = File(ctx.cacheDir, "bugreport_trimmed.zip")
        try {
            java.util.zip.ZipFile(bugZip).use { zf ->
                // newest first; ZipEntry.time reflects when the entry was written
                val newestFirst = zf.entries().toList()
                    .filter { !it.isDirectory }
                    .sortedByDescending { it.time }

                java.util.zip.ZipOutputStream(trimmed.outputStream().buffered()).use { zos ->
                    var kept = 0L
                    for (e in newestFirst) {
                        val entrySize = (if (e.size > 0) e.size else zf.getInputStream(e).use { it.available().toLong() })
                        if (kept > 0 && kept + entrySize > BUG_ZIP_TRIM_BUDGET_BYTES) break
                        zf.getInputStream(e).use { ins ->
                            zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                            ins.copyTo(zos)
                            zos.closeEntry()
                        }
                        kept += entrySize
                    }
                }
            }
            Logger.i(Logger.LOG_TAG_UI, "$TAG trimmed bugzip: ${bugZip.length()} -> ${trimmed.length()} bytes")
            return trimmed
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "$TAG trim bugzip error: ${e.message}", e)
            trimmed.delete()
            return bugZip
        }
    }

    fun writeDiagFile(ctx: Context, content: String): File? {
        return try {
            val dir = File(ctx.filesDir, "support").also { it.mkdirs() }
            val file = File(dir, DIAG_FILE_NAME)
            file.writeText(content, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_UI, "$TAG writeDiagFile error: ${e.message}", e)
            null
        }
    }

    fun getFileUri(ctx: Context, file: File?): Uri? {
        file ?: return null
        return try {
            if (file.exists()) {
                FileProvider.getUriForFile(
                    ctx.applicationContext,
                    BugReportZipper.FILE_PROVIDER_NAME,
                    file,
                )
            } else null
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_UI, "$TAG getFileUri error: ${e.message}", e)
            null
        }
    }

    fun launchEmailIntent(ctx: Context, body: String, supportZip: File?, category: String?) {
        try {
            val subject = buildSubject(category)
            val email = ctx.getString(R.string.about_mail_to)
            val zipUri = getFileUri(ctx, supportZip)

            val intent = if (zipUri != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    putExtra(Intent.EXTRA_STREAM, zipUri)
                    clipData = ClipData.newUri(ctx.contentResolver, "Support Diagnostics", zipUri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                }
            }

            ctx.startActivity(
                Intent.createChooser(intent, ctx.getString(R.string.support_email_chooser_title))
            )
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_UI, "$TAG launchEmailIntent error: ${e.message}", e)
            Toast.makeText(ctx, ctx.getString(R.string.support_error_email), Toast.LENGTH_LONG).show()
        }
    }

    // subject stays minimal: no user data; details live in the attachment
    fun buildSubject(category: String?): String {
        val catPart = if (category != null) " [$category]" else ""
        return "Rethink Plus Support Request: $catPart"
    }

    /** Returns the app version name, falling back to "?" on error. */
    fun appVersionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
            .getOrElse { "?" }

    // via reflection so the helper stays usable from any source set
    fun appFlavour(): String {
        return try {
            val clazz = Class.forName("com.celzero.bravedns.BuildConfig")
            clazz.getField("FLAVOR").get(null)?.toString() ?: "unknown"
        } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_UI, "$TAG appFlavour error: ${e.message}", e)
            "unknown"
        }
    }

    // keeps at most the first 12 chars of a sensitive id, rest replaced with "***"
    fun redactId(id: String): String {
        if (id.isEmpty()) return "N/A"
        return if (id.length > 12) "${id.take(12)}***" else id
    }

    fun redactDid(id: String): String {
        if (id.isEmpty()) return "N/A"
        return if (id.length > 4) "${id.take(4)}***" else id
    }

    fun formatTs(ms: Long): String {
        if (ms <= 0L || ms == Long.MAX_VALUE) return "N/A"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    /** Files to attach to the support zip, either of which may be null when absent or dropped. */
    data class LogAttachments(val consoleLogFile: File?, val rpnLogFile: File?)

    // dumps console + RPN logs within the budget left by fixed attachments;
    // RPN logs are never dropped, console logs need MIN_CONSOLE_LOG_ATTACHMENT_BYTES
    suspend fun dumpLogsWithBudget(
        ctx: Context,
        consoleLogRepository: ConsoleLogRepository,
        rpnLogRepository: RpnLogRepository,
        fixedAttachmentSize: Long,
    ): LogAttachments {
        val supportDir = File(ctx.filesDir, "support").also { it.mkdirs() }
        val consoleLogFile = File(supportDir, SUPPORT_CONSOLE_LOG_FILE_NAME)
        val rpnLogFile = File(supportDir, SUPPORT_RPN_LOG_FILE_NAME)
        consoleLogFile.delete()
        rpnLogFile.delete()

        val hasConsoleLogs = runCatching { consoleLogRepository.getLogCount() > 0 }.getOrElse { false }
        val hasRpnLogs = runCatching { rpnLogRepository.getLogCount() > 0 }.getOrElse { false }
        if (!hasConsoleLogs && !hasRpnLogs) return LogAttachments(null, null)

        fun logBudget(): Long =
            (BugReportZipper.MAX_ZIP_SIZE_BYTES - fixedAttachmentSize - WIRELOG_ATTACH_LIMIT_BYTES - SUPPORT_ZIP_OVERHEAD_RESERVE_BYTES)
                .coerceAtLeast(0L)

        val includeConsoleLogs = hasConsoleLogs && logBudget() >= MIN_CONSOLE_LOG_ATTACHMENT_BYTES
        val maxLogBytes = logBudget()
        val exportedRpnLogs =
            hasRpnLogs && BugReportZipper.dumpRpnLogs(rpnLogRepository, rpnLogFile, maxBytes = maxLogBytes)
        val remainingBudget = logBudget() - (if (exportedRpnLogs) rpnLogFile.length() else 0L)
        val exportedConsoleLogs = includeConsoleLogs &&
            remainingBudget >= MIN_CONSOLE_LOG_ATTACHMENT_BYTES &&
            BugReportZipper.dumpConsoleLogs(consoleLogRepository, consoleLogFile, maxBytes = remainingBudget)

        return LogAttachments(
            consoleLogFile = consoleLogFile.takeIf { exportedConsoleLogs && it.length() > 0L },
            rpnLogFile = rpnLogFile.takeIf { exportedRpnLogs && it.length() > 0L },
        )
    }
}
