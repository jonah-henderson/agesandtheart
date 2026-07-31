package co.voik.agesandtheart.age

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

/**
 * How an `/age` command answers — as prose to a person, or as one JSON document to whatever is reading it.
 *
 * **Prose is the default and is unchanged**: in game the commands say what they always said. Structured
 * output is asked for per invocation, by putting `json` after the command, and exists so the server checks
 * can *measure* rather than parse sentences. A test asserting on "248,734 block(s) differ" is really
 * asserting on the wording.
 *
 * **A structured answer is exactly one message**, sent by [finish]. That matters more than it looks: RCON
 * returns a command's whole output as one buffer with no separator between the messages that went into it
 * (`RconConsoleSource` appends to a `StringBuffer`), so several messages arrive run together. One message
 * means the buffer *is* the document.
 */
class Report private constructor(private val source: CommandSourceStack, private val document: JsonObject?) {

    /** Whether anything is listening for structure — for the rare caller that must skip work to build it. */
    val isStructured: Boolean get() = document != null

    /**
     * Say something to a person: a heading, a hint, an aside. **Silent under [structured]**, because a
     * machine has no use for "Travel with /age tp" and every line of it would have to be filtered back out.
     */
    fun say(line: () -> String) {
        if (document == null) source.sendSuccess({ Component.literal(line()) }, false)
    }

    /**
     * A fact, said both ways — as [line] to a person and under [key] to a machine. The prose is a lambda so
     * a structured run never pays to format a sentence nobody reads.
     */
    fun fact(key: String, value: Any?, line: () -> String) {
        if (document == null) {
            source.sendSuccess({ Component.literal(line()) }, false)
        } else {
            document.add(key, primitiveOf(value))
        }
    }

    /**
     * A line whose *styling* carries meaning — struck-through unread pages, and little else. Prose only,
     * like [say]: a structured reader gets the same information as a field, where it needs no colour.
     */
    fun styled(line: () -> Component) {
        if (document == null) source.sendSuccess(line, false)
    }

    /**
     * A fact with no sentence of its own, because the prose already said it inside another one.
     *
     * "248,734 block(s) differ across 284 of 289 chunks" is three numbers to a reader and one sentence to a
     * person; this is how the other two get out without inventing lines nobody wants to read.
     */
    fun only(key: String, value: Any?) {
        document?.add(key, primitiveOf(value))
    }

    /**
     * One of a repeated thing — an Age in a listing, a flaw in an instability, a body in a sky. Appends to
     * the array at [key], so the caller loops and this collects.
     */
    fun entry(key: String, fields: Map<String, Any?>, line: () -> String) {
        if (document == null) {
            source.sendSuccess({ Component.literal(line()) }, false)
            return
        }
        val array = document.getAsJsonArray(key) ?: JsonArray().also { document.add(key, it) }
        array.add(JsonObject().apply { fields.forEach { (name, value) -> add(name, primitiveOf(value)) } })
    }

    /**
     * The command failed, said both ways. Returns [AgeCommand.FAILURE] so a caller can `return report.fail(…)`.
     *
     * A structured failure is still a document rather than Brigadier's error channel: a reader that has to
     * tell "the command failed" from "the connection failed" cannot do it if one of them is not JSON.
     */
    fun fail(message: String): Int {
        if (document == null) {
            source.sendFailure(Component.literal(message))
        } else {
            document.addProperty("error", message)
            send()
        }
        return AgeCommand.FAILURE
    }

    /** Emits the document, if there is one. Every structured command must end here or say nothing at all. */
    fun finish() {
        if (document != null) send()
    }

    private fun send() {
        source.sendSuccess({ Component.literal(document.toString()) }, false)
    }

    private fun primitiveOf(value: Any?) = when (value) {
        null -> JsonPrimitive("")
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Iterable<*> -> JsonArray().apply { value.forEach { add(JsonPrimitive(it.toString())) } }
        else -> JsonPrimitive(value.toString())
    }

    companion object {
        /** What a writer says to be answered in JSON. */
        const val STRUCTURED_LITERAL = "json"

        fun prose(source: CommandSourceStack) = Report(source, document = null)

        fun structured(source: CommandSourceStack) = Report(source, JsonObject())
    }
}

/**
 * How a command gets the [Report] it answers through — prose or structured, decided by the branch of the
 * tree that was parsed rather than by anything the command has to ask.
 */
typealias ReportFor = (CommandContext<CommandSourceStack>) -> Report
