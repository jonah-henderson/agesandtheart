package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.worldgen.VerticalWindow
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * That every dimension type an Age can be told to wear is one we actually ship, and that the four differ
 * only where they are meant to.
 *
 * **Nothing else would say so until a world opened.** `Sky.dimensionType` answers an identifier and the
 * backend takes a `ResourceKey`, so a type with no file behind it is not a compile error, not a load error, and not
 * something a recipe can notice — it is a dimension that comes up wrong on a player's screen.
 */
class DimensionTypeCheck : FunSpec({

    val shipped = File("src/main/resources/data/agesandtheart/dimension_type")

    val everyCombination = listOf(
        Options() to AgeGeneration.AGE_DIMENSION_TYPE,
        Options(mapOf(Sky.SKYLIGHT.name to listOf("none"))) to AgeGeneration.AGE_LIGHTLESS_DIMENSION_TYPE,
        Options(mapOf(Sky.ROOF.name to listOf("always"))) to AgeGeneration.AGE_ROOFED_DIMENSION_TYPE,
        Options(mapOf(Sky.SKYLIGHT.name to listOf("none"), Sky.ROOF.name to listOf("always")))
            to AgeGeneration.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE,
    )

    test("each pair of switches picks its own type") {
        for ((options, expected) in everyCombination) {
            val chosen = Sky.dimensionType(options)
            check(chosen == expected) { "$options picked $chosen rather than $expected" }
        }
        check(everyCombination.map { it.second }.distinct().size == everyCombination.size) {
            "two combinations of switches share a dimension type"
        }
    }

    test("every type an Age can wear is shipped, and says what its switches asked for") {
        check(shipped.isDirectory) { "no dimension types ship from ${shipped.absolutePath}" }
        for ((options, expected) in everyCombination) {
            val file = File(shipped, "${expected.path}.json")
            check(file.isFile) { "'${expected.path}' is chosen by $options and ships no file" }
            val written = JsonParser.parseString(file.readText()).asJsonObject
            val wantsSkylight = options.of(Sky.SKYLIGHT) != "none"
            val wantsRoof = options.of(Sky.ROOF) == "always"
            check(written.get("has_skylight").asBoolean == wantsSkylight) {
                "'${expected.path}' has_skylight is ${written.get("has_skylight")}, asked $wantsSkylight"
            }
            check(written.get("has_ceiling").asBoolean == wantsRoof) {
                "'${expected.path}' has_ceiling is ${written.get("has_ceiling")}, asked $wantsRoof"
            }
        }
    }

    /**
     * The band is the one thing that must **not** vary, because terrain is generated against it: a type
     * with a different floor would put an Age's landform in the wrong place for the same recipe.
     */
    test("all four declare the same band of world") {
        for ((_, id) in everyCombination) {
            val written = JsonParser.parseString(File(shipped, "${id.path}.json").readText()).asJsonObject
            check(written.get("min_y").asInt == VerticalWindow.DEFAULT.minY) {
                "'${id.path}' starts at ${written.get("min_y")} rather than ${VerticalWindow.DEFAULT.minY}"
            }
            check(written.get("height").asInt == VerticalWindow.DEFAULT.height) {
                "'${id.path}' is ${written.get("height")} tall rather than ${VerticalWindow.DEFAULT.height}"
            }
        }
    }
})
