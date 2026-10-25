package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A warden in an Age stays past the minute vanilla's dig cooldown gives it (`Wardens`), and a shrieker's still goes
 * back into the ground. Two controls, since a warden that never tried to dig would pass: the overworld's goes, and
 * so does one in the same Age carrying the shrieker's tag.
 */
@Tags(NEEDS_SERVER)
class WardensCheck : FunSpec({

    val server = DrivenServer.shared

    fun inThe(dimension: String, command: String) = server.run("execute in $dimension run $command")

    fun isThere(dimension: String, type: String, tag: String) =
        inThe(dimension, "execute if entity @e[distance=0..,type=$type,tag=$tag]").startsWith("Test passed")

    /**
     * Whether a warden left alone in [dimension] is still there once its cooldown has long lapsed, carrying [tags].
     *
     * **Walled in, in the middle of the forced chunk**: a warden that strolls into the chunk beside it stops ticking
     * there, and a warden that never ticks stays.
     */
    fun wardenStaysIn(dimension: String, tags: List<String> = emptyList()): Boolean {
        inThe(dimension, "forceload add $CENTRE $CENTRE")
        server.waitUntil("the chunk a warden is summoned into loading in '$dimension'") {
            inThe(dimension, "execute if loaded $CENTRE $HEIGHT $CENTRE").startsWith("Test passed")
        }
        val (low, high) = CENTRE - BOX_HALF_WIDTH to CENTRE + BOX_HALF_WIDTH
        inThe(dimension, "fill $low ${HEIGHT - 1} $low $high ${HEIGHT + BOX_HEIGHT} $high minecraft:stone hollow")
        // Summoned without NBT, which would skip `finalizeSpawn` and so the cooldown this waits out, and tagged after.
        // A chunk's entities load after it, so a marker is waited on before the warden is looked for.
        inThe(dimension, "summon minecraft:marker $CENTRE $HEIGHT $CENTRE {Tags:[\"$MARKER\"]}")
        inThe(dimension, "summon minecraft:warden $CENTRE $HEIGHT $CENTRE")
        server.waitUntil("the entities beside the summoned warden loading in '$dimension'") {
            isThere(dimension, "minecraft:marker", MARKER)
        }
        for (tag in tags + SUMMONED) {
            inThe(dimension, "execute positioned $CENTRE $HEIGHT $CENTRE run tag @e[type=minecraft:warden,distance=..$NEAR] add $tag")
        }
        check(isThere(dimension, "minecraft:warden", SUMMONED)) { "no warden arrived in '$dimension'" }
        server.sprint(PAST_THE_COOLDOWN_TICKS)
        val stayed = isThere(dimension, "minecraft:warden", SUMMONED)
        inThe(dimension, "kill @e[distance=0..,tag=$SUMMONED]")
        inThe(dimension, "kill @e[distance=0..,tag=$MARKER]")
        inThe(dimension, "forceload remove all")
        return stayed
    }

    test("a warden in an Age does not go back into the ground, and a shrieker's does") {
        val said = server.run("age write $AGE $SEED age clear sky")
        check(said.contains("Created Age")) { "the Age was not written: $said" }
        check(!wardenStaysIn("minecraft:overworld")) { "the overworld's warden stayed too, so this shows nothing" }
        check(!wardenStaysIn("agesandtheart:$AGE", tags = listOf(SHRIEKERS))) { "a shrieker's warden stayed in the Age" }
        check(wardenStaysIn("agesandtheart:$AGE")) { "the Age's warden went back into the ground" }
    }
})

private const val AGE = "wardenstay"
private const val SEED = 51L

/** The middle of a chunk far from the origin, and high enough to be open air in either level. */
private const val CENTRE = 6008
private const val HEIGHT = 200

/** A stone box the warden cannot leave its chunk from, tall enough for it to stand in. */
private const val BOX_HALF_WIDTH = 3
private const val BOX_HEIGHT = 4

/** Vanilla's 1,200-tick cooldown and the digging after it, with room to spare. */
private const val PAST_THE_COOLDOWN_TICKS = 2000

/** `Wardens`' own tag for a warden a shrieker called. */
private const val SHRIEKERS = "agesandtheart.summoned_by_shrieker"

private const val SUMMONED = "wardens_check_summoned"
private const val MARKER = "wardens_check_marker"

/** How close to where it was summoned the warden is looked for, before it has moved. */
private const val NEAR = 3
