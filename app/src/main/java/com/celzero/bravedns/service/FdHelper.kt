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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_BUG_REPORT
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.celzero.bravedns.service.FdHelper.fcntlGetInt
import com.celzero.bravedns.service.FdHelper.fcntlSetInt
import com.celzero.bravedns.service.FdHelper.makeBlocking
import com.celzero.bravedns.util.Utilities.isAtleastO_MR1
import java.io.FileDescriptor
import java.lang.reflect.Field

/**
 * Shared utilities for duplicating a raw Go fd integer into a [ParcelFileDescriptor] we own,
 * and for making that fd blocking so callers can use [Os.read].
 *
 * Both [GoCrashFileDescriptorReader] and [GoLogFileDescriptorReader] need exactly the same
 * logic.
 *
 * All methods are on the companion object so callers need no instance.
 */
internal object FdHelper {

    /**
     * Duplicates the raw integer file descriptor [fd] supplied by the Go runtime into a
     * [ParcelFileDescriptor] that the caller owns independently of Go's copy.
     *
     * Steps:
     *   1. Write [fd] into a blank [FileDescriptor] via the private `descriptor` field.
     *      We never close this wrapper, Go owns the underlying fd number.
     *   2. Call [Os.dup] to get a new fd number pointing at the same kernel object.
     *   3. Attempt to clear O_NONBLOCK on the dup so callers can use blocking [Os.read].
     *   4. Wrap the dup's fd number in [ParcelFileDescriptor.adoptFd] so we have a closeable
     *      handle with a proper lifecycle.
     *
     * Returns null if any step fails; the caller should abort
     */
    fun duplicate(fd: Long, logTag: String): ParcelFileDescriptor? {
        if (fd <= 0L) {
            Logger.w(LOG_TAG_BUG_REPORT, "$logTag FdHelper.duplicate: invalid fd=$fd")
            return null
        }

        // Step 1: wrap the raw int in a FileDescriptor WITHOUT taking ownership.
        val sourceFd = FileDescriptor()
        if (!setFdInt(sourceFd, fd.toInt(), logTag)) return null

        // Step 2: dup() to get our own fd number.
        val dupFd: FileDescriptor = try {
            Os.dup(sourceFd)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "$logTag FdHelper.duplicate: dup() failed: ${e.message}", e)
            return null
        }

        // Step 3: best-effort: make the dup blocking.
        // If this fails we fall back to EAGAIN / EWOULDBLOCK handling at the call site.
        makeBlocking(dupFd, logTag)

        // Step 4: extract the dup's int and adopt it into a ParcelFileDescriptor.
        val dupInt = getFdInt(dupFd, logTag) ?: run {
            // Can't extract the int, close the dup to avoid a leak and abort.
            try { Os.close(dupFd) } catch (_: Exception) {}
            return null
        }

