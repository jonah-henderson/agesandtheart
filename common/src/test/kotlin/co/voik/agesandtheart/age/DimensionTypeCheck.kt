package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.worldgen.VerticalWindow
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.data.worldgen.SurfaceRuleData
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
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
@Tags(NEEDS_REGISTRIES)
class DimensionTypeCheck : FunSpec({

    val shipped = File("src/main/resources/data/agesandtheart/dimension_type")

    val sealed = Options(mapOf(Sky.SEALED.name to listOf("always")))
    val unlit = Options(mapOf(Sky.SHINING.name to listOf(Sky.NEVER)))

    /**
     * **Every set of facts an Age can have, and the type each earns.** Three rather than four: roofed and
     * lit is a combination the world cannot be in, so no file ships for it.
     */
    val everyCombination = listOf(
        Triple(Options(), Options(), AgeGeneration.AGE_DIMENSION_TYPE),
        Triple(Options(), unlit, AgeGeneration.AGE_LIGHTLESS_DIMENSION_TYPE),
        Triple(sealed, Options(), AgeGeneration.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE),
    )

    test("each set of facts picks its own type") {
        for ((sky, sun, expected) in everyCombination) {
            val chosen = Sky.dimensionType(sky, sun)
            check(chosen == expected) { "$sky $sun picked $chosen rather than $expected" }
        }
        check(everyCombination.map { it.third }.distinct().size == everyCombination.size) {
            "two sets of facts share a dimension type"
        }
    }

    /**
     * **Each world dresses ground of ours in its own skin**, and no two of them share one.
     *
     * The surface tree was `SurfaceRuleData.overworldLike` for every Age whichever template it started
     * from, so an infernal Age grew grass on its hills — the overworld's tree does not know a nether biome
     * and falls through to its own default, which is dirt with grass on top (Jonah, 2026-08-14, walked).
     *
     * Compared by what each tree *encodes to* rather than by identity: a `RuleSource` is a tree of records
     * built fresh on every call, so two calls to `nether()` are equal in meaning and not by reference.
     */
    test("each template dresses our ground in its own world's skin") {
        MinecraftRegistries.ensureStoodUp()
        fun spelled(rule: SurfaceRules.RuleSource) =
            SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow().toString()

        val theirs = mapOf(
            AgeTemplate.INFERNAL to SurfaceRuleData.nether(),
            AgeTemplate.DARK_VOID to SurfaceRuleData.end(),
        )
        for ((template, tree) in theirs) {
            check(spelled(template.skin) == spelled(tree)) {
                "${template.key} dresses our ground in something that is not its own world's skin"
            }
        }

        val distinct = AgeTemplate.entries.map { spelled(it.skin) }.distinct()
        check(distinct.size == AgeTemplate.entries.size) {
            "two templates share a skin, so at least one is wearing another world's"
        }
    }

    /**
     * **A world wearing another's rock wears its type**, and vanilla's three are the three ours restate.
     *
     * Nothing else would notice a template pointing at a type that is not there until an Age was written
     * over it and the world failed to open. And the pairing is not arbitrary: the facts of a template's own
     * world are what decide whether an Age keeps its type or falls back to one of ours, so the two must
     * agree about which of the three each world is.
     */
    test("each template's own type matches the facts of its world") {
        MinecraftRegistries.ensureStoodUp()
        val ourEquivalent = mapOf(
            BuiltinDimensionTypes.OVERWORLD to AgeGeneration.AGE_DIMENSION_TYPE,
            BuiltinDimensionTypes.NETHER to AgeGeneration.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE,
            BuiltinDimensionTypes.END to AgeGeneration.AGE_LIGHTLESS_DIMENSION_TYPE,
        )
        for (template in AgeTemplate.entries) {
            val world = template.world()
            val facts = Sky.dimensionType(world.optionsFor(Aspect.SKY, 0), world.optionsFor(Aspect.SUN, 0))
            val restated = ourEquivalent[template.dimensionType]
            check(restated != null) { "${template.key} wears ${template.dimensionType}, which restates none of ours" }
            check(restated == facts) {
                "${template.key}'s world reads as $facts but it wears ${template.dimensionType}, which is $restated"
            }
        }
    }

    test("every type an Age can wear is shipped, and says what its switches asked for") {
        check(shipped.isDirectory) { "no dimension types ship from ${shipped.absolutePath}" }
        for ((sky, sun, expected) in everyCombination) {
            val file = File(shipped, "${expected.path}.json")
            check(file.isFile) { "'${expected.path}' is chosen by $sky $sun and ships no file" }
            val written = JsonParser.parseString(file.readText()).asJsonObject
            val wantsRoof = sky.of(Sky.SEALED) == "always"
            val wantsSkylight = !wantsRoof && sun.of(Sky.SHINING) != Sky.NEVER
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
    test("every type declares the same band of world") {
        for ((_, _, id) in everyCombination) {
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
