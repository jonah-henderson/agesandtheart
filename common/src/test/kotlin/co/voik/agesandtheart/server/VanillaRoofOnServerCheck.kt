package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A sealed sky over vanilla's terrain has a roof: our landforms build one into their own shape, and vanilla's
 * overworld has none of its own, so it was open air in endless dark.
 */
@Tags(NEEDS_SERVER)
class VanillaRoofOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("a sealed sky over vanilla's overworld terrain is shut with rock overhead") {
        val written = server.run("age write vanillaroofed 7 age lightless sky overworld landmass")
        check("Created Age" in written) { "the Age was not written: $written" }
        server.run("execute in agesandtheart:vanillaroofed run forceload add 0 0 15 15")
        val said = server.run("execute in agesandtheart:vanillaroofed run fill 0 $ROOF_FROM 0 15 $ROOF_TO 15 minecraft:glass replace #minecraft:base_stone_overworld")
        val roof = Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
        check(roof > 0) { "no rock between y=$ROOF_FROM and $ROOF_TO over a sealed sky: $said ($written)" }
    }
})

/** The vault hangs from the top of the world; this is the band just under it. */
private const val ROOF_FROM = 300
private const val ROOF_TO = 318
