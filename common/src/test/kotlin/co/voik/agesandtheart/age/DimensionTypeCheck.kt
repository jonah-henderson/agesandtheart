package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.sky.Described
import co.voik.agesandtheart.sky.described
import co.voik.agesandtheart.worldgen.VerticalWindow
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import co.voik.ephemeris.Rgba
import net.minecraft.core.registries.Registries
import org.joml.Vector3f
import org.joml.Vector3fc
import net.minecraft.data.worldgen.material.EndMaterialRules
import net.minecraft.data.worldgen.material.NetherMaterialRules
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
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

    /** 26.2\'s surface rules ask for the biome registry; these are vanilla\'s own. */
    val BIOMES = MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME)
    val RULES = MinecraftRegistries.worldgen.lookupOrThrow(Registries.MATERIAL_RULE)

    val shipped = File("src/main/resources/data/agesandtheart/dimension_type")

    val sealed = Options(mapOf(Sky.SEALED.name to listOf(Parameter.TRUE)))
    val unlit = Options(mapOf(SkyBodies.ABSENT.name to listOf(Parameter.TRUE)))

    /** Each of vanilla's three worlds and the type of ours that restates it. */
    val ourEquivalent = mapOf(
        BuiltinDimensionTypes.OVERWORLD to Sky.AGE_DIMENSION_TYPE,
        BuiltinDimensionTypes.NETHER to Sky.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE,
        BuiltinDimensionTypes.END to Sky.AGE_LIGHTLESS_DIMENSION_TYPE,
    )

    /**
     * **Every set of facts an Age can have, and the type each earns.** Three rather than four: roofed and
     * lit is a combination the world cannot be in, so no file ships for it.
     */
    val everyCombination = listOf(
        Triple(Options(), Options(), Sky.AGE_DIMENSION_TYPE),
        Triple(Options(), unlit, Sky.AGE_LIGHTLESS_DIMENSION_TYPE),
        Triple(sealed, Options(), Sky.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE),
    )

    /**
     * **A world of solid rock is roofed whether or not anybody wrote the word**, which is the whole of what
     * `AgeParts.roofedByItsRock` exists for: the seal is a physical fact about the Age, and a landform that
     * reaches the ceiling settles it on its own.
     *
     * It matters beyond tidiness. The roofed type is what `Ages.footingIn` branches on to come *down* from
     * the ceiling for somewhere to stand, so without this a visitor to a world with no surface arrives on
     * top of it.
     */
    test("a landform that reaches the ceiling is roofed without the word") {
        val open = Described()
        check(Sky.dimensionType(open) == Sky.AGE_DIMENSION_TYPE) {
            "an Age with nothing said about it is not the ordinary one"
        }
        val solidRock = Described(roofedByItsRock = true)
        check(Sky.dimensionType(solidRock) == Sky.AGE_LIGHTLESS_ROOFED_DIMENSION_TYPE) {
            "a world of solid rock wears ${Sky.dimensionType(solidRock)} rather than the roofed type"
        }
        check(Sky.isRoofed(solidRock) && Sky.isLightless(solidRock)) {
            "a world of solid rock reads as open to the sky"
        }
    }

    /** And the landform is what says so, rather than the fact being asserted about a flag. */
    test("solid is the landform that roofs the world, and the only one") {
        val roofing = Terrain.entries.filter { it.roofsTheWorld }
        check(roofing == listOf(Terrain.SOLID)) { "these landforms claim to roof the world: $roofing" }
    }

    test("each set of facts picks its own type") {
        for ((sky, sun, expected) in everyCombination) {
            val chosen = Sky.dimensionType(described(sky, sun))
            check(chosen == expected) { "$sky $sun picked $chosen rather than $expected" }
        }
        check(everyCombination.map { it.third }.distinct().size == everyCombination.size) {
            "two sets of facts share a dimension type"
        }
    }

    /**
     * **Each world dresses ground of ours in its own skin**, and no two of them share one.
     *
     * The surface tree was the overworld's for every Age whichever template it started from, so an
     * infernal Age grew grass on its hills — the overworld's tree does not know a nether biome and falls
     * through to its own default, which is dirt with grass on top (Jonah, 2026-08-14, walked).
     *
     * **Asked which tree each template names**, which is what 26.3 turned this into: each of vanilla's is
     * a registry entry now, so a skin encodes to the id it points at rather than to ninety lines of
     * inlined records. Comparing an encoded tree against one we built the same way would have compared
     * `getRule(NETHER)` with `getRule(NETHER)` and been unfalsifiable — and naming the keys covers all
     * three templates where the old shape could only reach two, the overworld's having been built from
     * flags rather than named.
     */
    test("each template dresses our ground in its own world's skin") {
        fun spelled(rule: MaterialRule) =
            MaterialRule.CODEC.encodeStart(
                MinecraftRegistries.worldgen.createSerializationContext(JsonOps.INSTANCE),
                rule,
            ).getOrThrow().toString()

        // The overworld's is its surface tree rather than its whole floating-islands rule, which since 26.3
        // also carries the ore veins — off on our rock, where `veins` is how a book asks for them.
        val theirs = mapOf(
            AgeTemplate.OVERWORLD to "minecraft:overworld/surface",
            AgeTemplate.INFERNAL to NetherMaterialRules.NETHER.identifier().toString(),
            AgeTemplate.DARK_VOID to EndMaterialRules.END.identifier().toString(),
        )
        check(theirs.keys.containsAll(AgeTemplate.entries.toSet())) {
            "${AgeTemplate.entries - theirs.keys} name no expected skin, so nothing here checks them"
        }
        for ((template, expected) in theirs) {
            val named = spelled(template.skin(RULES))
            check(expected in named) { "${template.key} dresses our ground in $named rather than in $expected" }
            check("ore_vein" !in named) { "${template.key} lays vanilla's ore veins over our rock: $named" }
        }

        val byTemplate = AgeTemplate.entries.associateWith { spelled(it.skin(RULES)) }
        val shared = byTemplate.entries.groupBy({ it.value }, { it.key.key }).filterValues { it.size > 1 }
        check(shared.isEmpty()) {
            "these templates share a skin, so at least one is wearing another world's: $shared"
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
        for (template in AgeTemplate.entries) {
            val world = template.world()
            val facts = Sky.dimensionType(world)
            val restated = ourEquivalent[template.dimensionType]
            check(restated != null) { "${template.key} wears ${template.dimensionType}, which restates none of ours" }
            check(restated == facts) {
                "${template.key}'s world reads as $facts but it wears ${template.dimensionType}, which is $restated"
            }
        }
    }

    /**
     * **A dragon is a template's to lend, and no type of ours may claim one.**
     *
     * `ServerLevel` builds the fight in its own constructor when the level's type asks for one, so
     * `Ages.lendTheDragon` hands one to an Age wearing ours and stands aside where vanilla has already
     * done it. Both halves of that rest on this: a type of ours that started claiming a dragon would fire
     * both paths and set two fights on one saved record, and a template that stopped carrying one would
     * leave the lending reaching for something that is not there.
     */
    test("a dragon belongs to a template's own world, never to a type of ours") {
        val vanillas = MinecraftRegistries.worldgen.lookupOrThrow(Registries.DIMENSION_TYPE)
        val lending = AgeTemplate.entries
            .filter { vanillas.getOrThrow(it.dimensionType).value().hasEnderDragonFight() }

        check(lending == listOf(AgeTemplate.DARK_VOID)) {
            "the worlds carrying a dragon are $lending, and the lending in Ages was written for the End alone"
        }

        fun claimsADragon(id: Identifier): Boolean = JsonParser.parseString(File(shipped, "${id.path}.json").readText())
            .asJsonObject.get("has_ender_dragon_fight").asBoolean

        val claimants = everyCombination.map { it.third }.filter(::claimsADragon)
        check(claimants.isEmpty()) {
            "$claimants claim a dragon of their own, so an Age wearing one would be given a second"
        }
    }

    /**
     * **Every colour the world it restates states, ours states too.**
     *
     * An attribute a dimension type says nothing about falls back to its *registered* default, and for
     * these three that default is `#000000`. A biome overrides them only where it declares them, and most
     * do not — plains and beach carry no fog colour — so leaving one out does not hand the question to the
     * biome. It paints every Age black.
     *
     * That was walked: `fog_color` and `sky_color` were left out deliberately, on the reasoning that a
     * biome carries its own, and every Age came up with black fog at every height over every landform
     * (Jonah, 2026-09-03). `ambient_light_color` had been caught the same way once already and was the
     * only one this check knew about, which is why it did not catch the other two.
     */
    test("each type of ours states every colour of the world it restates") {
        val vanillas = MinecraftRegistries.worldgen.lookupOrThrow(Registries.DIMENSION_TYPE)

        val colours = listOf(
            EnvironmentAttributes.AMBIENT_LIGHT_COLOR to "minecraft:visual/ambient_light_color",
            EnvironmentAttributes.FOG_COLOR to "minecraft:visual/fog_color",
            EnvironmentAttributes.SKY_COLOR to "minecraft:visual/sky_color",
        )

        // No cast: a colour attribute is typed `Vector3fc` in 26.3, where it was a packed `Int` and this
        // had to assert the pairing the registry could not.
        fun vanillasIs(world: ResourceKey<DimensionType>, attribute: EnvironmentAttribute<Vector3fc>): String {
            val colour = vanillas.getOrThrow(world).value().attributes().applyModifier(attribute, BLACK)
            return "#%06X".format(Rgba.of(colour).packed() and RGB)
        }

        fun oursDeclares(id: Identifier, key: String): String? =
            JsonParser.parseString(File(shipped, "${id.path}.json").readText())
                .asJsonObject.getAsJsonObject("attributes").get(key)?.asString?.uppercase()

        for ((world, ours) in ourEquivalent) {
            for ((attribute, key) in colours) {
                val theirs = vanillasIs(world, attribute)
                val declared = oursDeclares(ours, key)
                check(declared != null) {
                    "'${ours.path}' declares no $key, so it falls to the attribute's own #000000 and is " +
                        "blacker than ${world.identifier()}, the world it restates"
                }
                check(declared == theirs) {
                    "'${ours.path}' states $declared for $key where ${world.identifier()} states $theirs"
                }
            }
        }
    }

    test("every type an Age can wear is shipped, and says what its switches asked for") {
        check(shipped.isDirectory) { "no dimension types ship from ${shipped.absolutePath}" }
        for ((sky, sun, expected) in everyCombination) {
            val file = File(shipped, "${expected.path}.json")
            check(file.isFile) { "'${expected.path}' is chosen by $sky $sun and ships no file" }
            val written = JsonParser.parseString(file.readText()).asJsonObject
            val wantsRoof = sky.isTrue(Sky.SEALED)
            val wantsSkylight = !wantsRoof && !sun.isTrue(SkyBodies.ABSENT)
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
     * with a different **floor** would put an Age's landform in the wrong place for the same recipe.
     */
    test("every type declares the same band of world") {
        val generated = VerticalWindow.DEFAULT
        for ((_, _, id) in everyCombination) {
            val written = JsonParser.parseString(File(shipped, "${id.path}.json").readText()).asJsonObject
            check(written.get("min_y").asInt == generated.minY) {
                "'${id.path}' starts at ${written.get("min_y")} rather than ${generated.minY}"
            }
            check(written.get("height").asInt == generated.height) {
                "'${id.path}' is ${written.get("height")} tall rather than ${generated.height}"
            }
        }
    }
})

/** The value an ambient light is read against, and the channels of one. */
/** Nothing at all, which is what an attribute falls back to — a vector now rather than a packed nought. */
private val BLACK: Vector3fc = Vector3f(0.0f, 0.0f, 0.0f)
private const val RGB = 0xFFFFFF
