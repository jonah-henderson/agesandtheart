package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.worldgen.field.TerrainFill
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings

/**
 * **What a book can still change about a rock it did not lay.**
 *
 * An Age that names no landform keeps the template's own generator, and the only way a sentence reaches it
 * is by substitution into `NoiseGeneratorSettings` — one block, one fluid, one surface rule. Everything
 * else about that world is vanilla's, deliberately.
 *
 * The sea went missing down that path and nothing said so: `Terrain.VANILLA` declares no waterline,
 * because vanilla's router pours its own fluid and there is nothing for a `SeaFill` of ours to fill. So the
 * fill came out `NONE` for every Age wearing this rock, and reading the sea off it meant a book could ask
 * for lava over the overworld and get water (Jonah, 2026-08-25, walked). It is read off the composition
 * now, which is the only place that ever knew.
 */
@Tags(NEEDS_REGISTRIES)
class VanillasRockCheck : FunSpec({

    fun theirs(template: AgeTemplate): NoiseGeneratorSettings =
        MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(template.rock).value()

    /** A book over [template] saying exactly [said] and nothing else — the shape `/age compose` builds. */
    fun writing(template: AgeTemplate, vararg said: Pair<String, String>): AgeComposition {
        val spelled = said.joinToString(" ") { (parameter, value) -> "$parameter=$value" }
        return AgeComposition.parse("template=${template.key} landmass=vanilla $spelled")
            .getOrElse { error("'$spelled' over ${template.key} is not a composition this build parses: $it") }
    }

    test("the sea a book named is the fluid that world is given") {
        MinecraftRegistries.ensureStoodUp()
        val lavaOverworld = AgeGeneration.vanillasRockFor(
            theirs(AgeTemplate.OVERWORLD),
            writing(AgeTemplate.OVERWORLD, Aspect.SEA.page to "lava"),
            TerrainFill.PLAIN,
        )
        check(lavaOverworld.defaultFluid().`is`(Blocks.LAVA)) {
            "an overworld Age written with a sea of lava is filled with ${lavaOverworld.defaultFluid()}"
        }

        val wetNether = AgeGeneration.vanillasRockFor(
            theirs(AgeTemplate.INFERNAL),
            writing(AgeTemplate.INFERNAL, Aspect.SEA.page to "water"),
            TerrainFill.PLAIN,
        )
        check(wetNether.defaultFluid().`is`(Blocks.WATER)) {
            "an infernal Age written with a sea of water is filled with ${wetNether.defaultFluid()}"
        }
    }

    /**
     * The control, and the half that must not move: a book adding nothing to the world it was written over
     * rebuilds that world, so `/age write nether infernal age` is the nether and not a dry one.
     *
     * `AgeTemplate.world()` is what a written Age starts from and it names the sea, which is why this
     * reads it rather than an empty spelling. **A spelling with no `sea=` in it genuinely means no sea** —
     * `AgeComposition` defaults to [Sea.NONE] — and `/age compose` is verbatim by contract, so an Age
     * composed by hand over vanilla's rock now agrees with one composed over a landform of ours about what
     * silence means. It did not before: this path quietly substituted the world's own fluid.
     */
    test("and a book that added nothing rebuilds the world it was written over") {
        MinecraftRegistries.ensureStoodUp()
        for (template in AgeTemplate.entries) {
            val world = theirs(template)
            val asItWas = AgeGeneration.vanillasRockFor(world, template.world(), TerrainFill.PLAIN)
            check(asItWas.defaultFluid() == world.defaultFluid()) {
                "${template.key} added nothing and was given ${asItWas.defaultFluid()} " +
                    "where its own world holds ${world.defaultFluid()}"
            }
            check(asItWas.defaultBlock() == world.defaultBlock()) {
                "${template.key} added nothing and is made of ${asItWas.defaultBlock()} " +
                    "where its own world is ${world.defaultBlock()}"
            }
        }
    }

    /** And silence in a hand-written spelling is a sea of nothing, which is what that spelling says. */
    test("a spelling with no sea in it asks for no sea") {
        MinecraftRegistries.ensureStoodUp()
        val composed = AgeGeneration.vanillasRockFor(
            theirs(AgeTemplate.OVERWORLD),
            writing(AgeTemplate.OVERWORLD),
            TerrainFill.PLAIN,
        )
        check(composed.defaultFluid().isAir) {
            "a spelling naming no sea was given ${composed.defaultFluid()} rather than open space"
        }
    }

    test("the rock a book named is the block that world is made of") {
        MinecraftRegistries.ensureStoodUp()
        val world = theirs(AgeTemplate.OVERWORLD)
        val written = writing(AgeTemplate.OVERWORLD, "${Aspect.TERRAIN.page}.${Terrain.STONE.name}" to BLACKSTONE)
        val fill = TerrainFill(listOf(written.optionsFor(Aspect.TERRAIN, 0).materialsOf(Terrain.STONE)))

        val laid = AgeGeneration.vanillasRockFor(world, written, fill)
        check(laid.defaultBlock().`is`(Blocks.BLACKSTONE)) {
            "an Age written in blackstone over vanilla's rock is made of ${laid.defaultBlock()}"
        }
    }

    /**
     * **What it cannot honour, it says.** One `defaultBlock` and one `defaultFluid` mean a book naming two
     * of either gets the first — which is a fact about the world it was written over rather than a defect,
     * so it belongs in the report a writer reads and not in a silent drop.
     */
    test("a second rock or a second sea is reported rather than dropped") {
        MinecraftRegistries.ensureStoodUp()
        fun reportOn(vararg said: Pair<String, String>): List<String> =
            AgeRecipe(
                world = AgeWorld.Composed(writing(AgeTemplate.OVERWORLD, *said)),
                seed = ONE_SEED,
                template = AgeTemplate.OVERWORLD,
            ).unhonoured

        val twoSeas = reportOn(Aspect.SEA.page to "water,lava")
        check(twoSeas.any { Aspect.SEA.page in it }) { "an Age written with two seas reports $twoSeas" }

        val twoRocks = reportOn("${Aspect.TERRAIN.page}.${Terrain.STONE.name}" to "$BLACKSTONE,minecraft:tuff")
        check(twoRocks.any { Terrain.STONE.name in it }) { "an Age written in two rocks reports $twoRocks" }

        // The control: one rock and one sea are what this world can hold, so neither is reported.
        val ordinary = reportOn(Aspect.SEA.page to "water")
        check(ordinary.none { Aspect.SEA.page in it || Terrain.STONE.name in it }) {
            "an Age asking for one rock and one sea reports $ordinary"
        }
    }

    /** A surface the writer named is the third and last thing that reaches a rock we did not lay. */
    test("and the skin a book named is the rule that world wears") {
        MinecraftRegistries.ensureStoodUp()
        val world = theirs(AgeTemplate.OVERWORLD)
        val silent = AgeGeneration.vanillasRockFor(world, writing(AgeTemplate.OVERWORLD), TerrainFill.PLAIN)
        check(silent.surfaceRule() == world.surfaceRule()) { "a book that named no skin repainted the world" }

        val dressed = AgeGeneration.vanillasRockFor(
            world,
            writing(AgeTemplate.OVERWORLD, "${Aspect.SURFACE.page}.${Surface.MATERIAL.name}" to BLACKSTONE),
            TerrainFill.PLAIN,
        )
        check(dressed.surfaceRule() != world.surfaceRule()) { "a book that named a skin was given the world's own" }
    }
})

/** Nothing here reads a seed; a recipe simply has to carry one. */
private const val ONE_SEED = 4242L

private const val BLACKSTONE = "minecraft:blackstone"
