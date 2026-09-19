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

/**
 * Best-effort parser for crash/tombstone text captured on-device.
 *
 * Supports two trace dialects:
 *  1. Go runtime panics (gocrash_ files):
 * ```
 *     panic: runtime error: index out of range
 *
 *     goroutine 1 [running]:
 *     main.foo(...)
 *         /app/foo.go:42 +0x123
 * ```
 *  2. Java/Kotlin stack traces (kotlin_ files):
 * ```
 *     java.lang.NullPointerException: something went wrong
 *     	at com.example.Foo.bar(Foo.kt:123)
 * ```
 *
 * Parsing is intentionally forgiving: a malformed frame never fails the whole parse.
 * Anything that cannot be represented as a [StackTraceElement] (Caused by / Suppressed /
 * `... N more` lines, goroutine headers, timestamps, token lines, blank lines, corrupt
 * frames) is simply left out of [ParsedException.frames] and remains available in
 * [ParsedException.raw] so callers can ship it via Crashlytics `log()`.
 *
 * This object must stay free of Android dependencies so it is unit-testable on the JVM.
 */
object ExceptionParser {

    enum class TraceType { GO, JAVA, UNKNOWN }

    /** One stack frame; [function] is the fully-qualified `pkg.Class.method` / Go func name. */
    data class ParsedFrame(val function: String, val file: String?, val line: Int)

    data class ParsedException(
        val message: String,
        val frames: List<ParsedFrame>,
        /** Complete, unmodified file content; never truncated. */
        val raw: String,
        val type: TraceType,
    ) {
        /**
         * Builds a synthetic [Throwable] whose [Throwable.stackTrace] is the parsed frames
         * (not the reporting call-site). [context] (e.g. `[GoCrash] gocrash_123.txt`) is
         * prepended to the message so Crashlytics issue grouping can tell files apart.
         */
        fun toThrowable(context: String? = null): Throwable = ImportedException(this, context)
    }

    /**
     * Imported exception carrying the *captured* crash's stack trace. The reporter call-site
     * (EnhancedBugReport.sendFileToFirebase) is intentionally not part of the trace.
     */
    private class ImportedException(parsed: ParsedException, context: String?) :
        Exception(if (context.isNullOrBlank()) parsed.message else "[$context] ${parsed.message}") {

        init {
            stackTrace = parsed.frames
                .map { frame ->
                    StackTraceElement(
                        frame.function.substringBeforeLast(
                            '.',
                            missingDelimiterValue = frame.function,
                        ),
                        frame.function.substringAfterLast(
                            '.',
                            missingDelimiterValue = frame.function,
                        ),
                        frame.file,
                        frame.line,
                    )
                }
                .toTypedArray()
        }
    }

    // Go: function line, e.g. `main.foo(0xc000123)`, `f()`, or `main.(*T).method(...)`;
    // must end in (...). Greedy match keeps receiver parens inside the function name.
    private val GO_FUNC_LINE = Regex("""^(\S.*)\(.*\)\s*$""")
    // Go: indented source line following the function line, e.g. `/app/foo.go:42 +0x123`.
    private val GO_FILE_LINE = Regex("""^\s+(\S+\.(?:go|s)):(\d+)(?:\s.*)?$""")
    // JVM: `at pkg.Class.method(location)`; inner classes / lambdas covered by [\w$]+.
    private val JAVA_AT_LINE =
        Regex("""^\s*at\s+([\w$]+(?:\.[\w$]+)*)\.([\w$<>]+)\((.*)\)\s*$""")
    // JVM header: dotted exception class with optional `: message`.
    private val JAVA_HEADER = Regex("""^((?:[\w$<>]+\.)+[\w$<>]+)(?::\s?(.*))?$""")

    // JVM sentinel line numbers per java.lang.StackTraceElement semantics.
    private const val LINE_UNKNOWN = -1
    private const val LINE_NATIVE = -2

