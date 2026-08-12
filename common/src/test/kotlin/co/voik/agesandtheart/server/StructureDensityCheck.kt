package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Writing an Age that asks for structures at anything but the ordinary rate (design §3.1).
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
        for (rung in listOf("teeming", "plentiful", "scarce")) {
            val said = server.run("age write spaced$rung 3 $rung villages structures age")
            check(said.contains("Created Age")) { "'$rung villages' was not written: $said" }
        }
    }
})
