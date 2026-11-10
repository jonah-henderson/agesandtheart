package co.voik.agesandtheart.worldgen.ruins

import co.voik.agesandtheart.worldgen.ruins.JigsawWiring.Connector
import co.voik.agesandtheart.worldgen.ruins.JigsawWiring.Piece
import co.voik.agesandtheart.worldgen.ruins.JigsawWiring.Pool
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.File

/**
 * That the D'ni city's shipped pieces and pools are wired up ([JigsawWiring]): every exit finds an entrance in the
 * pool it draws from, and every pool names pieces that exist. Read from the files, so a mistake fails here rather
 * than as a city that quietly stops short.
 */
class RuinsWiringCheck : FunSpec({

    test("the city's shipped pieces and pools are wired up") {
        val pieces = PIECES.walkTopDown().filter { it.extension == "nbt" }.map(::pieceIn).toList()
        val pools = POOLS.walkTopDown().filter { it.extension == "json" }.map(::poolIn).toList()
        val problems = JigsawWiring.problemsIn(pieces, pools)
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }

    test("an exit finds the entrance it looks for in its pool or the pool's fallback") {
        val pieces = listOf(
            piece("hall", exit("walkway", pool = "corridors")),
            piece("corridor", entrance("walkway")),
            piece("cap", entrance("walkway")),
        )
        val pools = listOf(pool("corridors", fallback = "caps"), pool("caps", "cap"))
        check(JigsawWiring.problemsIn(pieces, pools).isEmpty()) { JigsawWiring.problemsIn(pieces, pools).joinToString("\n") }
    }

    test("each mistake the jigsaw block's screen invites is named") {
        val pieces = listOf(
            piece("stair", Connector(id("walkway_in"), NOTHING, id("corridors")), Connector(id("walkway"), id("walkway"), id("corridors"))),
            piece("hall", Connector(id("walkway_out"), id("walkway_in"), id("streets")), Connector(id("door_out"), id("walkway_in"), id("corridors"))),
            piece("corridor", entrance("other")),
        )
        val pools = listOf(pool("corridors", "corridor", "vanished"))
        val problems = JigsawWiring.problemsIn(pieces, pools)
        val expected = listOf("is an entrance but draws", "named neither", "streets, which does not exist", "not ${id("door_in")}", "no piece has the entrance", "vanished, which does not exist")
        val missed = expected.filter { phrase -> problems.none { phrase in it } }
        check(missed.isEmpty()) { "not named: $missed, among:\n${problems.joinToString("\n")}" }
    }
}) {
    private companion object {
        val PIECES = File("src/main/resources/data/agesandtheart/structure/ruins")
        val POOLS = File("src/main/resources/data/agesandtheart/worldgen/template_pool/ruins")
        const val NOTHING = JigsawWiring.NOTHING
        const val JIGSAW = "minecraft:jigsaw"

        fun id(path: String) = "${JigsawWiring.SCOPE}$path"

        fun entrance(base: String) = Connector(id("${base}_in"), NOTHING, NOTHING)

        fun exit(base: String, pool: String) = Connector(id("${base}_out"), id("${base}_in"), id(pool))

        fun piece(name: String, vararg connectors: Connector) = Piece(id(name), connectors.toList())

        fun pool(name: String, vararg pieces: String, fallback: String? = null) =
            Pool(id(name), pieces.map(::id), fallback?.let(::id) ?: NOTHING)

        /** A saved piece's connectors: the jigsaw blocks among its blocks, by their place in its palette. */
        fun pieceIn(file: File): Piece {
            val template = NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap())
            val palette = template.getListOrEmpty("palette").map { (it as CompoundTag).let(::blockName) }
            val connectors = template.getListOrEmpty("blocks")
                .map { it as CompoundTag }
                .filter { palette.getOrNull(it.getIntOr("state", -1)) == JIGSAW }
                .map { block ->
                    val jigsaw = block.getCompoundOrEmpty("nbt")
                    Connector(jigsaw.getStringOr("name", ""), jigsaw.getStringOr("target", ""), jigsaw.getStringOr("pool", ""))
                }
            return Piece(id(file.relativeTo(PIECES).invariantSeparatorsPath.removeSuffix(".nbt")), connectors)
        }

        /** 26.3 writes a template's palette as `id`, where older templates wrote `Name`. */
        fun blockName(state: CompoundTag): String = state.getStringOr("id", state.getStringOr("Name", ""))

        fun poolIn(file: File): Pool {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val pieces = json.getAsJsonArray("elements").map { it.asJsonObject.getAsJsonObject("element") }
                .filter { it.get("element_type")?.asString == "minecraft:single_pool_element" }
                .map { it.get("location").asString }
            val fallback = json.get("fallback")?.asString ?: NOTHING
            return Pool(id(file.relativeTo(POOLS).invariantSeparatorsPath.removeSuffix(".json")), pieces, fallback)
        }
    }
}
