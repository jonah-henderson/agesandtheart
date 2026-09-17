package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Phenomenon
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * That the shipped tuning reads, and that reading it changes nothing.
 *
 * A datapack file is worse than a constant in one specific way: a constant cannot fail to load. If
 * `tempest.json` stops parsing, every field falls back to its default and the storm carries on looking
 * exactly right — so nothing in play would notice, and the pack would be inert rather than broken.
 */
@Tags(NEEDS_REGISTRIES)
class PhenomenonBehaviourCheck : FunSpec({

    val shipped = Path.of("src/main/resources/data/agesandtheart/art/phenomenon/tempest.json")

    test("the shipped tempest reads") {
        val read = PhenomenonBehaviour.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString(shipped.readText()))
            .getOrThrow { problem -> IllegalStateException("the shipped tempest would not read: $problem") }
        check(read == PhenomenonBehaviour.ORDINARY) {
            "the file and the defaults disagree, so one of them is not what was walked: $read"
        }
    }

    /**
     * **Every field is optional and every default is the walked number**, so a pack overriding one thing
     * does not silently reset the rest — the trap an all-or-nothing record would set for its first author.
     */
    test("a file that says one thing leaves the rest alone") {
        val partial = PhenomenonBehaviour.CODEC
            .parse(JsonOps.INSTANCE, JsonParser.parseString("""{"fire_attempts": 0}"""))
            .getOrThrow { problem -> IllegalStateException("a partial file would not read: $problem") }
        check(partial.fireAttempts == 0) { "the one field written did not land: $partial" }
        check(partial.rolls == PhenomenonBehaviour.ORDINARY.rolls) { "writing one field moved another: $partial" }
        check(partial.blast == PhenomenonBehaviour.ORDINARY.blast) { "writing one field moved another: $partial" }
    }

    /** Zero attempts is how a pack asks for a storm that only cracks, so it has to be reachable. */
    test("no fire is a thing a pack can ask for") {
        val quiet = PhenomenonBehaviour(fireAttempts = 0)
        check(quiet.fireAttempts == 0) { "a storm that only cracks cannot be written" }
    }

    /** And the file is named for the phenomenon it tunes, or nothing ever finds it. */
    test("the file is named for its phenomenon") {
        check(shipped.fileName.toString() == "${Phenomenon.TEMPEST.key}.json") {
            "the tuning is filed under ${shipped.fileName} and looked up by '${Phenomenon.TEMPEST.key}'"
        }
    }
})
