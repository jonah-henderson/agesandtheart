package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A deep sea on vanilla's rock: vanilla pours its own sea at the level its settings say, so a book's depth has to
 * move that level, and the abyss is laid off where it then stands.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class VanillaDeepSeaOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(command: String) = server.run("execute in agesandtheart:vanilladeepsea run $command")

    /** How many of [block] fill the box, counted by turning them into stone. */
    fun countOf(block: String, fromY: Int, toY: Int, x: Int, z: Int): Int {
        val said = inAge("fill $x $fromY $z ${x + SIDE} $toY ${z + SIDE} minecraft:stone replace $block")
        return Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
    }

    test("a deep sea over vanilla's rock stands far above vanilla's level, with an abyss under it") {
        server.run("age write vanilladeepsea 7 age deep sea")
        val found = inAge("locate biome #minecraft:is_deep_ocean")
        val (x, z) = Regex("""\[(-?\d+), [^,]+, (-?\d+)]""").find(found)?.destructured ?: error("no deep ocean: $found")
        val atX = x.toInt()
        val atZ = z.toInt()
        // A forceload returns once its chunks are generated, decorated and settled.
        inAge("forceload add ${atX - SIDE} ${atZ - SIDE} ${atX + SIDE} ${atZ + SIDE}")
        val highWater = countOf("minecraft:water", HIGH_WATER_FROM, HIGH_WATER_TO, atX, atZ)
        check(highWater > 0) { "no water between y=$HIGH_WATER_FROM and $HIGH_WATER_TO over the deep ocean at $atX, $atZ" }
        val abyss = countOf("agesandtheart:deep_water", ABYSS_FROM, ABYSS_TO, atX, atZ)
        check(abyss > 0) { "no deep water under the deep ocean at $atX, $atZ" }
    }
})

/** Vanilla's sea stands to y=62; the least a `deep` sea moves it is some fifty-nine blocks. */
private const val HIGH_WATER_FROM = 90
private const val HIGH_WATER_TO = 110
private const val ABYSS_FROM = -20
private const val ABYSS_TO = 40
private const val SIDE = 15
