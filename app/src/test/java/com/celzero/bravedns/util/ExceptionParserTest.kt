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
package com.celzero.bravedns.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionParserTest {

    // -- Go panics ---------------------------------------------------------------

    @Test
    fun `parses normal Go panic`() {
        val raw = """
            panic: runtime error: index out of range

            goroutine 1 [running]:
            main.foo()
                /app/foo.go:42 +0x123
            main.main()
                /app/main.go:10 +0x20
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals("runtime error: index out of range", parsed.message)
        assertEquals(2, parsed.frames.size)
        assertEquals("main.foo", parsed.frames[0].function)
        assertEquals("/app/foo.go", parsed.frames[0].file)
        assertEquals(42, parsed.frames[0].line)
        assertEquals("main.main", parsed.frames[1].function)
        assertEquals("/app/main.go", parsed.frames[1].file)
        assertEquals(10, parsed.frames[1].line)
        assertEquals(raw, parsed.raw)
    }

    @Test
    fun `parses Go panic with multiple goroutines and runtime frames`() {
        val raw = """
            panic: runtime error: invalid memory address or nil pointer dereference
            [signal SIGSEGV: segmentation violation code=0x1 addr=0x0 pc=0x4a1b2c]

            goroutine 7 [running]:
            github.com/celzero/firestack.(*Tunnel).Serve(0xc0000b4000)
                /go/src/firestack/tunnel.go:120 +0x8f
            created by github.com/celzero/firestack.(*Intra).Start
                /go/src/firestack/intra.go:64 +0x1c5

            goroutine 1 [chan receive]:
            main.waitForExit()
                /app/main.go:88 +0x40
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals("runtime error: invalid memory address or nil pointer dereference", parsed.message)
        // function frame pairs from both goroutine blocks; `created by` (no trailing parens)
        // and `[signal ...]` lines must not become frames.
        assertEquals(2, parsed.frames.size)
        assertEquals(
            "github.com/celzero/firestack.(*Tunnel).Serve",
            parsed.frames[0].function
        )
        assertEquals("/go/src/firestack/tunnel.go", parsed.frames[0].file)
        assertEquals(120, parsed.frames[0].line)
        assertEquals("main.waitForExit", parsed.frames[1].function)
        assertEquals("/app/main.go", parsed.frames[1].file)
        assertEquals(88, parsed.frames[1].line)
    }

    @Test
    fun `go panic with malformed source line keeps valid frames only`() {
        val raw = """
            panic: oops

            goroutine 1 [running]:
            main.good()
                /app/good.go:1 +0x1
            main.bad()
                this line is not a source location
            main.alsoGood()
                /app/also.go:2 +0x2
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(2, parsed.frames.size)
        assertEquals("main.good", parsed.frames[0].function)
        assertEquals("main.alsoGood", parsed.frames[1].function)
    }

    // -- Java/Kotlin traces --------------------------------------------------------

    @Test
    fun `parses Java-Kotlin exception`() {
        val raw = """
            java.lang.NullPointerException: something went wrong
            	at com.celzero.bravedns.service.Foo.bar(Foo.kt:123)
            	at com.celzero.bravedns.service.Foo.baz(Foo.kt:45)
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.JAVA, parsed.type)
        assertEquals("java.lang.NullPointerException: something went wrong", parsed.message)
        assertEquals(2, parsed.frames.size)
        assertEquals("com.celzero.bravedns.service.Foo.bar", parsed.frames[0].function)
        assertEquals("Foo.kt", parsed.frames[0].file)
        assertEquals(123, parsed.frames[0].line)
    }

    @Test
    fun `parses Kotlin crash file with time and token prefix lines`() {
        val raw = """
            8/29/26 6:00 PM
            Token: abcd1234abcd1234
            ---Uncaught Exception main---
            java.lang.IllegalStateException: boom
            	at com.celzero.bravedns.ui.HomeScreenActivity.onC(HomeScreenActivity.kt:99)
            	at android.app.Activity.performCreate(Activity.java:8051)
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.JAVA, parsed.type)
        assertEquals("java.lang.IllegalStateException: boom", parsed.message)
        assertEquals(2, parsed.frames.size)
        assertEquals("HomeScreenActivity.kt", parsed.frames[0].file)
        assertEquals(99, parsed.frames[0].line)
        assertEquals("Activity.java", parsed.frames[1].file)
    }

    @Test
    fun `Caused by headers are not frames`() {
        val raw = """
            java.lang.IllegalStateException: outer
            	at com.example.A.a(A.kt:1)
            Caused by: java.lang.IllegalArgumentException: inner
            	at com.example.B.b(B.kt:2)
            	... 1 more
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.JAVA, parsed.type)
        // message comes from the first (outer) header, not the `Caused by` one
        assertEquals("java.lang.IllegalStateException: outer", parsed.message)
        // both real `at` frames are kept; `Caused by` header and `... N more` are not frames
        assertEquals(2, parsed.frames.size)
        assertEquals("com.example.A.a", parsed.frames[0].function)
        assertEquals("com.example.B.b", parsed.frames[1].function)
        // preserved for the Crashlytics log
        assertTrue(parsed.raw.contains("Caused by: java.lang.IllegalArgumentException: inner"))
        assertTrue(parsed.raw.contains("... 1 more"))
    }

    @Test
    fun `Suppressed headers are not frames`() {
        val raw = """
            java.lang.RuntimeException: primary
            	at com.example.A.run(A.kt:10)
            Suppressed: java.io.IOException: during close
            	at com.example.C.close(C.kt:20)
            	... 2 more
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(2, parsed.frames.size)
        assertEquals("com.example.A.run", parsed.frames[0].function)
        assertEquals("com.example.C.close", parsed.frames[1].function)
        assertTrue(parsed.raw.contains("Suppressed: java.io.IOException: during close"))
    }

    @Test
    fun `Unknown Source location yields null file and -1 line`() {
        val raw = """
            java.lang.Exception: x
            	at com.example.A.a(A.java)
            	at com.example.B.b(Unknown Source)
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)

        assertEquals(2, parsed.frames.size)
        assertEquals("A.java", parsed.frames[0].file)
        assertEquals(-1, parsed.frames[0].line)
        assertNull(parsed.frames[1].file)
        assertEquals(-1, parsed.frames[1].line)
    }

    @Test
    fun `Native Method location yields null file and -2 line`() {
        val raw = """
            java.lang.Exception: x
            	at com.example.A.a(Native Method)
            	at com.example.B.b(B.kt:7)
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)

        assertEquals(2, parsed.frames.size)
        assertNull(parsed.frames[0].file)
        assertEquals(-2, parsed.frames[0].line)
        assertEquals(7, parsed.frames[1].line)
    }

    @Test
    fun `inner class and lambda frames are parsed`() {
        val raw = """
            java.lang.Exception: x
            	at com.example.Outer${'$'}Inner.call(Outer.java:5)
            	at com.example.Outer${'$'}run${'$'}1.invoke(Outer.kt:6)
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)

        assertEquals(2, parsed.frames.size)
        assertEquals("com.example.Outer${'$'}Inner.call", parsed.frames[0].function)
        assertEquals("com.example.Outer${'$'}run${'$'}1.invoke", parsed.frames[1].function)
    }

    // -- Degraded inputs -----------------------------------------------------------

    @Test
    fun `malformed at lines are skipped but valid frames kept`() {
        val raw = """
            java.lang.Exception: x
            	at com.example.A.a(A.kt:1
            	at (broken
            at no.leading whitespace but malformed
            	at com.example.B.b(B.kt:2)
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.JAVA, parsed.type)
        assertEquals(1, parsed.frames.size)
        assertEquals("com.example.B.b", parsed.frames[0].function)
        assertTrue(parsed.raw.contains("at (broken"))
    }

    @Test
    fun `empty file yields UNKNOWN with no frames`() {
        val parsed = ExceptionParser.parse("")

        assertEquals(ExceptionParser.TraceType.UNKNOWN, parsed.type)
        assertTrue(parsed.frames.isEmpty())
        assertEquals("", parsed.raw)
    }

    @Test
    fun `blank content yields UNKNOWN with no frames`() {
        val parsed = ExceptionParser.parse("\n\n   \n")

        assertEquals(ExceptionParser.TraceType.UNKNOWN, parsed.type)
        assertTrue(parsed.frames.isEmpty())
    }

    @Test
    fun `unrecognised content yields UNKNOWN and preserves raw`() {
        val raw = "some random go log line\nanother line without any trace"
        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.UNKNOWN, parsed.type)
        assertTrue(parsed.frames.isEmpty())
        assertEquals(raw, parsed.raw)
    }

    @Test
    fun `trailing newlines are preserved in raw`() {
        val raw = "panic: boom\n\ngoroutine 1 [running]:\nmain.f()\n\t/app/f.go:1 +0x1\n\n\n"
        val parsed = ExceptionParser.parse(raw)

        assertEquals(raw, parsed.raw)
        assertEquals(1, parsed.frames.size)
    }

    @Test
    fun `panic line without frames is not misdetected as Go trace`() {
        val raw = "log line mentioning panic: but no trace\njava.lang.Exception: x\n\tat a.B.c(B.kt:3)"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.JAVA, parsed.type)
        assertEquals(1, parsed.frames.size)
    }

    // -- Imported throwable ----------------------------------------------------------

    @Test
    fun `toThrowable carries parsed frames not the call-site`() {
        val raw = """
            panic: runtime error: index out of range

            goroutine 1 [running]:
            main.foo()
                /app/foo.go:42 +0x123
            main.main()
                /app/main.go:10 +0x20
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)
        val t = parsed.toThrowable(context = "GoCrash gocrash_1.txt")
        val st = t.stackTrace

        assertEquals("[GoCrash gocrash_1.txt] runtime error: index out of range", t.message)
        assertEquals(2, st.size)

        // className/methodName split on the last dot
        assertEquals("main", st[0].className)
        assertEquals("foo", st[0].methodName)
        // StackTraceElement keeps the full source path as-is
        assertEquals("/app/foo.go", st[0].fileName)
        assertEquals(42, st[0].lineNumber)

        assertEquals("main", st[1].className)
        assertEquals("main", st[1].methodName)
        assertEquals("/app/main.go", st[1].fileName)
        assertEquals(10, st[1].lineNumber)

        // the trace must not originate from this test method / parser internals
        assertTrue(st.none { it.className.startsWith("ExceptionParser") })
    }

    @Test
    fun `toThrowable without context keeps plain message`() {
        val parsed = ExceptionParser.parse(
            "java.lang.Exception: x\n\tat a.B.c(B.kt:3)"
        )
        val t = parsed.toThrowable()

        assertEquals("java.lang.Exception: x", t.message)
        assertEquals(1, t.stackTrace.size)
        assertEquals("a.B.c", t.stackTrace[0].className + "." + t.stackTrace[0].methodName)
    }
}
