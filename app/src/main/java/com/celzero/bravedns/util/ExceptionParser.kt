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
 * Supports three trace dialects:
 *  1. Go runtime panics (plain dump):
 * ```
 *     panic: runtime error: index out of range
 *
 *     goroutine 1 [running]:
 *     main.foo(...)
 *         /app/foo.go:42 +0x123
 * ```
 *  2. Firestack crash dumps (Console.log envelope or setCrashOutput file):
 * ```
 *     tun: debugging... not a crash [0] runtime error: ... [gobind@v...]
 *      (#0)goroutine 1360 [running]:
 *     ...
 *     created by github.com/celzero/firestack/intra.Crash in goroutine 328
 *     	github.com/celzero/firestack@v.../intra/tun2socks.go:412 +0x50
 * ```
 *  3. Java/Kotlin stack traces (kotlin_ files):
 * ```
 *     java.lang.NullPointerException: something went wrong
 *     	at com.example.Foo.bar(Foo.kt:123)
 * ```
 *
 * Go dumps are huge (every live goroutine). Only the panicking goroutine's frames are
 * extracted; later goroutine blocks and the firestack logging wrappers that prefix them
 * are dropped. Leading firestack level chars (`F `, `E `, ...) are stripped so
 * level-prefixed log lines (old golog_ files) parse too.
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

    /**
     * Separator used by the firestack crash envelope: `<title> <===> <goroutine dump>`.
     * Shared with CrashReporter, which splits the same content before parsing.
     */
    const val GO_CRASH_SPLITTER = "<===>"

    /** One stack frame; [function] is the fully-qualified `pkg.Class.method` / Go func name. */
    data class ParsedFrame(val function: String, val file: String?, val line: Int)

    data class ParsedException(
        /**
         * Crash headline: firestack envelope title line, the text after `panic:`, or the
         * fallback title (text before the first goroutine header).
         */
        val message: String,
        /** Real frames of the panicking goroutine (logging wrappers skipped). */
        val frames: List<ParsedFrame>,
        /** Complete, unmodified file content; never truncated. */
        val raw: String,
        val type: TraceType,
        /**
         * Source location of the goroutine creator (the line after `created by`), e.g.
         * `celzero/firestack@v.../intra/tun2socks.go:412`, or null.
         */
        val createdBy: String? = null,
    ) {
        /**
         * Builds a synthetic [Throwable] whose [Throwable.stackTrace] is the parsed frames
         * (not the reporting call-site). [context] (e.g. `[GoCrash] gocrash_123.txt`) is
         * prepended to the message so Crashlytics issue grouping can tell files apart.
         *
         * Crashlytics derives the issue title from the top frame and the subtitle from
         * "class: message". For Go traces with a [ParsedException.createdBy], a synthetic
         * top frame carries the crash headline (issue title) and the message becomes the
         * created-by location (subtitle).
         */
        fun toThrowable(context: String? = null): Throwable =
            if (type == TraceType.GO) {
                GoCrashException(this, context)
            } else {
                JvmCrashException(this, context)
            }
    }

    // Go: function line, e.g. `main.foo(0xc000123)`, `f()`, or `main.(*T).method(...)`;
    // must end in (...). Greedy match keeps receiver parens inside the function name.
    private val GO_FUNC_LINE = Regex("""^(\S.*)\(.*\)\s*$""")
    // Go: indented source line following the function line, e.g. `/app/foo.go:42 +0x123`.
    private val GO_FILE_LINE = Regex("""^\s+(\S+\.(?:go|s)):(\d+)(?:\s.*)?$""")
    // Go: goroutine header, e.g. `goroutine 520 [running]:` or the newer runtime format
    // `goroutine 298 gp=0x... m=27 mp=0x... [running]:`.
    private val GO_GOROUTINE_LINE = Regex("""^goroutine\s+\d+.*\[[^\]]*]:\s*$""")
    // Go: goroutine header anywhere in the text (the firestack dump may omit the newline
    // after the crash headline: `...(#0)goroutine 1360 [running]:`).
    private val GO_GOROUTINE_ANYWHERE = Regex("""goroutine\s+\d+.*?\[""")
    // Go: `created by <pkg>.<func> in goroutine <n>`; only the following location is kept.
    private val GO_CREATED_BY_LINE = Regex("""^created by\s+(\S.*)$""")
    // Go: standalone ` (#0)` line of the firestack crash envelope.
    private val GO_CRASH_INDEX_LINE = Regex("""^\s*\(#\d+\)\s*$""")
    // Go: source location attached to a `created by` line, e.g. `tun2socks.go:412 +0x50`.
    private val GO_CREATED_BY_FILE_LINE = Regex("""^\s*(\S+\.(?:go|s)):(\d+)(?:\s.*)?$""")
    // Go: leading firestack log-level char written by the go logger (`F `, `F\t`, ...).
    private val GO_LEVEL_PREFIX = Regex("""^[YVVDIWEFU][ \t]""")
    // JVM: `at pkg.Class.method(location)`; inner classes / lambdas covered by [\w$]+.
    private val JAVA_AT_LINE =
        Regex("""^\s*at\s+([\w$]+(?:\.[\w$]+)*)\.([\w$<>]+)\((.*)\)\s*$""")
    // JVM header: dotted exception class with optional `: message`.
    private val JAVA_HEADER = Regex("""^((?:[\w$<>]+\.)+[\w$<>]+)(?::\s?(.*))?$""")

    // Crashlytics issue title comes from the top frame; keep the synthetic headline frame short.
    private const val MAX_SYNTHETIC_TITLE_LENGTH = 256

    // Goroutine blocks included in the parsed stack: the panicking one plus the next.
    private const val MAX_GOROUTINE_BLOCKS = 2

    // JVM sentinel line numbers per java.lang.StackTraceElement semantics.
    private const val LINE_UNKNOWN = -1
    private const val LINE_NATIVE = -2

    /**
     * Parses [raw] into a [ParsedException]. Tries Go first (envelope / `panic:` detection),
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
     * Go parser. Claims a trace when the firestack crash envelope (`<===>`), a `panic:`
     * line, or a goroutine dump is present with at least one valid function/source frame
     * pair, so unrelated text is not misdetected.
     */
    private fun parseGo(raw: String): ParsedException? {
        val hasEnvelope = raw.contains(GO_CRASH_SPLITTER)
        val head: String
        val dump: String
        if (hasEnvelope) {
            head = raw.substringBefore(GO_CRASH_SPLITTER)
            dump = raw.substringAfter(GO_CRASH_SPLITTER)
        } else {
            // Go-direct crash files: `<headline> (#0)goroutine 1360 [running]:...` — the
            // dump starts at the first goroutine header, the headline precedes it (the
            // newline between headline and header may be missing).
            val match = GO_GOROUTINE_ANYWHERE.find(raw)
            head = match?.let { raw.substring(0, it.range.first) } ?: raw
            dump = match?.let { raw.substring(it.range.first) } ?: ""
        }
        val parsedDump = parseGoDump(dump) ?: return null
        // headline precedence: envelope title > `panic:` text > fallback title
        val message = (if (hasEnvelope) headlineFrom(head) else null)
            ?: panicMessageFrom(raw)
            ?: (if (!hasEnvelope) headlineFrom(head) else null)
            ?: return null
        return ParsedException(message, parsedDump.frames, raw, TraceType.GO, parsedDump.createdBy)
    }

    /** Text after the first `panic:` line, or null when the dump has none. */
    private fun panicMessageFrom(raw: String): String? =
        raw.lineSequence()
            .map(::stripGoLevelPrefix)
            .firstOrNull { it.startsWith("panic:") }
            ?.removePrefix("panic:")
            ?.trim()
            ?.ifBlank { "panic" }

    /**
     * Picks the headline out of a crash-head text block: the last non-blank line, skipping
     * a standalone ` (#N)` line and trailing `(#[dem])` suffixes; firestack level prefixes
     * are stripped. Null when nothing usable remains.
     */
    private fun headlineFrom(head: String): String? {
        val lines = head.lineSequence()
            .map(::stripGoLevelPrefix)
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null
        var title = lines.last()
        if (GO_CRASH_INDEX_LINE.matchEntire(title) != null) {
            title = lines.dropLast(1).lastOrNull() ?: return null
        }
        return title.trim()
            .replace(Regex("""\s*\(#\d+\)\s*$"""), "")
            .take(MAX_SYNTHETIC_TITLE_LENGTH)
            .ifBlank { null }
    }

    /**
     * Parses one Go dump. Frames are taken from the first two goroutine blocks — the
     * panicking one and the next relevant one; the third `goroutine` header stops
     * collection. Nothing is filtered. The source location following the first `created by`
     * line becomes [GoDump.createdBy]. Returns null when no frame pair is found.
     */
    private fun parseGoDump(dump: String): GoDump? {
        val lines = dump.lineSequence().map(::stripGoLevelPrefix).toList()
        val frames = ArrayList<ParsedFrame>()
        var createdBy: String? = null
        var inGoroutine = false
        var goroutineCount = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (GO_GOROUTINE_LINE.matchEntire(line.trim()) != null) {
                goroutineCount++
                if (goroutineCount > MAX_GOROUTINE_BLOCKS) break
                inGoroutine = true
                i++
                continue
            }
            if (inGoroutine) {
                if (createdBy == null && GO_CREATED_BY_LINE.matchEntire(line.trim()) != null) {
                    createdBy = lines.getOrNull(i + 1)
                        ?.let { GO_CREATED_BY_FILE_LINE.matchEntire(it) }
                        ?.let { shortenPkg("${it.groupValues[1]}:${it.groupValues[2]}") }
                    if (createdBy != null) i++
                    i++
                    continue
                }
                val func = GO_FUNC_LINE.matchEntire(line)
                if (func != null && i + 1 < lines.size) {
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
            }
            i++
        }
        if (frames.isEmpty()) return null
        return GoDump(frames, createdBy)
    }

    /** Drops a leading firestack log-level prefix (`F `, `F\t`, ...) if present. */
    private fun stripGoLevelPrefix(line: String): String =
        if (line.length > 2) GO_LEVEL_PREFIX.replaceFirst(line, "") else line

    /** Shortens well-known package prefixes so subtitles stay readable. */
    private fun shortenPkg(origin: String): String =
        origin.replaceFirst("github.com/celzero", "celzero")

    private data class GoDump(
        val frames: List<ParsedFrame>,
        val createdBy: String?,
    )

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

