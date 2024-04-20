/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.backup

import Logger
import android.content.Context
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.service.VpnController
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class BackupHelper {

    companion object {
        // MIME type is used for unknown binary files (if stored locally)
        const val INTENT_TYPE_OCTET = "application/octet-stream"

        // google drive will have its own mimetypes changed to "x-zip"
        const val INTENT_TYPE_XZIP = "application/x-zip"

        // file name for the backup file
        const val BACKUP_FILE_NAME = "Rethink_"

        // date format for backup  file name
        const val BACKUP_FILE_NAME_DATETIME = "yyMMddHHmmss"

        // backup file extension
        const val BACKUP_FILE_EXTN = ".rbk"

        // backup shared pref file name
        const val SHARED_PREFS_BACKUP_FILE_NAME = "shared_prefs_backup.txt"

        // metadata file name
        const val METADATA_FILENAME = "rethink_backup.txt"

        // data builder uri string for restore worker
        const val DATA_BUILDER_RESTORE_URI = "restoreFileUri"

        // data builder uri string for backup worker
        const val DATA_BUILDER_BACKUP_URI = "backupFileUri"

        // temp zip  file name
        const val TEMP_ZIP_FILE_NAME = "temp.rbk"

        // intent scheme
        const val INTENT_SCHEME = "content"

        // restart app after database restore
        const val INTENT_RESTART_APP = "restartApp"

        // metadata constants
        // version
        const val VERSION = "version"
        // package name
        const val PACKAGE_NAME = "package"
        // time when the backup  is taken
        const val CREATED_TIME = "createdTs"

        fun getTempDir(context: Context): File {
            // temp dir (files/RethinkDns/)
            val backupDirectoryPath: String =
                context.filesDir.absolutePath +
                    File.separator +
                    context.getString(R.string.app_name)
            val file = File(backupDirectoryPath)
            if (!file.exists()) {
                file.mkdir()
            }

            return file
        }

        fun getRethinkDatabase(context: Context): File? {
            val path =
                (context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile?.path
                    ?: return null) + File.separator
            return File(path)
        }

        fun stopVpn(context: Context) {
            Logger.i(Logger.LOG_TAG_BACKUP_RESTORE, "calling vpn stop from backup helper")
            VpnController.stop(context)
        }

        fun startVpn(context: Context) {
            Logger.i(Logger.LOG_TAG_BACKUP_RESTORE, "calling vpn start from backup helper")
            VpnController.start(context)
        }

        fun deleteResidue(backupFile: File) {
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }

        fun unzip(inputStream: InputStream?, path: String): Boolean {
            var zis: ZipInputStream? = null
            try {
                zis = ZipInputStream(BufferedInputStream(inputStream))
                var ze: ZipEntry? = zis.nextEntry
                while (ze != null) {
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    var count: Int
                    val filename: String = ze.name
                    val fout = FileOutputStream(path + File.separator + filename)

                    // reading and writing
                    while (zis.read(buffer).also { r -> count = r } != -1) {
                        baos.write(buffer, 0, count)
                        val bytes = baos.toByteArray()
                        fout.write(bytes)
                        baos.reset()
                    }
                    fout.close()
                    zis.closeEntry()
                    ze = zis.nextEntry
                }
            } catch (e: Exception) {
                return false
            } finally {
                zis?.close()
            }
            return true
        }

        fun getFileNameFromPath(file: String): String {
            return file.substring(file.lastIndexOf("/") + 1)
        }
    }
}
