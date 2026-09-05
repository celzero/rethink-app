package com.celzero.bravedns.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedBugReportTest {

    @Test
    fun `kotlin tombstone preserves exception message and jvm frames`() {
        val content =
            """
                2026-08-28 10:31:42
                Token: redacted
                Build: release
                android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)
                    at android.database.sqlite.SQLiteConnection.nativeExecuteForChangedRowCount(Native Method)
                    at android.database.sqlite.SQLiteConnection.executeForChangedRowCount(SQLiteConnection.java:1354)
                    at androidx.room.RoomDatabase.assertNotSuspendingTransaction(RoomDatabase.kt:594)
                    at com.celzero.bravedns.database.LogDatabase.insert(LogDatabase.kt:77)
            """.trimIndent()

        val exception =
            EnhancedBugReport.buildReportException("kotlin_1787938302000.txt", content)

        assertEquals(
            "[KotlinCrash] kotlin_1787938302000.txt: " +
                "android.database.sqlite.SQLiteFullException: database or disk is full " +
                "(code 13 SQLITE_FULL)",
            exception.message
        )
        assertEquals("android.database.sqlite.SQLiteConnection", exception.stackTrace[0].className)
        assertEquals("nativeExecuteForChangedRowCount", exception.stackTrace[0].methodName)
        assertTrue(exception.stackTrace[0].isNativeMethod)
        assertEquals("androidx.room.RoomDatabase", exception.stackTrace[2].className)
        assertEquals("RoomDatabase.kt", exception.stackTrace[2].fileName)
        assertEquals(594, exception.stackTrace[2].lineNumber)
        assertEquals("LogDatabase.kt", exception.stackTrace[3].fileName)
        assertEquals(77, exception.stackTrace[3].lineNumber)
    }

    @Test
    fun `jvm parser safely handles incomplete and malformed frames`() {
        val content =
            """
                java.lang.IllegalStateException: broken
                    at example.WithoutLine(Source.kt)
                    at example.Unknown.run(Unknown Source)
                    at example.Native.call(Native Method)

                    at malformed
                    random diagnostic text
            """.trimIndent()

        val exception = EnhancedBugReport.buildReportException("kotlin_1.txt", content)

        assertEquals(3, exception.stackTrace.size)
        assertEquals("Source.kt", exception.stackTrace[0].fileName)
        assertEquals(-1, exception.stackTrace[0].lineNumber)
        assertNull(exception.stackTrace[1].fileName)
        assertEquals(-1, exception.stackTrace[1].lineNumber)
        assertTrue(exception.stackTrace[2].isNativeMethod)
    }

    @Test
    fun `go panic pairs functions with source locations including created by`() {
        val content =
            """
                panic: database is full

                goroutine 23 [running]:
                github.com/celzero/firestack/tunnel.(*Writer).write(0x140001)
                    /workspace/tunnel/writer.go:87 +0x128
                created by github.com/celzero/firestack/tunnel.Start in goroutine 1
                    /workspace/tunnel/start.go:41 +0x74
            """.trimIndent()

        val exception =
            EnhancedBugReport.buildReportException("gocrash_1787938302000.txt", content)

        assertEquals("[GoCrash] gocrash_1787938302000.txt: panic: database is full", exception.message)
        assertEquals(2, exception.stackTrace.size)
        assertEquals("github.com/celzero/firestack/tunnel.(*Writer)", exception.stackTrace[0].className)
        assertEquals("write", exception.stackTrace[0].methodName)
        assertEquals("writer.go", exception.stackTrace[0].fileName)
        assertEquals(87, exception.stackTrace[0].lineNumber)
        assertEquals("github.com/celzero/firestack/tunnel", exception.stackTrace[1].className)
        assertEquals("Start", exception.stackTrace[1].methodName)
        assertEquals("start.go", exception.stackTrace[1].fileName)
        assertEquals(41, exception.stackTrace[1].lineNumber)
    }

    @Test
    fun `malformed and non crash logs use bounded fallback with existing labels`() {
        val longContent = "diagnostic line\n".repeat(500)

        val goCrash = EnhancedBugReport.buildReportException("gocrash_1.txt", "truncated panic")
        val goLog = EnhancedBugReport.buildReportException("golog_1.txt", longContent)
        val kotlin = EnhancedBugReport.buildReportException("kotlin_1.txt", "metadata only")
        val unknown = EnhancedBugReport.buildReportException("other.txt", "plain log")

        assertTrue(goCrash.message!!.startsWith("[GoCrash] gocrash_1.txt\n"))
        assertTrue(goLog.message!!.startsWith("[GoLog] golog_1.txt\n"))
        assertTrue(kotlin.message!!.startsWith("[KotlinCrash] kotlin_1.txt\n"))
        assertTrue(unknown.message!!.startsWith("[CrashLog] other.txt\n"))
        assertTrue(goLog.message!!.length <= 2 * 1024 + "[GoLog] golog_1.txt\n".length)
        assertEquals(EnhancedBugReport::class.java.name, goLog.stackTrace.first().className)
    }
}