    /**
     * Parses [raw] into a [ParsedException]. Tries Go first (cheap `panic:` detection),
     * then JVM `at` frames. Returns an UNKNOWN result with empty frames when neither
     * dialect matches; callers should fall back to their previous reporting behaviour.
     */
    fun parse(raw: String): ParsedException {
        if (raw.isBlank()) {
            return ParsedException("unknown", emptyList(), raw, TraceType.UNKNOWN)
        }
        parseGo(raw)?.let { return it }
        parseJava(raw)?.let { return it }
        return ParsedException(
            firstNonBlankLine(raw)?.take(256) ?: "unknown",
            emptyList(),
            raw,
            TraceType.UNKNOWN,
        )
    }

    /**
     * Go panic parser. A panic is only claimed when a `panic:` line exists AND at least one
     * valid function/source frame pair follows, so unrelated text mentioning "panic:" is not
     * misdetected.
     */
    private fun parseGo(raw: String): ParsedException? {
        val lines = raw.lineSequence().toList()
        val panicLine = lines.firstOrNull { it.startsWith("panic:") } ?: return null

        val frames = ArrayList<ParsedFrame>()
        var i = 0
        while (i < lines.size - 1) {
            val func = GO_FUNC_LINE.matchEntire(lines[i])
            if (func != null) {
                val file = GO_FILE_LINE.matchEntire(lines[i + 1])
                if (file != null) {
                    frames.add(
                        ParsedFrame(
                            function = func.groupValues[1].trim(),
                            file = file.groupValues[1],
                            line = file.groupValues[2].toIntOrNull() ?: LINE_UNKNOWN,
                        )
                    )
                    i += 2
                    continue
                }
            }
            i++
        }
        // A `panic:` without any parsable frames is not a confidently-parsed Go trace.
        if (frames.isEmpty()) return null

        val message = panicLine.removePrefix("panic:").trim().ifBlank { "panic" }
        return ParsedException(message, frames, raw, TraceType.GO)
    }

    /**
     * JVM/Kotlin stack trace parser. Requires at least one valid `at` frame. The first
     * parseable exception header becomes the message; `Caused by:` / `Suppressed:` headers
     * are NOT parsed into frames — they stay in raw for Crashlytics `log()`.
     */
    private fun parseJava(raw: String): ParsedException? {
        var message: String? = null
        val frames = ArrayList<ParsedFrame>()

        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue

            val at = JAVA_AT_LINE.matchEntire(line)
            if (at != null) {
                val (qualifiedClass, method, location) = at.destructured
                val (file, lineNo) = parseLocation(location)
                frames.add(ParsedFrame("$qualifiedClass.$method", file, lineNo))
                continue
            }

            // Header detection: only the first plausible header is used as the message.
            // Non-frame, non-header lines (timestamps, `Token:`, `Caused by:`, `... N more`)
            // are ignored here and preserved in raw.
            if (message == null) {
                val header = JAVA_HEADER.matchEntire(line.trim())
                if (header != null) {
                    val cls = header.groupValues[1]
                    val msg = header.groupValues[2]
                    message = if (msg.isBlank()) cls else "$cls: $msg"
                }
            }
        }
        if (frames.isEmpty()) return null

        return ParsedException(message ?: "unknown", frames, raw, TraceType.JAVA)
    }

    /**
     * Parses the parenthesised JVM frame location into (file, line).
     * Handles `File.kt:123`, `File.kt`, `Native Method` and `Unknown Source`.
     */
    private fun parseLocation(location: String): Pair<String?, Int> {
        val loc = location.trim()
        if (loc.equals("Native Method", ignoreCase = true)) return null to LINE_NATIVE
        if (loc.equals("Unknown Source", ignoreCase = true)) return null to LINE_UNKNOWN

        val colon = loc.lastIndexOf(':')
        if (colon > 0) {
            val file = loc.substring(0, colon)
            val line = loc.substring(colon + 1).trim().toIntOrNull()
            if (line != null) return file to line
        }
        return loc.ifBlank { null } to LINE_UNKNOWN
    }

    private fun firstNonBlankLine(raw: String): String? =
        raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
}