/**
 * Base for synthetic exceptions carrying the *captured* crash's stack trace. The reporter
 * call-site (EnhancedBugReport.sendFileToFirebase) is intentionally not part of the trace.
 * Concrete subclasses are named for the trace dialect so Crashlytics renders a meaningful
 * class name in the issue subtitle.
 */
internal open class CapturedCrashException(parsed: ExceptionParser.ParsedException, context: String?) :
    Exception(
        buildString {
            if (parsed.type == ExceptionParser.TraceType.GO) {
                if (!parsed.createdBy.isNullOrBlank()) {
                    // subtitle: the location line after `created by`
                    append(parsed.createdBy)
                } else {
                    append(GO_PANIC_TITLE_PREFIX)
                    append(parsed.message)
                }
            } else {
                context?.takeIf { it.isNotBlank() }?.let { append("[$it] ") }
                append(parsed.message)
            }
        }
    ) {

    private companion object {
        // Prefix on the synthetic title frame / message so Go panics are identifiable in
        // the Crashlytics issue title.
        const val GO_PANIC_TITLE_PREFIX = "go-panic: "

        // Crashlytics issue title comes from the top frame; keep the headline frame short.
        const val MAX_SYNTHETIC_TITLE_LENGTH = 256

        // java.lang.StackTraceElement sentinel for unknown line numbers
        const val LINE_UNKNOWN = -1
    }

    init {
        val hasCreatedBy =
            parsed.type == ExceptionParser.TraceType.GO && !parsed.createdBy.isNullOrBlank()
        val realFrames = parsed.frames.map(::toStackTraceElement)
        stackTrace = if (hasCreatedBy) {
            // synthetic top frame: the go-panic headline becomes the Crashlytics issue
            // title; empty methodName avoids the className.methodName concatenation
            // doubling dot-less headlines (e.g. `runtime error: ...`)
            val title = StackTraceElement(
                (GO_PANIC_TITLE_PREFIX + parsed.message).take(MAX_SYNTHETIC_TITLE_LENGTH),
                /* methodName = */ "",
                /* fileName = */ null,
                LINE_UNKNOWN,
            )
            (listOf(title) + realFrames).toTypedArray()
        } else {
            realFrames.toTypedArray()
        }
    }

    private fun toStackTraceElement(frame: ExceptionParser.ParsedFrame): StackTraceElement =
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

/** Synthetic exception for parsed Go panic/crash dumps. */
internal class GoCrashException(parsed: ExceptionParser.ParsedException, context: String?) :
    CapturedCrashException(parsed, context)

/** Synthetic exception for parsed Java/Kotlin tombstone traces. */
internal class JvmCrashException(parsed: ExceptionParser.ParsedException, context: String?) :
    CapturedCrashException(parsed, context)
