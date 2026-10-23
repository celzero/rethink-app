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
        // frames from the first two goroutines, nothing filtered; `created by` (no trailing
        // parens) and `[signal ...]` lines must not become frames.
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
        // created-by origin kept separately: only the source location after the
        // `created by` line (no `github.com/celzero` prefix to shorten in this fixture)
        assertEquals("/go/src/firestack/intra.go:64", parsed.createdBy)
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

    // -- Firestack crash envelope ---------------------------------------------------

    @Test
    fun `parses firestack crash envelope with title and first two goroutines`() {
        val raw = """
            Token: fdroid
            icmpecho.reply4 [11] runtime error: index out of range [0] with length 0 [gobind@v20260112110838-e5bc08c]
             (#0)
            <===>
            goroutine 520 [running]:
            github.com/celzero/firestack/intra/log.(*simpleLogger).Stack(0x4000194008, 0x0?)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/log/logger.go:483 +0x15c
            github.com/celzero/firestack/intra/core.Recover(0xb, {0x7d1ff7e760, 0x40003144f0})
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/dontpanic.go:130 +0x128
            panic({0x7d2006b3a0?, 0x40007023c0?})
            	runtime/panic.go:783 +0x120
            gvisor.dev/gvisor/pkg/tcpip/header.ICMPv4.Type(...)
            	gvisor.dev/gvisor@v0.0.0-20250816201027-ba3b9ca85f20/pkg/tcpip/header/icmpv4.go:131
            github.com/celzero/firestack/intra/netstack.(*icmpForwarder).reply4(0x40000e9380, {0x0})
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/netstack/icmp.go:75 +0x994
            created by github.com/celzero/firestack/intra/core.Gx in goroutine 98
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/async.go:60 +0x78

            goroutine 17 [syscall, locked to thread]:
            runtime.goexit({})
            	runtime/asm_arm64.s:1268 +0x4

            goroutine 34 [syscall, locked to thread]:
            runtime.goexit({})
            	runtime/asm_arm64.s:1268 +0x4
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        // headline: last non-blank line before the separator, ` (#0)` skipped, headers ignored
        assertEquals(
            "icmpecho.reply4 [11] runtime error: index out of range [0] with length 0 [gobind@v20260112110838-e5bc08c]",
            parsed.message
        )
        // frames from the first two goroutines, nothing filtered
        assertEquals("github.com/celzero/firestack/intra/log.(*simpleLogger).Stack", parsed.frames[0].function)
        assertEquals("github.com/celzero/firestack/intra/core.Recover", parsed.frames[1].function)
        assertEquals("panic", parsed.frames[2].function)
        assertEquals("gvisor.dev/gvisor/pkg/tcpip/header.ICMPv4.Type", parsed.frames[3].function)
        assertEquals(
            "github.com/celzero/firestack/intra/netstack.(*icmpForwarder).reply4",
            parsed.frames[4].function
        )
        assertEquals(75, parsed.frames[4].line)
        // second goroutine's frames are included
        assertEquals("runtime.goexit", parsed.frames[5].function)
        assertEquals(6, parsed.frames.size)
        // third goroutine block excluded
        assertTrue(parsed.frames.count { it.function == "runtime.goexit" } == 1)
        // created-by subtitle: only the shortened location line after `created by`
        assertEquals(
            "celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/async.go:60",
            parsed.createdBy
        )
    }

    @Test
    fun `envelope without index line still yields title`() {
        val raw = """
            dns53.(*dot).Query [3] context deadline exceeded [gobind@v20260112110838-e5bc08c]
            <===>
            goroutine 9 [running]:
            main.f()
            	/app/f.go:1 +0x1
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals(
            "dns53.(*dot).Query [3] context deadline exceeded [gobind@v20260112110838-e5bc08c]",
            parsed.message
        )
        assertEquals(1, parsed.frames.size)
        assertNull(parsed.createdBy)
    }

    @Test
    fun `parses go-direct crash dump without separator and without panic line`() {
        // Intra.crash(0/2) output written via setCrashOutput: headline + `(#0)` directly
        // followed by the goroutine header on the same line; no `<===>`, no `panic:`.
        val raw = """
            tun: debugging... not a crash [0] runtime error: index out of range [10] with length 0 [gobind@v20261005214959-d3d530e] (#0)goroutine 1360 [running]:
            github.com/celzero/firestack/intra/log.(*simpleLogger).Stack(0x6db2492008, 0x0?)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/log/logger.go:554 +0x148
            panic({0x6dfc057940?, 0x6db26d0630?})
            	runtime/panic.go:859 +0x120
            github.com/celzero/firestack/intra.Crash.func1()
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/tun2socks.go:423 +0x110
            created by github.com/celzero/firestack/intra.Crash in goroutine 328
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/tun2socks.go:412 +0x50

            goroutine 509 [GC scavenge wait]:
            gvisor.dev/gvisor/pkg/sync.Gopark(...)
            	gvisor.dev/gvisor@v0.0.0-20260508212337-96dad6a2da94/pkg/sync/runtime_unsafe.go:34
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals(
            "tun: debugging... not a crash [0] runtime error: index out of range [10] with length 0 [gobind@v20261005214959-d3d530e]",
            parsed.message
        )
        // frames from the first two goroutines, nothing filtered; log plumbing stays
        assertEquals(4, parsed.frames.size)
        assertEquals("github.com/celzero/firestack/intra/log.(*simpleLogger).Stack", parsed.frames[0].function)
        assertEquals("panic", parsed.frames[1].function)
        assertEquals("github.com/celzero/firestack/intra.Crash.func1", parsed.frames[2].function)
        assertEquals(423, parsed.frames[2].line)
        // second goroutine's frames are included
        assertEquals("gvisor.dev/gvisor/pkg/sync.Gopark", parsed.frames[3].function)
        // subtitle = shortened location line after `created by`
        assertEquals(
            "celzero/firestack@v0.0.0-00010101000000-000000000000/intra/tun2socks.go:412",
            parsed.createdBy
        )
    }

    @Test
    fun `parses level-prefixed crash lines from old golog files`() {
        val raw = """
            F tun: debug trace (not a crash) (#0)goroutine 53 [running]:
            F github.com/celzero/firestack/intra/log.(*simpleLogger).Stack(0x1)
            F 	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/log/logger.go:554 +0x148
            F github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify(0x0)
            F 	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/ipn/wgproxy.go:1197 +0x1
            F created by github.com/celzero/firestack/intra/core.Gx in goroutine 9
            F 	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/async.go:60 +0x78
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals("tun: debug trace (not a crash)", parsed.message)
        assertEquals(
            "github.com/celzero/firestack/intra/log.(*simpleLogger).Stack",
            parsed.frames[0].function
        )
        assertEquals(
            "github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify",
            parsed.frames[1].function
        )
        assertEquals(2, parsed.frames.size)
        assertEquals(
            "celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/async.go:60",
            parsed.createdBy
        )
    }

    @Test
    fun `all frames of the first two goroutines are kept, nothing filtered`() {
        val raw = """
            panic: send on closed channel

            goroutine 520 [running]:
            github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify(0x0)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/ipn/wgproxy.go:1197 +0x1
            github.com/celzero/firestack/intra/log.C({0x1})
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/log/log.go:216 +0x8c
            github.com/celzero/firestack/intra/core.applog(0x2, {0x3})
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/dontpanic.go:204 +0x164
            github.com/celzero/firestack/intra/core.Recover(0x0, {0x4})
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/dontpanic.go:177 +0x118
            github.com/celzero/firestack/intra/log.(*ring).process(0x0)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/log/ring.go:68 +0x84

            goroutine 17 [syscall, locked to thread]:
            runtime.goexit({})
            	runtime/asm_arm64.s:1039 +0x4
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        // nothing is filtered: log-plumbing frames stay wherever they appear...
        assertEquals(6, parsed.frames.size)
        assertEquals(
            "github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify",
            parsed.frames[0].function
        )
        assertEquals("github.com/celzero/firestack/intra/log.C", parsed.frames[1].function)
        assertEquals("github.com/celzero/firestack/intra/core.applog", parsed.frames[2].function)
        assertEquals("github.com/celzero/firestack/intra/core.Recover", parsed.frames[3].function)
        assertEquals(
            "github.com/celzero/firestack/intra/log.(*ring).process",
            parsed.frames[4].function
        )
        // ...and the second goroutine's frames are included
        assertEquals("runtime.goexit", parsed.frames[5].function)
        assertEquals(1039, parsed.frames[5].line)
    }

    @Test
    fun `plain panic dump without envelope keeps panic message and no synthetic frame`() {
        val raw = """
            panic: send on closed channel

            goroutine 520 [running]:
            github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify(0x0)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/ipn/wgproxy.go:1197 +0x1
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals("send on closed channel", parsed.message)
        assertEquals(
            "github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify",
            parsed.frames[0].function
        )
        assertNull(parsed.createdBy)
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

        assertEquals("go-panic: runtime error: index out of range", t.message)
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

    @Test
    fun `toThrowable with createdBy prepends synthetic title frame and rewrites message`() {
        val raw = """
            Token: fdroid
            icmpecho.reply4 [11] runtime error: index out of range [0] with length 0 [gobind@v20260112110838-e5bc08c]
             (#0)
            <===>
            goroutine 520 [running]:
            github.com/celzero/firestack/intra/log.(*simpleLogger).Stack(0x0?)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/log/logger.go:483 +0x15c
            gvisor.dev/gvisor/pkg/tcpip/header.ICMPv4.Type(...)
            	gvisor.dev/gvisor@v0.0.0-20250816201027-ba3b9ca85f20/pkg/tcpip/header/icmpv4.go:131
            github.com/celzero/firestack/intra/netstack.(*icmpForwarder).reply4(0x40000e9380, {0x0})
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/netstack/icmp.go:75 +0x994
            created by github.com/celzero/firestack/intra/core.Gx in goroutine 98
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/async.go:60 +0x78
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)
        val t = parsed.toThrowable(context = "GoCrash gocrash_1.txt")
        val st = t.stackTrace

        // message = the location line after `created by` (Crashlytics subtitle); no
        // context prefix on Go traces
        assertEquals(
            "celzero/firestack@v0.0.0-00010101000000-000000000000/intra/core/async.go:60",
            t.message
        )

        // synthetic top frame carries the go-panic headline (Crashlytics issue title);
        // empty methodName avoids the className.methodName concatenation doubling the text
        assertEquals(
            "go-panic: icmpecho.reply4 [11] runtime error: index out of range [0] with length 0 [gobind@v20260112110838-e5bc08c]",
            st[0].className
        )
        assertEquals("", st[0].methodName)
        assertNull(st[0].fileName)
        assertEquals(-1, st[0].lineNumber)

        // real frames follow; only the log.C / applog / Recover plumbing is filtered
        assertEquals(
            "github.com/celzero/firestack/intra/log.(*simpleLogger).Stack",
            st[1].className + "." + st[1].methodName
        )
        assertEquals("gvisor.dev/gvisor/pkg/tcpip/header.ICMPv4.Type", st[2].className + "." + st[2].methodName)
        assertEquals(
            "github.com/celzero/firestack/intra/netstack.(*icmpForwarder).reply4",
            st[3].className + "." + st[3].methodName
        )
        assertEquals(4, st.size)
    }

    @Test
    fun `toThrowable without createdBy keeps message and plain frames`() {
        val raw = """
            panic: send on closed channel

            goroutine 520 [running]:
            github.com/celzero/firestack/intra/ipn.(*wgtun).WriteNotify(0x0)
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/ipn/wgproxy.go:1197 +0x1
        """.trimIndent()

        val parsed = ExceptionParser.parse(raw)
        val t = parsed.toThrowable(context = "GoCrash gocrash_2.txt")
        val st = t.stackTrace

        assertEquals("go-panic: send on closed channel", t.message)
        assertEquals(1, st.size)
        assertEquals("github.com/celzero/firestack/intra/ipn.(*wgtun)", st[0].className)
        assertEquals("WriteNotify", st[0].methodName)
    }

    @Test
    fun `parses newer runtime goroutine headers with gp m mp fields`() {
        val raw = """
            panic: runtime error: index out of range [10] with length 0

            goroutine 298 gp=0x6f4a33da40 m=27 mp=0x6f4a314008 [running]:
            panic({0x6f56c55940?, 0x6f4a1caed0?})
            	runtime/panic.go:878 +0x150 fp=0x6f4a3f0e30 sp=0x6f4a3f0d80 pc=0x6f56406c60
            github.com/celzero/firestack/intra.Crash.func1()
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/tun2socks.go:423 +0x110 fp=0x6f4a3f0fd0 sp=0x6f4a3f0f30 pc=0x6f569c6e30
            created by github.com/celzero/firestack/intra.Crash in goroutine 102
            	github.com/celzero/firestack@v0.0.0-00010101000000-000000000000/intra/tun2socks.go:412 +0x50

            goroutine 17 gp=0x6f4a072780 m=1 mp=0x6f4a0c6008 [syscall, locked to thread]:
            runtime.goexit({})
            	runtime/asm_arm64.s:1039 +0x4 fp=0x6f4a26bfe0 sp=0x6f4a26bfe0 pc=0x6f5640e6a4
        """.trimIndent() + "\n"

        val parsed = ExceptionParser.parse(raw)

        assertEquals(ExceptionParser.TraceType.GO, parsed.type)
        assertEquals("runtime error: index out of range [10] with length 0", parsed.message)
        assertEquals(3, parsed.frames.size)
        assertEquals("panic", parsed.frames[0].function)
        assertEquals("github.com/celzero/firestack/intra.Crash.func1", parsed.frames[1].function)
        // second goroutine's frames are included
        assertEquals("runtime.goexit", parsed.frames[2].function)
        assertEquals(
            "celzero/firestack@v0.0.0-00010101000000-000000000000/intra/tun2socks.go:412",
            parsed.createdBy
        )
    }
}