        return try {
            ParcelFileDescriptor.adoptFd(dupInt)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT, "$logTag FdHelper.duplicate: adoptFd failed: ${e.message}", e)
            try { Os.close(dupFd) } catch (_: Exception) {}
            null
        }
    }

    /**
     * Writes [value] into [fd]'s private `descriptor` int field.
     *
     * Why this is necessary:
     *   Go passes us a raw C `int` fd.  The Android SDK has no public API to construct a
     *   [FileDescriptor] from an int (only [ParcelFileDescriptor.adoptFd] exists, and that
     *   takes ownership).  The private field has been present and stable since API 1; it is
     *   accessed via reflection with a cached [Field] handle so the cost is a single volatile
     *   read after the first call.
     *
     * Returns true on success, false on any reflection failure.
     */
    fun setFdInt(fd: FileDescriptor, value: Int, logTag: String): Boolean {
        return try {
            descriptorField.setInt(fd, value)
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT,
                "$logTag FdHelper.setFdInt failed (SDK changed?): ${e.message}", e)
            false
        }
    }

    /**
     * Reads the private `descriptor` int from [fd].
     * Returns null on reflection failure so callers can abort cleanly.
     */
    fun getFdInt(fd: FileDescriptor, logTag: String): Int? {
        return try {
            descriptorField.getInt(fd)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BUG_REPORT,
                "$logTag FdHelper.getFdInt failed (SDK changed?): ${e.message}", e)
            null
        }
    }

    /**
     * Clears the O_NONBLOCK flag on [fd] so that [Os.read] blocks until data is available.
     *
     * Why blocking is preferred:
     *   A non-blocking fd forces the read loop to busy-spin or yield on EAGAIN, wasting CPU
     *   and making the coroutine harder to reason about.  A blocking fd lets [Os.read] park
     *   the thread until Go writes data, which is exactly what we want.
     *
     * Why this is best-effort:
     *   fcntlInt is not in the public Android SDK.  We try two reflection strategies:
     *     1. libcore.io.Libcore.os: available Android 5–15, most reliable
     *     2. android.system.Os: public class but fcntlInt may not be exposed
     *   If both fail we log and proceed; the read loop must handle EAGAIN via yield+retry.
     *
     * Both strategies are tried in the same method so the result (flags or null) is obtained
     * once, checked, and the updated value is set atomically within one method call. We never
     * partially read in one strategy and write in another.
     */
    fun makeBlocking(fd: FileDescriptor, logTag: String) {
        val currentFlags = fcntlGetInt(fd, OsConstants.F_GETFL) ?: run {
            Logger.w(LOG_TAG_BUG_REPORT,
                "$logTag FdHelper.makeBlocking: F_GETFL failed, fd stays non-blocking")
            return
        }

        if ((currentFlags and OsConstants.O_NONBLOCK) == 0) {
            Logger.d(LOG_TAG_BUG_REPORT,
                "$logTag FdHelper.makeBlocking: already blocking (flags=0x${currentFlags.toString(16)})")
            return   // already blocking nothing to do
        }

        val blockingFlags = if (isAtleastO_MR1()) {
            currentFlags and OsConstants.O_NONBLOCK.inv() and OsConstants.O_CLOEXEC.inv()
        } else {
            currentFlags and OsConstants.O_NONBLOCK.inv()
        }
        val setOk = fcntlSetInt(fd, OsConstants.F_SETFL, blockingFlags)
        if (!setOk) {
            Logger.w(LOG_TAG_BUG_REPORT,
                "$logTag FdHelper.makeBlocking: F_SETFL failed, fd stays non-blocking")
            return
        }

        // Verify: read back and confirm O_NONBLOCK is gone.
        val updatedFlags = fcntlGetInt(fd, OsConstants.F_GETFL)
        val isNowBlocking = updatedFlags != null && (updatedFlags and OsConstants.O_NONBLOCK) == 0
        Logger.d(LOG_TAG_BUG_REPORT,
            "$logTag FdHelper.makeBlocking: before=0x${currentFlags.toString(16)} " +
            "after=0x${updatedFlags?.toString(16)} blocking=$isNowBlocking")
    }

    /**
     * Calls `fcntl(fd, cmd, 0)` via the best available reflection strategy.
     * Returns the result int, or null if both strategies fail.
     *
     * WHY the two-strategy approach:
     *   `libcore.io.Libcore.os` has exposed `fcntlInt` since Android 5 and is the most
     *   reliable path through Android 15.  `android.system.Os` is a public class but its
     *   `fcntlInt` overload is hidden (`@hide`) and may not be available on all ROM variants.
     *   Using both gives maximum compatibility.
     */
    private fun fcntlGetInt(fd: FileDescriptor, cmd: Int): Int? {
        // Strategy 1: libcore.io.Libcore.os (Android 5+)
        try {
            val os = libcoreOsInstance()
            val m = os.javaClass.getMethod("fcntlInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            return m.invoke(os, fd, cmd, 0) as Int
        } catch (_: Exception) {}

        // Strategy 2: android.system.Os (public class, hidden method)
        try {
            val m = Class.forName("android.system.Os").getMethod("fcntlInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            return m.invoke(null, fd, cmd, 0) as Int
        } catch (_: Exception) {}

        return null
    }

    /**
     * Calls `fcntl(fd, cmd, arg)` to set flags. Returns true on success.
     * Mirrors [fcntlGetInt] with the same two-strategy approach.
     */
    private fun fcntlSetInt(fd: FileDescriptor, cmd: Int, arg: Int): Boolean {
        // Strategy 1
        try {
            val os = libcoreOsInstance()
            val m = os.javaClass.getMethod("fcntlInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            m.invoke(os, fd, cmd, arg)
            return true
        } catch (_: Exception) {}

        // Strategy 2
        try {
            val m = Class.forName("android.system.Os").getMethod("fcntlInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType)
            m.invoke(null, fd, cmd, arg)
            return true
        } catch (_: Exception) {}

        return false
    }

    /**
     * Returns the `libcore.io.Libcore.os` singleton.
     *
     * This object implements `libcore.io.Os` and exposes `fcntlInt`.  The field is not
     * cached here because this is a private helper only called from [fcntlGetInt] and
     * [fcntlSetInt] which are themselves only called from [makeBlocking] i.e., at most
     * twice per reader startup, never on the hot read path.
     */
    private fun libcoreOsInstance(): Any {
        val cls = Class.forName("libcore.io.Libcore")
        val field = cls.getDeclaredField("os")
        field.isAccessible = true
        return field.get(null)!!
    }

    /**
     * Lazily resolved, cached [Field] for the private `FileDescriptor.descriptor` int.
     */
    private val descriptorField: Field by lazy {
        try {
            FileDescriptor::class.java
                .getDeclaredField("descriptor")
                .also { it.isAccessible = true }
        } catch (e: NoSuchFieldException) {
            // This should never happen on any shipping Android version.
            // Log clearly so it's obvious what broke if a future SDK removes it.
            Logger.e(LOG_TAG_BUG_REPORT,
                "FdHelper: FileDescriptor.descriptor field not found, Android SDK changed! " +
                "Go log/crash capture will not work.", e)
            throw e  // re-throw: the lazy delegate will remember the exception and re-throw
                     // on every future access, giving clear errors rather than silent failures.
        }
    }
}

