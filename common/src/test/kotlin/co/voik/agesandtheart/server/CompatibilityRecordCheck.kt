package co.voik.agesandtheart.server

import co.voik.agesandtheart.BuildMatch
import co.voik.agesandtheart.Release
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * That content players and servers must agree on only changes in a breaking release.
 *
 * `compatibility.record` holds every id of ours in a built-in registry on a running server (`/age
 * registered`), every payload, and the generator version, with
 * the compatibility line that content requires. A change fails here until it is re-recorded, which marks
 * the next release as owing a breaking bump; a release build on an older line than the record then fails.
 * A codec whose shape changed under the same id is invisible to this.
 */
@Tags(NEEDS_SERVER)
class CompatibilityRecordCheck : FunSpec({
    val server = DrivenServer.shared

    test("registered content changes only in a breaking release") {
        val build = BuildMatch.BUILD
        val currentLine = requireNotNull(Release.of(build)) { "build '$build' carries no release" }.compatibilityLine
        val registered = server.ask("registered")
        val current = CompatibilityRecord(
            line = currentLine,
            generatorVersion = registered["generator"].asInt,
            content = registered["content"].asJsonArray.map { it.asString }.toSet(),
        )
        val recorded = CompatibilityRecord.read(RECORD_FILE)
        val nothingReleasedYet = ".untagged." in build

        if (System.getProperty(RECORDING_PROPERTY).toBoolean()) {
            current.copy(line = lineRequiredBy(current, recorded, nothingReleasedYet)).write(RECORD_FILE)
            return@test
        }

        checkNotNull(recorded) { "no ${RECORD_FILE.name} yet. $HOW_TO_RECORD" }
        // Not `check`: Power-Assert would print every entry of both records under the message.
        if (!current.sameContentAs(recorded)) {
            error(
                "${current.differencesFrom(recorded)}\n" +
                    "Players and servers must agree on these, so the next release must break compatibility " +
                    "(at least ${formatLine(lineRequiredBy(current, recorded, nothingReleasedYet))}). $HOW_TO_RECORD",
            )
        }

        val buildIsAReleaseTag = build.substringAfter('+').all { it.isDigit() || it == '.' }
        val releaseIsOnAnOlderLine = compareLines(currentLine, recorded.line) < 0
        check(!(buildIsAReleaseTag && releaseIsOnAnOlderLine)) {
            "$build is a release, but its content needs a breaking release of at least ${formatLine(recorded.line)}"
        }
    }
})

private val RECORD_FILE = File("compatibility.record")
private const val RECORDING_PROPERTY = "agesandtheart.recordCompatibility"
private const val HOW_TO_RECORD =
    "Re-record with ./gradlew :common:serverTest -Pfast -Pon=compatibility -PrecordCompatibility"

private typealias Line = Pair<Int, Int>

private fun compareLines(first: Line, second: Line) = compareValuesBy(first, second, Line::first, Line::second)

private fun nextBreakingLine(line: Line): Line = if (line.first == 0) 0 to line.second + 1 else line.first + 1 to 0

private fun formatLine(line: Line) = if (line.first == 0) "0.${line.second}" else "${line.first}"

private fun parseLine(text: String): Line {
    val parts = text.split('.').map(String::toInt)
    return parts[0] to parts.getOrElse(1) { 0 }
}

/**
 * The line to record [current] at: unchanged content keeps the recorded line, and changed content needs the
 * next breaking line past the current build, or the line already owed if that is further. Before the first
 * release tag nobody has a build to be incompatible with, so the current line stands.
 */
private fun lineRequiredBy(
    current: CompatibilityRecord,
    recorded: CompatibilityRecord?,
    nothingReleasedYet: Boolean,
): Line {
    if (recorded == null || nothingReleasedYet) return current.line
    if (current.sameContentAs(recorded)) return recorded.line
    val nextBreaking = nextBreakingLine(current.line)
    return if (compareLines(recorded.line, nextBreaking) > 0) recorded.line else nextBreaking
}

private data class CompatibilityRecord(val line: Line, val generatorVersion: Int, val content: Set<String>) {
    fun sameContentAs(other: CompatibilityRecord) =
        generatorVersion == other.generatorVersion && content == other.content

    fun differencesFrom(recorded: CompatibilityRecord): String = buildList {
        if (generatorVersion != recorded.generatorVersion) {
            add("generator version ${recorded.generatorVersion} → $generatorVersion")
        }
        (content - recorded.content).sorted().forEach { add("added $it") }
        (recorded.content - content).sorted().forEach { add("removed $it") }
    }.joinToString("\n")

    fun write(file: File) {
        val header = listOf(
            "# What players' and servers' builds must agree on, and the compatibility line that requires it.",
            "# Written by CompatibilityRecordCheck; don't edit by hand.",
            "line=${formatLine(line)}",
            "generator=$generatorVersion",
        )
        file.writeText((header + content.sorted()).joinToString("\n", postfix = "\n"))
    }

    companion object {
        fun read(file: File): CompatibilityRecord? {
            if (!file.exists()) return null
            val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            fun setting(name: String) = lines.first { it.startsWith("$name=") }.substringAfter('=')
            val content = lines.filterNot { '=' in it }.toSet()
            return CompatibilityRecord(parseLine(setting("line")), setting("generator").toInt(), content)
        }
    }
}
