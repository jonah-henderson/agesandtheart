package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.word.Resolution
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import java.util.Optional

/**
 * What an Age is, as data — the description its world is rebuilt from on every open.
 *
 * The resolved composition is what persists, never the words that produced it (design §4.6).
 */
data class AgeRecipe(
    val world: AgeWorld,
    val seed: Long,
    val character: AgeCharacter = AgeCharacter.LEGACY,
    /** Resolved once when the Age was written and kept; never re-derived from the words. */
    val instability: Instability = Instability.NONE,
    /** What the book said. Provenance only — nothing reads it to decide anything. */
    val words: List<String> = emptyList(),
    val generatorVersion: Int = CURRENT_GENERATOR_VERSION,
    /**
     * **The world this Age was written over** (`the-world-model.md` §4) — the base of the recipe.
     *
     * On the recipe rather than merged away, because a template supplies a thing the composition cannot
     * spell: `landmass=vanilla` says the rock is not ours, and *which* vanilla is this. Everything else a
     * template gives is merged into the composition and does not come back here, so what persists is still
     * the answer rather than the words (§4.6) — this is simply part of that answer.
     */
    val template: AgeTemplate = AgeTemplate.ORDINARY,
    /**
     * The overworld's game time when this Age was written — **the clock a decaying Age is read against**
     * (design §5.4).
     *
     * Immutable, and part of what an Age *is* in the way its seed is. §5.4's ruling is that a phenomenon
     * keeps no ledger and the blocks are its state; where that fails, the escape is to derive the state
     * from how long the Age has existed rather than to track it. Worsening and collapse both need that — how
     * holed an Age is has to be knowable in a chunk nobody has ever visited, or fresh chunks generate at
     * the state it had when it was written and there is a seam at the edge of where people have walked.
     *
     * **The overworld's clock rather than the Age's own**, because an Age's own only advances while it is
     * loaded, which is precisely when nobody is there — the register is meant to progress in your absence.
     *
     * Zero means an Age written before this existed, which reads as having been written at the beginning
     * of the world. That is wrong by however old the save is and harmless: it makes an old test Age decay
     * faster, not a live one decay wrongly.
     */
    val writtenAt: Long = UNRECORDED,
) {
    /** How long this Age has existed, in ticks, against [server]'s overworld clock. Never negative. */
    fun ageAt(server: MinecraftServer): Long =
        (server.overworld().gameTime - writtenAt).coerceAtLeast(0L)
    /** The composition this Age was assembled from, or null for the few that are not assembled. */
    val composition: AgeComposition? get() = (world as? AgeWorld.Composed)?.composition

    /**
     * What this Age asked for that the **world it was written over** cannot honour — said rather than
     * silently dropped, on the same argument as [AgeComposition.unknownOptions]: a parameter that does nothing
     * should look wrong instead of merely being ineffective.
     *
     * One entry so far. Vanilla's router has a single `defaultBlock`, so rock made of two things is rock
     * made of the first; an Age with a field of ours mingles them per column and has no such limit.
     */
    val unhonoured: List<String>
        get() = rockUnhonoured + biomesUnhonoured

    private val rockUnhonoured: List<String>
        get() {
            val written = composition ?: return emptyList()
            if (Terrain.VANILLA !in written.terrains) return emptyList()
            return listOfNotNull(oneMaterialOnly(written), oneSeaOnly(written), whateverItCutsItself(written))
        }

    /** A rock we did not lay has one material, whatever the book named after the first. */
    private fun oneMaterialOnly(written: AgeComposition): String? {
        val stone = written.optionsFor(Aspect.TERRAIN, 0).allSpelled(Terrain.STONE.name).toList()
        if (stone.size <= 1) return null
        return "${Aspect.TERRAIN.page}.${Terrain.STONE.name}=${stone.joinToString(",")} — " +
            "${template.key}'s own rock is one material, so ${stone.first()} is laid and the rest are not"
    }

    /**
     * **And it holds one sea**, on the same argument: `defaultFluid` is a single block, where a sea of ours
     * divides on the terrain's own map.
     */
    private fun oneSeaOnly(written: AgeComposition): String? {
        if (written.seas.size <= 1) return null
        return "${Aspect.SEA.page}=${written.seas.joinToString(",") { it.id.toString() }} — " +
            "${template.key}'s own rock holds one sea, so ${written.seas.first().id} fills it and the rest do not"
    }

    /**
     * **A rock we did not lay cuts its own caves**, and a carving named against it does nothing.
     *
     * The generator hands `applyCarvers` back to the superclass where the rock is vanilla's — its carvers
     * read the router the shape came out of, where ours would be cutting into a world they know nothing
     * about. Which is right, and left `rock=solid` over the overworld quietly full of caves.
     */
    private fun whateverItCutsItself(written: AgeComposition): String? {
        val asked = written.carvers
        if (asked == template.world().carvers) return null
        return "${Aspect.CARVERS.page}=${asked.joinToString(",") { it.key }} — " +
            "${template.key}'s own rock cuts its own caves, so it keeps them"
    }

    /**
     * What the world this Age was written over cannot honour about its **biomes**.
     *
     * A preference weighs a table a climate is looked up in, and the End has no climate — it picks by
     * distance from the centre. So a book that weighs biomes over the void is asking for something that
     * world has no way to answer, and is told rather than ignored.
     */
    private val biomesUnhonoured: List<String>
        get() {
            if (template.biomesAreChosenByClimate) return emptyList()
            val said = composition?.optionsFor(Aspect.BIOMES, 0)?.allSpelled(Biomes.GROWN.name).orEmpty()
            if (said.isEmpty()) return emptyList()
            return listOf(
                "${Aspect.BIOMES.page}.${Biomes.GROWN.name}=${said.joinToString(",")} — " +
                    "${template.key} chooses its biomes by place rather than by climate, so nothing weighs them",
            )
        }

    override fun toString(): String = buildString {
        if (words.isNotEmpty()) append("\"${words.joinToString(" ")}\" → ")
        // The template is the one part of an Age the composition cannot say, so `/age list` says it here
        // — and in the spelling `/age compose` reads back. Silence already means the overworld.
        if (template != AgeTemplate.ORDINARY) append("template=${template.key} ")
        append("$world seed=$seed")
        if (!instability.isCoherent) append(" [${instability.index}]")
    }

    companion object {
        /**
         * Bumped by hand whenever a change to generation would make the same recipe produce different
         * terrain. What moved at each version: `notes/generator-versions.md`.
         */
        const val CURRENT_GENERATOR_VERSION = 31

        val MAP_CODEC: MapCodec<AgeRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                // Optional only so recipes written before aspects existed still load.
                AgeWorld.MAP_CODEC.codec().optionalFieldOf("world").forGetter { Optional.of(it.world) },
                // Read for migration only.
                AgePreset.CODEC.optionalFieldOf("preset").forGetter { Optional.empty<AgePreset>() },
                Codec.LONG.fieldOf("seed").forGetter(AgeRecipe::seed),
                // Required, never `optionalFieldOf(name, default)`: that omits the field when it equals the
                // default, so a recipe would read back claiming whatever version is current when it is read.
                Codec.INT.fieldOf("generator_version").forGetter(AgeRecipe::generatorVersion),
                AgeCharacter.MAP_CODEC.codec().optionalFieldOf("character", AgeCharacter.LEGACY)
                    .forGetter(AgeRecipe::character),
                // Absent on every Age not written from words.
                Instability.CODEC.optionalFieldOf("instability", Instability.NONE)
                    .forGetter(AgeRecipe::instability),
                Codec.STRING.listOf().optionalFieldOf("words", emptyList()).forGetter(AgeRecipe::words),
                // Absent on every Age written before an Age had an age.
                Codec.LONG.optionalFieldOf("written_at", UNRECORDED).forGetter(AgeRecipe::writtenAt),
                // Absent on every Age written before a book started from a world.
                AgeTemplate.CODEC.optionalFieldOf("template", AgeTemplate.ORDINARY)
                    .forGetter(AgeRecipe::template),
            ).apply(instance) {
                world, legacyPreset, seed, version, character, instability, words, writtenAt, template,
                ->
                AgeRecipe(
                    world = world.orElseGet { worldFor(legacyPreset.orElse(AgePreset.SPIRE)) },
                    seed = seed,
                    character = character,
                    instability = instability,
                    words = words,
                    generatorVersion = version,
                    template = template,
                    writtenAt = writtenAt,
                )
            }
        }

        val CODEC: Codec<AgeRecipe> = MAP_CODEC.codec()

        /** A fresh recipe for one of the classic demo presets. */
        fun of(preset: AgePreset, id: Identifier): AgeRecipe =
            AgeRecipe(worldFor(preset), seedFor(id))

        /** A fresh recipe, with its character drawn from [seed] and the world [server] is running. */
        fun written(
            server: MinecraftServer,
            world: AgeWorld,
            seed: Long,
            template: AgeTemplate = AgeTemplate.ORDINARY,
        ): AgeRecipe = AgeRecipe(
            seamed(world, seed),
            seed,
            AgeCharacter.drawn(server, seed),
            writtenAt = server.overworld().gameTime,
            template = template,
        )

        /** A fresh recipe for an Age somebody wrote: the resolved composition, plus words and instability. */
        fun written(server: MinecraftServer, resolution: Resolution, seed: Long): AgeRecipe = AgeRecipe(
            seamed(AgeWorld.Composed(resolution.composition), seed),
            seed,
            AgeCharacter.drawn(server, seed),
            resolution.instability,
            resolution.sentence,
            writtenAt = server.overworld().gameTime,
            template = resolution.template,
        )

        /**
         * [world] with a form drawn for every boundary it has one — see [AgeComposition.seamed]. Here
         * rather than in the resolver, because a hand-composed Age has boundaries too and a bespoke world
         * has none to draw.
         */
        private fun seamed(world: AgeWorld, seed: Long): AgeWorld = when (world) {
            is AgeWorld.Composed -> AgeWorld.Composed(world.composition.seamed(seed))
            is AgeWorld.Bespoke -> world
        }

        /** An Age from before an Age had an age — read as having been written when the world began. */
        const val UNRECORDED = 0L

        /** The seed an Age gets when nothing has chosen one for it. */
        fun seedFor(id: Identifier): Long = id.hashCode().toLong()

        /** The world a classic preset names — also what a pre-aspects recipe migrates to. */
        fun worldFor(preset: AgePreset): AgeWorld {
            val composition = when (preset) {
                AgePreset.VANILLA, AgePreset.VANILLA_BARE -> return AgeWorld.Bespoke(preset)

                // Both keys build the one composition, so they cannot drift apart.
                AgePreset.SPIRE, AgePreset.FIELD -> spire()
                AgePreset.PYRAMIDS -> pyramids("grid")
                AgePreset.PYRINGS -> pyramids("rings")
                AgePreset.PYRVARIED -> pyramids("varied")
                AgePreset.SHAPES -> AgeComposition(terrains = listOf(Terrain.SHAPES))
                AgePreset.PILLARS -> AgeComposition(terrains = listOf(Terrain.PILLARS), seas = listOf(Sea.WATER))
                AgePreset.ERODED -> AgeComposition(terrains = listOf(Terrain.ERODED), seas = listOf(Sea.WATER))
                // The sea is the river: there is no open ground for anything else to stand on. Grounded so
                // that it reads as one — ungrounded, the gorge floor takes whatever vanilla's climate noise
                // files there, which is an ocean about as often as anything else.
                AgePreset.CANYON -> grounded(
                    AgeComposition(terrains = listOf(Terrain.CANYON), seas = listOf(Sea.WATER)),
                )
                // Grounded: an ocean this size wants ocean biomes over it and a beach where it meets the
                // cliff, and nothing about the preset is trying to be strange.
                AgePreset.CLIFFS -> grounded(
                    AgeComposition(terrains = listOf(Terrain.CLIFFS), seas = listOf(Sea.WATER)),
                )
                AgePreset.CANYONLANDS ->
                    AgeComposition(terrains = listOf(Terrain.CANYONLANDS), seas = listOf(Sea.WATER))
                AgePreset.SHATTERED ->
                    AgeComposition(terrains = listOf(Terrain.SHATTERED), seas = listOf(Sea.WATER))
                // Grounded, since an endless sea most of all wants ocean biomes over it.
                AgePreset.ISLANDS -> grounded(
                    AgeComposition(
                        terrains = listOf(Terrain.ISLANDS),
                        seas = listOf(Sea.WATER),
                        carvers = listOf(Carvers.CAVES),
                    ),
                )
                // The one landform here that is straightforwardly habitable, so it gets caves too — and
                // it is the strongest case for grounding, having rivers for the biomes to agree with.
                AgePreset.RIVERLANDS -> grounded(
                    AgeComposition(
                        terrains = listOf(Terrain.RIVERLANDS),
                        seas = listOf(Sea.WATER),
                        carvers = listOf(Carvers.CAVES),
                    ),
                )
                // Grounded, and it is the strongest case for it yet: without biomes that agree with the
                // shape a range has no treeline and no snowline, and two hundred blocks of climb pass
                // through no country at all. See `Elevation`.
                AgePreset.ALPS -> grounded(
                    AgeComposition(
                        terrains = listOf(Terrain.ALPS),
                        seas = listOf(Sea.WATER),
                        carvers = listOf(Carvers.CAVES),
                    ),
                )
                AgePreset.HILLS -> AgeComposition(
                    terrains = listOf(Terrain.HILLS),
                    seas = listOf(Sea.WATER),
                    carvers = listOf(Carvers.CAVES),
                )
                // Its caves are its shape, so nothing is carved — but the rock still runs wet and dry.
                AgePreset.CAVERNS -> AgeComposition(
                    terrains = listOf(Terrain.CAVERNS),
                    seas = listOf(Sea.WATER),
                    carvers = listOf(Carvers.POROUS),
                )
                // Grounded, and it wants it twice over: the round sea at the middle needs ocean biomes
                // and a shore, and a hundred blocks of ring wall needs a treeline to be a climb through
                // anything. Carved, the plain being ordinary habitable country.
                AgePreset.CRATERLANDS -> grounded(
                    AgeComposition(
                        terrains = listOf(Terrain.CRATERLANDS),
                        seas = listOf(Sea.WATER),
                        carvers = listOf(Carvers.CAVES),
                    ),
                )
                // No sea named at all, since the terrain claims no waterline for one to stand at. Carved,
                // though: a carver reaching the cast severs it, and a bridge that is out is a better
                // problem to be given than a bridge that was never there. Ungrounded, because grounding
                // reads erosion off the local fall and every face here is a cliff.
                AgePreset.INVERSE_CAVES -> AgeComposition(
                    terrains = listOf(Terrain.INVERSE_CAVES),
                    carvers = listOf(Carvers.CAVES),
                )
                // Grounded, because the whole of what is above ground here is meant to read as ordinary —
                // the halls are the strange part and they are better for arriving under somewhere real.
                // Carved as well as halled: vanilla's caves are what connect the surface down into them,
                // and a hall you cannot find from above is a hall nobody visits.
                AgePreset.HALLS -> grounded(
                    AgeComposition(
                        terrains = listOf(Terrain.OVERWORLD),
                        seas = listOf(Sea.WATER),
                        carvers = listOf(Carvers.CAVES),
                        underground = Underground.GREAT_HALLS,
                    ),
                )
            }
            return AgeWorld.Composed(composition)
        }

        /**
         * [composition] with its biomes pinned to agree with its shape — see `Biomes.FOOTING`.
         *
         * A preset's own call rather than a default, because being *ungrounded* is a lever this mod means
         * to keep: an Age whose biomes ignore its land is allowed, and `shattered` wants exactly that.
         */
        private fun grounded(composition: AgeComposition): AgeComposition = composition.copy(
            options = composition.options.with(
                Aspect.BIOMES,
                listOf(Options(mapOf(Biomes.FOOTING.name to listOf(Biomes.GROUNDED_FOOTING)))),
            ),
        )

        /**
         * The Spire, pinned: weathered island spires over its green sea, under its own sky, nothing growing.
         *
         * The single `only` claim on the empty `agesandtheart:plasma` biome is load-bearing — it is what stops
         * decoration, mob spawning, biome-driven carving and the surface skin, and it carries the green water
         * colour. Remove it and all five come back.
         */
        private fun spire() = AgeComposition(
            terrains = listOf(Terrain.SPIRE_ISLANDS),
            seas = listOf(Sea.WATER),
            carvers = listOf(Carvers.SOLID),
            sky = Sky.SPIRE,
            options = AspectOptions()
                .with(
                    Aspect.STRUCTURES,
                    // Nobody built here. The `only plasma` claim below would strand every set anyway, but
                    // the Spire says so outright rather than relying on a side effect of its biome.
                    listOf(Options(mapOf(Structures.BUILT.name to listOf(Structures.NOTHING)))),
                )
                .with(
                    Aspect.BIOMES,
                    // Exclusive, so vanilla's table is dropped rather than added to. **Spelled by [Claim]
                    // and never by hand**: this was written out as `plasma{only}` when braces were the
                    // marks, survived the move to brackets unnoticed, and left the Spire asking for a biome
                    // literally named `agesandtheart:plasma{only}` — which nothing is.
                    listOf(
                        Options(
                            mapOf(
                                Biomes.GROWN.name to listOf(
                                    Claim(AgeGeneration.PLASMA_BIOME.toString(), Polarity.ONLY).spelled(),
                                ),
                            ),
                        ),
                    ),
                )
                .with(
                    Aspect.SURFACE,
                    // The plasma biome kills decoration but not the surface rule, whose grass-over-dirt
                    // default is not biome-gated — so the Spire wears no skin and its own rock shows.
                    listOf(Options(mapOf(Surface.MATERIAL.name to listOf(BARE_GROUND)))),
                )
                .with(
                    Aspect.TERRAIN,
                    // Three rocks mingled on a 3D noise rather than banded by height, which the old
                    // generator did. Banding would need height ranges on `TerrainFill` or a per-preset palette.
                    listOf(
                        Options(
                            mapOf(
                                Terrain.STONE.name to SPIRE_ROCKS,
                                // Speckled at block scale rather than in blotches: one mottled stone.
                                // The floor of the axis, which is also where an unsaid one sits — stated
                                // anyway, because a bespoke recipe should say what it wants of its rock.
                                Terrain.MINGLING.name to listOf(Span.at(Span.NATURAL_LEAST).spelled()),
                            ),
                        ),
                    ),
                ),
        )

        /** How a recipe says the ground wears nothing: air, exactly as `open` says the sea is nothing. */
        private const val BARE_GROUND = "minecraft:air"

        /** The Spire's rock, in the order the old generator layered it: deepest first. */
        private val SPIRE_ROCKS = listOf("minecraft:basalt", "minecraft:blackstone", "minecraft:gravel")

        private fun pyramids(arrangement: String) = AgeComposition(
            terrains = listOf(Terrain.PYRAMIDS),
            options = AspectOptions().with(
                Aspect.TERRAIN,
                listOf(Options(mapOf(Terrain.ARRANGEMENT.name to listOf(arrangement)))),
            ),
        )
    }
}
