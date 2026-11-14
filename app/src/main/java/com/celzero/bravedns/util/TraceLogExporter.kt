package com.celzero.bravedns.util

import android.content.Context
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import java.io.File

object TraceLogExporter {

    private const val TAG = "TraceLogExporter;"
    private const val TRACE_LOG_FILE_PREFIX = "trace_logs_"

    suspend fun dumpTraceLogsFile(context: Context): File? {
        return try {
            val bytes = VpnController.getAllTraceLogsAsBytes()
            if (bytes == null || bytes.isEmpty()) {
                Logger.i(LOG_TAG_VPN, "$TAG no trace logs to dump")
                return null
            }
            // unique per export so consumers delete only the file they created
            val file = File(
                context.cacheDir,
                "$TRACE_LOG_FILE_PREFIX${System.currentTimeMillis()}.txt",
            )
            file.writeBytes(bytes)
            file
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "$TAG err dumping trace logs: ${e.message}", e)
            null
        }
    }
}
