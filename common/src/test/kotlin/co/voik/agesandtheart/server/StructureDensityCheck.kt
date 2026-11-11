package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Writing an Age that asks for structures at anything but the ordinary rate.
 *
 * **This exists because of one book.** `foreboding age minecraft:packed_ice landmass frozen climate`
 * turned up in a stronghold library, and linking to it killed the server with a `StackOverflowError` —
 * rescaling a structure set's spacing round-trips the placement through its codec, and a placement can
 * carry an **exclusion zone**, which holds a `Holder<StructureSet>`, which holds a placement. Vanilla's sets
 * exclude one another in a ring, so the walk never ended.
 *
 * The fault was the *ops*: `RegistryFileCodec` writes a holder as an **id** only when the ops can show it a
 * registry, and inlines the value otherwise. A plain `JsonOps` therefore walks the whole ring by value.
 *
 * **Which is why this is a server check and not a unit one.** The bug needs real registries with vanilla's
 * real exclusion graph in them; nothing hand-built would have had the ring.
 */
@Tags(NEEDS_SERVER)
class StructureDensityCheck : FunSpec({

    val server = DrivenServer.shared

    /** The book itself, verbatim, so the thing that broke is the thing that is guarded. */
    test("the book that crashed the server writes an Age") {
        val said = server.run("age write bensoom 1 foreboding age minecraft:packed_ice landmass frozen climate")
        check(said.contains("Created Age")) { "the Age was not written: $said" }
    }

    /**
     * And the general case behind it, since the book only found this by accident: **any** rung on a
     * structure population rescales a placement, and it is the rescale that walks the ring.
     */
    test("structures can be asked for at any rate") {
        for (rung in listOf("teeming", "plentiful", "few", "scarce")) {
            val said = server.run("age write spaced$rung 3 $rung villages structures age")
            check(said.contains("Created Age")) { "'$rung villages' was not written: $said" }
        }
    }

    /**
     * Set apart or huddled, a set's grid and separation are both rewritten through the codec, and vanilla must take
     * the pair back: a village is still found in each.
     */
    test("structures set apart or huddled are still built") {
        for (spacing in listOf("sparse", "clustered")) {
            val written = server.run("age write apart$spacing 3 $spacing villages age")
            check(written.contains("Created Age")) { "'$spacing villages' was not written: $written" }
            val found = server.run("execute in agesandtheart:apart$spacing run locate structure #minecraft:village")
            check(found.contains("nearest", ignoreCase = true)) { "no village in '$spacing villages': $found" }
        }
    }

    /** Every Age has its star fissures however its structures were written, and only `no star_fissure` seals it. */
    test("only no star_fissure seals an Age") {
        for ((name, sentence) in listOf("fissuresopen" to "no structures age", "fissuresonly" to "only villages age")) {
            server.run("age write $name 3 $sentence")
            val found = server.run("execute in agesandtheart:$name run locate structure agesandtheart:star_fissure")
            check(found.contains("nearest", ignoreCase = true)) { "'$sentence' closed the fissures: $found" }
        }
        server.run("age write fissuressealed 3 no star_fissure age")
        val sealed = server.run("execute in agesandtheart:fissuressealed run locate structure agesandtheart:star_fissure")
        check(!sealed.contains("nearest", ignoreCase = true)) { "'no star_fissure' left a fissure: $sealed" }
    }

    /** Sprawling or shrunk, a village's pieces are still laid ([co.voik.agesandtheart.generation.JigsawSizes]). */
    test("villages sized colossal or minuscule are still built") {
        for (size in listOf("colossal", "minuscule")) {
            val written = server.run("age write sized$size 5 $size villages plains age")
            check(written.contains("Created Age")) { "'$size villages' was not written: $written" }
            val found = server.run("execute in agesandtheart:sized$size run locate structure #minecraft:village")
            check(found.contains("nearest", ignoreCase = true)) { "no village in '$size villages': $found" }
        }
    }

    /** Buried, or lost — buried and abandoned — a village is still laid ([co.voik.agesandtheart.generation.StructureHeights]). */
    test("buried and lost villages are still built") {
        for (fate in listOf("buried", "lost")) {
            val written = server.run("age write sunk$fate 5 $fate villages plains age")
            check(written.contains("structures.height=")) { "'$fate villages' set no height: $written" }
            val found = server.run("execute in agesandtheart:sunk$fate run locate structure #minecraft:village")
            check(found.contains("nearest", ignoreCase = true)) { "no village in '$fate villages': $found" }
        }
    }

    /** Eroded or ashen, a village is still laid ([co.voik.agesandtheart.generation.StructureWeathering]). */
    test("eroded and ashen villages are still built") {
        for (weather in listOf("eroded", "ashen")) {
            val written = server.run("age write weathered$weather 5 $weather villages plains age")
            check(written.contains("Created Age")) { "'$weather villages' was not written: $written" }
            val found = server.run("execute in agesandtheart:weathered$weather run locate structure #minecraft:village")
            check(found.contains("nearest", ignoreCase = true)) { "no village in '$weather villages': $found" }
        }
    }
})
