package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A deep sea on vanilla's rock: vanilla pours its own sea at the level its settings say, so a book's depth has to
 * move that level, and the abyss is laid off where it then stands — its deep water, its biome, and the whalefalls and
 * vents on its floor, and the hadalfish the vents keep.
 */
@Tags(NEEDS_SERVER, NEEDS_TIME)
class VanillaDeepSeaOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    fun inAge(age: String, command: String) = server.run("execute in agesandtheart:$age run $command")

    /** How many of [block] fill the box, counted by turning them into stone. */
    fun countOf(age: String, block: String, fromY: Int, toY: Int, x: Int, z: Int, side: Int = SIDE): Int {
        val said = inAge(age, "fill $x $fromY $z ${x + side} $toY ${z + side} minecraft:stone replace $block")
        return Regex("""filled (\d+)""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** How many biome cells in the box are [biome], counted by setting them to plains. */
    fun biomeCellsOf(age: String, biome: String, fromY: Int, toY: Int, x: Int, z: Int): Int {
        val said = inAge(age, "fillbiome $x $fromY $z ${x + SIDE} $toY ${z + SIDE} minecraft:plains replace $biome")
        return Regex("""(\d+) biome entr""").find(said)?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** A deep ocean's position in [age], with every chunk [reach] blocks around it generated. */
    fun deepOceanIn(age: String, reach: Int): Pair<Int, Int> {
        val found = inAge(age, "locate biome #minecraft:is_deep_ocean")
        val (x, z) = Regex("""\[(-?\d+), [^,]+, (-?\d+)]""").find(found)?.destructured ?: error("no deep ocean: $found")
        // A forceload returns once its chunks are generated, decorated and settled.
        inAge(age, "forceload add ${x.toInt() - reach} ${z.toInt() - reach} ${x.toInt() + reach} ${z.toInt() + reach}")
        return x.toInt() to z.toInt()
    }

    test("a deep sea over vanilla's rock stands far above vanilla's level, with an abyss under it") {
        server.run("age write vanilladeepsea 7 age deep sea")
        val (atX, atZ) = deepOceanIn("vanilladeepsea", SIDE)
        val highWater = countOf("vanilladeepsea", "minecraft:water", HIGH_WATER_FROM, HIGH_WATER_TO, atX, atZ)
        check(highWater > 0) { "no water between y=$HIGH_WATER_FROM and $HIGH_WATER_TO over the deep ocean at $atX, $atZ" }
        val abyss = countOf("vanilladeepsea", "agesandtheart:deep_water", ABYSS_FROM, ABYSS_TO, atX, atZ)
        check(abyss > 0) { "no deep water under the deep ocean at $atX, $atZ" }
        val abyssCells = biomeCellsOf("vanilladeepsea", "agesandtheart:abyss", ABYSS_FROM, ABYSS_TO, atX, atZ)
        check(abyssCells > 0) { "no abyss biome under the deep ocean at $atX, $atZ" }
    }

    test("the abyss offers a hadalfish to vanilla's spawner, and a book leaning away from hostile things thins it") {
        /** What the census says of the hadalfish on an abyss floor under [key]: `offered` or `asked`. */
        fun hadalfishIn(age: String, key: String): String? {
            val said = server.ask("spawns", "$age 2")[key]?.asJsonArray?.map { it.asJsonObject }.orEmpty()
            val inTheAbyss = said.firstOrNull { it["where"].asString == "monster abyss" } ?: return null
            return inTheAbyss["creatures"].asJsonArray.map { it.asString }.firstOrNull { "hadalfish" in it }
        }
        check(hadalfishIn("vanilladeepsea", "offered") != null) { "the abyss of a deep sea offers no hadalfish" }
        // Aimed at the creatures: laid bare, a lean on everything leans only some of what it reaches.
        server.run("age write vanillahomely 7 age homely spawns deep sea")
        val rung = hadalfishIn("vanillahomely", "asked")?.substringAfterLast(' ')?.toDouble()
        check(rung != null && rung < 1.0) { "a homely Age asks for the hadalfish at $rung, no less than one that said nothing" }
    }

    test("the floor of an abyss on vanilla's rock holds whalefalls, and vents keeping hadalfish where there is a deluge") {
        server.run("age write vanillavents 7 age deep sea deluge phenomena")
        val (atX, atZ) = deepOceanIn("vanillavents", WIDE)
        val squares = (-WIDE..<WIDE step SQUARE).flatMap { dx -> (-WIDE..<WIDE step SQUARE).map { dz -> dx to dz } }
        fun countAcross(block: String, fromY: Int) = squares.sumOf { (dx, dz) ->
            (fromY..<fromY + SQUARE * STACKED step SQUARE).sumOf { y ->
                countOf("vanillavents", block, y, y + SQUARE - 1, atX + dx, atZ + dz, side = SQUARE - 1)
            }
        }
        // Vanilla buries its own fossils under y=-8 and lays none in an ocean, so bone above that is a whalefall's.
        val bones = countAcross("minecraft:bone_block", WHALEFALL_FROM)
        check(bones > 0) { "no whalefall within $WIDE blocks of the deep ocean at $atX, $atZ" }
        val lining = countAcross("agesandtheart:vent_lining", VENT_FROM)
        check(lining > 0) { "no vent within $WIDE blocks of the deep ocean at $atX, $atZ" }
        // One vent in four keeps a hadalfish, and nothing else brings one where nobody has been.
        val box = "x=${atX - WIDE},y=-64,z=${atZ - WIDE},dx=${WIDE * 2},dy=$ALL_THE_HEIGHT,dz=${WIDE * 2}"
        val fishSaid = inAge("vanillavents", "execute if entity @e[type=agesandtheart:hadalfish,$box]")
        val fish = Regex("""Count: (\d+)""").find(fishSaid)?.groupValues?.get(1)?.toInt() ?: 0
        check(fish > 0) { "no hadalfish kept by any vent within $WIDE blocks of $atX, $atZ: $fishSaid" }
    }
})

/** Vanilla's sea stands to y=62; the least a `deep` sea moves it is some fifty-nine blocks. */
private const val HIGH_WATER_FROM = 90
private const val HIGH_WATER_TO = 110
private const val ABYSS_FROM = -20
private const val ABYSS_TO = 40
private const val SIDE = 15

/** Half the side of the area searched for what lies on the floor: eleven chunks across, give or take. */
private const val WIDE = 80

/** The side of one counted cube, which keeps each fill inside vanilla's 32768-block limit. */
private const val SQUARE = 32

private const val STACKED = 3

/** The floors of an abyss on vanilla's rock lie between about here and the line, at y=71 or more. */
private const val WHALEFALL_FROM = 0

private const val ALL_THE_HEIGHT = 384

/** A vent's chamber lies a dozen blocks under its bed. */
private const val VENT_FROM = -16
