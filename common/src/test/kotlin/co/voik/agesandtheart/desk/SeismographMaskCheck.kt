package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.Manifestation
import io.kotest.core.spec.style.FunSpec

/**
 * What the seismograph says was bought reaches its screen as it was. A data slot crosses the wire as a short, so the
 * mask goes in pieces, and a piece that touched a short's sign bit would come back as every manifestation after it.
 */
class SeismographMaskCheck : FunSpec({

    /** [mask] as the client hears it: each piece sent as vanilla sends a data slot, and joined again. */
    fun overTheWire(mask: Int): Int = (0..<SeismographMenu.BOUGHT_SLOTS).fold(0) { heard, slot ->
        val sent = SeismographMenu.piece(mask, slot).toShort().toInt()
        heard or SeismographMenu.joined(sent, slot)
    }

    test("the slots hold a bit for every manifestation") {
        val bits = SeismographMenu.BOUGHT_SLOTS * SeismographMenu.BITS_A_SLOT
        check(Manifestation.entries.size <= bits) { "${Manifestation.entries.size} manifestations, room for $bits" }
    }

    test("each manifestation arrives alone, and all of them together") {
        for (manifestation in Manifestation.entries) {
            val mask = 1 shl manifestation.ordinal
            check(overTheWire(mask) == mask) { "$manifestation arrived as ${Integer.toBinaryString(overTheWire(mask))}" }
        }
        val all = Manifestation.entries.fold(0) { mask, it -> mask or (1 shl it.ordinal) }
        check(overTheWire(all) == all) { "every manifestation arrived as ${Integer.toBinaryString(overTheWire(all))}" }
    }
})
