package com.celzero.bravedns.scheduler

import com.celzero.bravedns.scheduler.EnhancedBugReport.PREFIX_GO_CRASH
import com.celzero.bravedns.scheduler.EnhancedBugReport.PREFIX_GO_LOG
import com.celzero.bravedns.scheduler.EnhancedBugReport.PREFIX_KOTLIN
import com.celzero.bravedns.util.ExceptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the reporting contract of [EnhancedBugReport.sendFileToFirebase] (the
 * ExceptionParser-based implementation): real tombstone contents are parsed by
 * [ExceptionParser], and the branch decision (parsed path vs legacy fallback) plus the
 * Crashlytics exception message format are verified — without touching Firebase or Koin.
 */
class EnhancedBugReportTest {

    private fun reportType(fileName: String): String = when {
        fileName.startsWith(PREFIX_GO_CRASH) -> "GoCrash"
        fileName.startsWith(PREFIX_GO_LOG) -> "GoLog"
        fileName.startsWith(PREFIX_KOTLIN) -> "KotlinCrash"
        else -> "CrashLog"
    }

    // -- Parsed path (frames found → parsed.toThrowable(context = "$type ${file.name}")) ----

    @Test
    fun `kotlin tombstone takes parsed path with restored jvm frames`() {
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
        val fileName = "${PREFIX_KOTLIN}1787938302000.txt"

        // sendFileToFirebase takes the parsed path only when frames are non-empty.
        val parsed = ExceptionParser.parse(content)
        assertTrue(parsed.frames.isNotEmpty())
        assertEquals(ExceptionParser.TraceType.JAVA, parsed.type)

        val t = parsed.toThrowable(context = "${reportType(fileName)} $fileName")
        val st = t.stackTrace

        assertEquals(
            "[KotlinCrash $fileName] " +
                "android.database.sqlite.SQLiteFullException: database or disk is full " +
                "(code 13 SQLITE_FULL)",
            t.message
        )
        assertEquals("android.database.sqlite.SQLiteConnection", st[0].className)
        assertEquals("nativeExecuteForChangedRowCount", st[0].methodName)
        assertTrue(st[0].isNativeMethod)
        assertEquals("androidx.room.RoomDatabase", st[2].className)
        assertEquals("RoomDatabase.kt", st[2].fileName)
        assertEquals(594, st[2].lineNumber)
        assertEquals("LogDatabase.kt", st[3].fileName)
        assertEquals(77, st[3].lineNumber)
        // the trace must reflect the captured crash, not the reporter call-site
        assertTrue(st.none { it.className.startsWith("EnhancedBugReport") })
    }

    @Test
    fun `forgiving parser keeps valid frames from partially malformed kotlin tombstone`() {
        val content =
            """
                java.lang.IllegalStateException: broken
                    at example.WithoutLine(Source.kt)
                    at example.Unknown.run(Unknown Source)
                    at example.Native.call(Native Method)

                    at malformed
                    random diagnostic text
            """.trimIndent()

        // sendFileToFirebase must still take the parsed path (frames survive), with the
        // malformed lines left out of the trace but preserved in raw for Crashlytics log().
        val parsed = ExceptionParser.parse(content)
        assertEquals(3, parsed.frames.size)

        val t = parsed.toThrowable(context = "${reportType("kotlin_1.txt")} kotlin_1.txt")
        val st = t.stackTrace
        assertEquals(3, st.size)
        assertEquals("Source.kt", st[0].fileName)
        assertEquals(-1, st[0].lineNumber)
        assertNull(st[1].fileName)
        assertEquals(-1, st[1].lineNumber)
        assertTrue(st[2].isNativeMethod)
        assertTrue(parsed.raw.contains("random diagnostic text"))
    }

    @Test
    fun `go panic takes parsed path and pairs function with source location`() {
        val content =
            """
                panic: database is full

                goroutine 23 [running]:
                github.com/celzero/firestack/tunnel.(*Writer).write(0x140001)
                    /workspace/tunnel/writer.go:87 +0x128
                created by github.com/celzero/firestack/tunnel.Start in goroutine 1
                    /workspace/tunnel/start.go:41 +0x74
            """.trimIndent()
        val fileName = "${PREFIX_GO_CRASH}1787938302000.txt"

        val parsed = ExceptionParser.parse(content)
        assertTrue(parsed.frames.isNotEmpty())
        assertEquals(ExceptionParser.TraceType.GO, parsed.type)

        val t = parsed.toThrowable(context = "${reportType(fileName)} $fileName")
        assertEquals("[GoCrash $fileName] database is full", t.message)
        val st = t.stackTrace
        // `created by` (no trailing parens) is not a frame in the ExceptionParser dialect.
        assertEquals(1, st.size)
        assertEquals("github.com/celzero/firestack/tunnel.(*Writer)", st[0].className)
        assertEquals("write", st[0].methodName)
        assertEquals("/workspace/tunnel/writer.go", st[0].fileName)
        assertEquals(87, st[0].lineNumber)
    }

    // -- Legacy fallback path (no frames → RuntimeException with 2 KB preview) -------------

    @Test
    fun `non crash go log takes legacy fallback path`() {
        val content = "diagnostic line\n".repeat(500)
        val fileName = "${PREFIX_GO_LOG}1.txt"

        val parsed = ExceptionParser.parse(content)

        // the branch condition sendFileToFirebase uses for the legacy fallback
        assertTrue(parsed.frames.isEmpty())
        assertEquals(ExceptionParser.TraceType.UNKNOWN, parsed.type)
        // fallback message preview shape: "[$type] $fileName\n" + first 2 KB of content
        val expectedPreview = "[$fileName]\n${content.take(2 * 1024)}"
        assertEquals(expectedPreview, "[$fileName]\n${parsed.raw.take(2 * 1024)}")
        // PR B reads the complete original content — never truncated before parsing.
        assertEquals(content, parsed.raw)
    }

    @Test
    fun `go crash mentioning panic without frames falls back to legacy path`() {
        val content = "truncated panic"
        val fileName = "${PREFIX_GO_CRASH}1.txt"

        val parsed = ExceptionParser.parse(content)

        // a bare `panic:`-ish fragment with no function/source pair must not be misdetected
        assertTrue(parsed.frames.isEmpty())
        assertEquals("[GoCrash $fileName]\ntruncated panic",
            "[${reportType(fileName)} $fileName]\n${parsed.raw}")
    }

    @Test
    fun `long non crash content is fully preserved for chunked log calls`() {
        // Crashlytics log() ring buffer is 64 KB; the reporter chunks the full raw content
        // so nothing is lost even when the parse falls back.
        val content = "golog line without any trace\n".repeat(2000)

        val parsed = ExceptionParser.parse(content)

        assertTrue(parsed.frames.isEmpty())
        assertEquals(content.length, parsed.raw.length)
        assertEquals(content, parsed.raw)
    }
}
