package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * Colossal biomes over vanilla's terrain stay on the land they belong to: the land is stretched with them, as in
 * vanilla's large biomes, where stretching the biomes alone put ocean biomes on dry ground — and the aquifers read the
 * stretched land as well, where reading the old one drained the seas it did not know were there.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class VanillaLargeBiomesOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(command: String) = server.run("execute in agesandtheart:vanillacolossalbiomes run $command")
    fun holds(command: String) = inAge(command).startsWith("Test passed")

    test("the ocean biomes of a colossal-biomed Age on vanilla's terrain mostly have the sea in them") {
        val written = server.run("age write vanillacolossalbiomes 7 age colossal biomes overworld landmass")
        check("Created Age" in written) { "the Age was not written: $written" }
        var oceans = 0
        var wet = 0
        for (x in -SPAN..SPAN step STEP) for (z in -SPAN..SPAN step STEP) {
            inAge("forceload add $x $z")
            if (!holds("execute if biome $x $VANILLAS_WATER_SURFACE $z #minecraft:is_ocean")) continue
            oceans++
            // A frozen ocean's surface is ice, with the sea under it.
            val sea = holds("execute if block $x $VANILLAS_WATER_SURFACE $z minecraft:water")
            val frozen = holds("execute if block $x $VANILLAS_WATER_SURFACE $z #minecraft:ice")
            if (sea || frozen) wet++
            inAge("forceload remove $x $z")
        }
        check(oceans >= LEAST_OCEANS) { "only $oceans ocean columns were sampled, too few to say anything" }
        check(wet.toDouble() / oceans >= MOSTLY) { "$wet of $oceans ocean columns had the sea in them" }
    }
})

private const val VANILLAS_WATER_SURFACE = 62
private const val SPAN = 3000
private const val STEP = 500
private const val LEAST_OCEANS = 8
private const val MOSTLY = 0.8
