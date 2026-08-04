package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Sea
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
) {
    /** The composition this Age was assembled from, or null for the few that are not assembled. */
    val composition: AgeComposition? get() = (world as? AgeWorld.Composed)?.composition

    override fun toString(): String = buildString {
        if (words.isNotEmpty()) append("\"${words.joinToString(" ")}\" → ")
        append("$world seed=$seed")
        if (!instability.isCoherent) append(" [${instability.index}]")
    }

    companion object {
        /**
         * Bumped by hand whenever a change to generation would make the same recipe produce different
         * terrain. What moved at each version: `notes/generator-versions.md`.
         */
        const val CURRENT_GENERATOR_VERSION = 20

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
            ).apply(instance) { world, legacyPreset, seed, version, character, instability, words ->
                AgeRecipe(
                    world.orElseGet { worldFor(legacyPreset.orElse(AgePreset.SPIRE)) },
                    seed,
                    character,
                    instability,
                    words,
                    version,
                )
            }
        }

        val CODEC: Codec<AgeRecipe> = MAP_CODEC.codec()

        /** A fresh recipe for one of the classic demo presets. */
        fun of(preset: AgePreset, id: Identifier): AgeRecipe =
            AgeRecipe(worldFor(preset), seedFor(id))

        /** A fresh recipe, with its character drawn from [seed] and the world [server] is running. */
        fun written(server: MinecraftServer, world: AgeWorld, seed: Long): AgeRecipe =
            AgeRecipe(world, seed, AgeCharacter.drawn(server, seed))

        /** A fresh recipe for an Age somebody wrote: the resolved composition, plus words and instability. */
        fun written(server: MinecraftServer, resolution: Resolution, seed: Long): AgeRecipe = AgeRecipe(
            AgeWorld.Composed(resolution.composition),
            seed,
            AgeCharacter.drawn(server, seed),
            resolution.instability,
            resolution.sentence,
        )

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
                        options = AspectOptions().with(
                            Aspect.TERRAIN,
                            listOf(Options(mapOf(Terrain.UNDERGROUND.name to listOf(Terrain.GREAT_HALLS)))),
                        ),
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
            // Nobody built here. The `only plasma` claim below would strand every set anyway, but the
            // Spire says so outright rather than relying on a side effect of its biome.
            structures = Structures.NONE,
            sky = Sky.SPIRE,
            options = AspectOptions()
                .with(
                    Aspect.BIOMES,
                    // `!` is `only` (see `Claim`): exclusive, so vanilla's table is dropped rather than added to.
                    listOf(
                        Options(
                            mapOf(
                                Biomes.GROWN.name to listOf("!${AgeGeneration.PLASMA_BIOME}"),
                                // The plasma biome kills decoration but not the surface rule, whose
                                // grass-over-dirt default is not biome-gated. See `Biomes.paletteIn`.
                                Biomes.SKIN.name to listOf("bare"),
                            ),
                        ),
                    ),
                )
                .with(
                    Aspect.TERRAIN,
                    // Three rocks mingled on a 3D noise rather than banded by height, which the old
                    // generator did. Banding would need height ranges on `Substance` or a per-preset palette.
                    listOf(
                        Options(
                            mapOf(
                                Terrain.STONE.name to SPIRE_ROCKS,
                                // Speckled at block scale rather than in blotches: one mottled stone.
                                Terrain.MINGLING.name to listOf("fine"),
                            ),
                        ),
                    ),
                ),
        )

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
