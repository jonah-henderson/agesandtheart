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
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import java.util.Optional

/**
 * What an Age *is*, as data — the description its world is rebuilt from every time it opens.
 *
 * An Age is not a saved world so much as a saved *sentence*: [AgeSavedData] keeps the recipe, and the
 * dimension is regenerated from it on every open, including after a restart. So this type is the mod's
 * most permanent shape, and a recipe already written must keep meaning what it meant.
 *
 * **The resolved recipe is what persists, never the words that produced it** (design §4.6). Words will
 * be kept alongside as provenance, but re-resolving them on load would let a change to tag data or
 * preset tuning silently rewrite somebody's existing world.
 */
data class AgeRecipe(
    val world: AgeWorld,
    val seed: Long,
    /**
     * What this Age is *like*, as opposed to what it is made of — drawn from the seed and the world it
     * was written in, then frozen here. Defaults to [AgeCharacter.LEGACY] so a recipe written before
     * character existed keeps the world it already had.
     */
    val character: AgeCharacter = AgeCharacter.LEGACY,
    /**
     * How far this Age is at odds with itself, and why — resolved once when it was written and kept.
     *
     * Persisted for the same reason the composition is (§4.6): §5's consequences read it long afterwards,
     * and re-deriving it would mean re-running the words through whatever tag data is current, so an
     * edited datapack could quietly make a stable Age unstable. Empty for every Age not written from
     * words, which is every Age that exists before the pen does.
     */
    val instability: Instability = Instability.NONE,
    /**
     * What the book said, as **provenance only** (§4.6). Nothing reads it to decide anything — the
     * composition is what the Age is — but an Age whose recipe cannot say what was written for it could
     * never explain itself to its author.
     */
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
         * terrain.
         *
         * Nothing reads it yet. It is stamped because book editing (design §6.5) reconstructs an Age's
         * *pristine* terrain to work out which blocks a player placed, and that subtraction is only
         * meaningful if the generator has not moved underneath it. An Age carrying no stamp could never
         * be told apart from one that matches, so the stamp has to predate the feature that needs it.
         *
         * **2 — aspects.** The presets that were whole worlds became compositions of parts, and several
         * demo Ages generate differently as a result.
         *
         * **3 — regions.** Terrain became a set, every sea moved to 63, and Spire was retuned around
         * its cloud decks. Nearly every demo Age generates differently.
         *
         * **4 — dressing regions.** Dressing became a set too, and an Age's territory maps now depend on
         * its drawn alignment, so where a seam falls moved even for Ages that name a single dressing.
         *
         * **5 — every positional aspect.** Sea and carving became sets as well, which completes the
         * set: only the sky, being a dimension type, stays singular.
         *
         * **6 — written Ages.** A recipe carries the words it came from and the instability they resolved
         * to. Nothing about generation moved, so no existing Age looks different; the bump is honest
         * anyway, because a version stamp that only moves when terrain moves cannot be used to tell
         * whether a recipe's *own shape* is one this code understands.
         *
         * **7 — shares.** A set-valued aspect's presets no longer divide the world evenly: each carries a
         * [co.voik.agesandtheart.age.aspect.Share], so a strongly-claimed preset takes most of an Age and a
         * weakly-claimed one turns up as scarce islands. Territory boundaries move for every Age that holds
         * more than one preset in a aspect, and blended seams shift very slightly even for those that do not.
         *
         * **8 — the Spire archipelago became two populations.** Its islands are laid out on a far harder
         * jitter and are now free to merge, and a second layer of small noise-cut blobs hangs between the
         * cloud decks. Every Age whose terrain is `spire_islands` generates differently — the shape of an
         * individual big island is untouched, but where they sit is not, and there is new terrain between
         * them. Note the *seam bias* drawn the same day needs no bump: an Age's seam is frozen in its
         * character when it is written, so existing recipes keep the seam they were made with.
         *
         * **9 — the sea aspect opened.** It was called `medium` at the time, and the spellings below are
         * its, not today's — see **13** for the rename. A sea became a block id rather than one of three
         * named presets, so `medium=sea` was written `medium=minecraft:water`. No Age changed shape from
         * that alone, but the candidate pool for the aspect became `preset_tags/` read in sorted key order
         * rather than an enum's declaration order, so an unconstrained sea can draw differently at the
         * same seed.
         *
         * **10 — the carving, three ways.** Carving is now the **union** of every seated carving's
         * carvers rather than one set selected per chunk, so an Age naming two of them gets both throughout
         * and the carver *seeds* shift with the list. Hydrology **divides** on the region map instead of one
         * table being drawn for the whole Age. `porous` cuts small vugs where it used to cut nothing at all,
         * and `flooded_caves` floods where it used to differ from `caves` by a noise seed. Every Age with a
         * carving other than `solid` generates differently.
         *
         * **11 — uncut ground.** A carving that cuts nothing anywhere now holds its own territory, in
         * which no carver starts, where 10 let it vanish into the union's identity. Two carving that
         * both cut are unaffected and still union throughout. So only an Age naming `solid` *alongside*
         * something else changes, and it changes into what it always claimed to be: caves here, solid ground
         * there.
         *
         * **12 — structures became an aspect.** `dressing.settlement=vanilla` is now `structures=vanilla`,
         * and the old spelling is no longer a knob any dressing declares, so an Age already written with it
         * keeps the token as an unrecognised option and loses its villages. Nothing about how a set is
         * *placed* moved; what moved is where the Age says it wants one.
         *
         * **13 — the medium aspect became `Sea`.** Nothing generates differently: this is a rename, and the
         * only entry here that changes no shape at all. It earns a number because it breaks recipes rather
         * than moving terrain — the aspect's own key went from `medium` to `sea`, so `medium=minecraft:water`
         * no longer names an aspect and an Age written before this reads as malformed rather than as an Age
         * without a sea. The value spellings moved too: `sea` and `lava` were never needed (both parse as
         * registry ids on their own), and `void` survives alongside a new `none`.
         *
         * The rename came out of dropping an *atmosphere* field — a block standing where air normally stands
         * — which would have made the aspect "everything the rock is not". Without it the aspect is exactly
         * vanilla's `default_fluid` + `sea_level`, which is what it should have been named for all along
         * (design §3.1). The plan's step 7 records why the atmosphere went, including the parts that worked.
         *
         * **14 — erosion moved into the shape, and the Spire floated up.** Three changes that all move rock, so
         * they share a number. `Weathered` wraps the terrain field instead of running as a carver, which alters
         * what erodes (it can now spare an island's middle by how deep the rock stands, and punish a column by
         * how high it reaches) and *when* — before `SURFACE` rather than after, so cuts are skinned. `Substance`
         * gained a mingling scale, pinned fine for the Spire. And `Terrain.ALTITUDE` lifts an archipelago 72
         * blocks, which comes with a per-Age [co.voik.agesandtheart.worldgen.VerticalWindow]: `age_spire`'s band
         * now runs y 0..383 rather than -64..319, since the islands never used the depth and the spires need
         * the ceiling.
         *
         * **15 — the seam became a fault.** `Seam` was four transition *widths* (sheared 85 / keen 9 / soft 4
         * / blurred 2) with softening as an innate property of any territory boundary. It is now the **forms**
         * a fault takes — `rift` 40%, `scarp` 40%, `sheared` 15%, `fuzzed` 5% — so a boundary is a flooded
         * chasm or a cliff four times in five, a plain cut about one time in seven, and dissolves rarely.
         *
         * Every Age with more than one **terrain** generates differently: four in five now get a 64-block
         * scarp or a rift along their seams where they used to get a plain cut. An Age divided only in its
         * sea, carving or climate keeps its old ground unless it drew the fuzzed form, whose width is now a
         * single 0.04 capped at 16 blocks rather than whichever of three it had. An Age with one territory
         * everywhere is untouched.
         *
         * Recipes written at 14 also break rather than bend: `seam: "keen"` names nothing now. That is the
         * intended cost while there are no saves worth keeping, and `terrain.throw` / `terrain.rift` go the
         * same way, replaced by one `terrain.seam`.
         */
        const val CURRENT_GENERATOR_VERSION = 15

        val MAP_CODEC: MapCodec<AgeRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                // Optional only so recipes written before aspects existed still load; [migrated] supplies
                // the world in that case, and nothing writes a recipe without one.
                AgeWorld.MAP_CODEC.codec().optionalFieldOf("world").forGetter { Optional.of(it.world) },
                // Read for migration only. A recipe written today names its world above.
                AgePreset.CODEC.optionalFieldOf("preset").forGetter { Optional.empty<AgePreset>() },
                Codec.LONG.fieldOf("seed").forGetter(AgeRecipe::seed),
                // Required, not defaulted, and deliberately so: `optionalFieldOf(name, default)` omits
                // the field whenever it equals the default, so a recipe written today would be read
                // back claiming whatever version is current when it is *read* — which is exactly the
                // question the stamp exists to answer.
                Codec.INT.fieldOf("generator_version").forGetter(AgeRecipe::generatorVersion),
                // Optional so recipes written before character existed still load, as the knife-edged
                // default-sized Ages they were generated as.
                AgeCharacter.MAP_CODEC.codec().optionalFieldOf("character", AgeCharacter.LEGACY)
                    .forGetter(AgeRecipe::character),
                // Both optional, and absent on every Age not written from words — which is every Age
                // written before the resolver existed, and still every Age made by `/age create`.
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

        /**
         * A fresh recipe for one of the classic demo presets — which now mostly means the composition
         * that describes it. Shares [worldFor] with save migration, so `/age create hills` and an Age
         * written before aspects existed cannot drift apart.
         */
        fun of(preset: AgePreset, id: ResourceLocation): AgeRecipe =
            AgeRecipe(worldFor(preset), seedFor(id))

        /**
         * A fresh recipe, with its character drawn from [seed] and the world [server] is running.
         *
         * The one construction site that can resolve character, because character reads the host
         * world's climate scale — everywhere else builds recipes from data that already has one.
         */
        fun written(server: MinecraftServer, world: AgeWorld, seed: Long): AgeRecipe =
            AgeRecipe(world, seed, AgeCharacter.drawn(server, seed))

        /**
         * A fresh recipe for an Age somebody actually *wrote* — the resolved composition, plus the words
         * and the instability as the record of how it was arrived at.
         *
         * The composition rather than the words is what gets rebuilt from, which is the whole of §4.6:
         * words re-resolved on every open would let a retuned tag shift somebody's beloved world.
         */
        fun written(server: MinecraftServer, resolution: Resolution, seed: Long): AgeRecipe = AgeRecipe(
            AgeWorld.Composed(resolution.composition),
            seed,
            AgeCharacter.drawn(server, seed),
            resolution.instability,
            resolution.sentence,
        )

        /**
         * The seed an Age gets when nothing has chosen one for it.
         *
         * Derived from the id because that is what the Fabric backend did before seeds were recipe
         * data, so every Age written under the old scheme keeps the world it already had. Once books
         * carry words, the seed becomes part of what is written rather than a function of the name.
         */
        fun seedFor(id: ResourceLocation): Long = id.hashCode().toLong()

        /**
         * The world a classic preset names — which is also what a pre-aspects recipe migrates to.
         *
         * Faithful in structure rather than block-for-block: several of these Ages generate differently
         * now, because composing them honestly means giving each aspect the preset that *describes* it
         * rather than reproducing whatever the hand-written bundle happened to do. Jonah's call, on the
         * grounds that all twelve are demos rather than worlds anyone lives in.
         *
         * The three that are not compositions at all stay themselves.
         */
        fun worldFor(preset: AgePreset): AgeWorld {
            val composition = when (preset) {
                AgePreset.VANILLA, AgePreset.VANILLA_BARE -> return AgeWorld.Bespoke(preset)

                // **The Spire is a pinned composition now, and this is the pattern for bespoke Ages in general**
                // (Jonah, 2026-07-29: *"this is how we will want to handle most bespoke, with carefully pinned
                // presets and occasional special exceptions, like the sky/star effects"*). `spire_islands` with
                // everything the aspect system has learned since is closer to his original vision than the
                // imperative `SpireChunkGenerator` ever was, so it supersedes it rather than sitting beside it.
                //
                // `FIELD` was already this composition under another name; both keys now build the one thing, so
                // they cannot drift apart.
                AgePreset.SPIRE, AgePreset.FIELD -> spire()
                AgePreset.PYRAMIDS -> pyramids("grid")
                AgePreset.PYRINGS -> pyramids("rings")
                AgePreset.PYRVARIED -> pyramids("varied")
                AgePreset.SHAPES -> AgeComposition(terrains = listOf(Terrain.SHAPES))
                AgePreset.PILLARS -> AgeComposition(terrains = listOf(Terrain.PILLARS), seas = listOf(Sea.WATER))
                AgePreset.ERODED -> AgeComposition(terrains = listOf(Terrain.ERODED), seas = listOf(Sea.WATER))
                AgePreset.HILLS -> AgeComposition(
                    terrains = listOf(Terrain.HILLS),
                    seas = listOf(Sea.WATER),
                    carvers = listOf(Carvers.CAVES),
                    structures = Structures.VANILLA,
                )
                // Its caves are its shape, so nothing is carved — but the rock still runs wet and dry.
                AgePreset.CAVERNS -> AgeComposition(
                    terrains = listOf(Terrain.CAVERNS),
                    seas = listOf(Sea.WATER),
                    carvers = listOf(Carvers.POROUS),
                )
            }
            return AgeWorld.Composed(composition)
        }

        /**
         * The Spire, pinned: weathered island spires over its green sea, under its own sky, and nothing growing.
         *
         * **Pinning `agesandtheart:plasma` as the Age's *only* biome does four jobs at once**, which is why it is
         * the whole of the recipe rather than a detail of it. That biome is deliberately empty — zero features
         * across all eleven decoration steps, no biome carvers, no spawners — and it carries the green
         * `water_color` the Spire's sea has always had. So one `only` claim buys: **no decoration**, **no mob
         * spawning**, **no biome-driven carving**, and the green sea. Nothing else has to be switched off.
         *
         * `Structures.NONE` is the default and left implicit — an Age says nothing about habitation unless it
         * wants some.
         *
         * **[Sky.SPIRE] is the one special exception**, and the only thing here that is not ordinary composed
         * data: two roiling cloud decks and stars that appear only above them.
         *
         * **And "the surface rule must add nothing" needs no work either, which was the surprise.** Every composed
         * Age gets `Palette.VANILLA_OVERWORLD` unconditionally and there is no way for a preset to name its own —
         * but vanilla's tree is **biome-keyed**, and `agesandtheart:plasma` sits in none of its branches, so every
         * one of them declines and the fill answers. Verified: the spawn column comes out `blackstone`, not
         * `grass_block`. So the featureless biome does this job too, and no palette plumbing was needed.
         *
         * **The one thing genuinely not reproduced is the *banding*.** The old generator laid basalt below y=62,
         * blackstone to 94 and gravel above; this mingles the three on a 3D noise instead. Accepted for now
         * (Jonah), and if it is ever wanted, it needs height ranges on `Substance` or a per-preset palette —
         * `Palette` already has `layers`, `where`, `belowY` and `NOTHING`, so what is missing is only the plumbing
         * to choose one.
         */
        private fun spire() = AgeComposition(
            terrains = listOf(Terrain.SPIRE_ISLANDS),
            seas = listOf(Sea.WATER),
            carvers = listOf(Carvers.WEATHERED),
            sky = Sky.SPIRE,
            options = AspectOptions()
                .with(
                    Aspect.BIOMES,
                    // `!` is `only` — see `Claim`. Exclusive, so vanilla's table is dropped rather than added to.
                    listOf(
                        Options(
                            mapOf(
                                Biomes.GROWN.name to listOf("!${AgeGeneration.PLASMA_BIOME}"),
                                // And **no skin over the rock**: the plasma biome kills decoration but not the
                                // surface rule, whose grass-over-dirt default is not biome-gated. See
                                // `Biomes.paletteIn` — this is the second half of "nothing grows here".
                                Biomes.SKIN.name to listOf("bare"),
                            ),
                        ),
                    ),
                )
                .with(
                    Aspect.TERRAIN,
                    // The old generator's three rocks, **mingled rather than banded by height** — Jonah:
                    // *"mingled blocks are ok as an approximation to the banded stuff for now."* It was
                    // basalt below y=62, blackstone to 94, gravel above; `Substance` mixes its blocks on a
                    // 3D noise instead, which reads as the same palette without the strata. Banding would
                    // need either height ranges on `Substance` or a per-preset surface rule; see below.
                    listOf(
                        Options(
                            mapOf(
                                Terrain.STONE.name to SPIRE_ROCKS,
                                // Speckled at block scale rather than in blotches (Jonah): three rocks that read
                                // as one mottled stone, which is what the old generator's height bands looked like
                                // from a distance.
                                Terrain.MINGLING.name to listOf("fine"),
                                // Floated up so the island tops sit just under the upper cloud deck and only
                                // the central spires break it (Jonah). Safe to pin here and nowhere else
                                // because it is conditioned on the Age's vertical band, and this recipe's sky
                                // is what earns the taller one — see [Terrain.ALTITUDE].
                                Terrain.ALTITUDE.name to listOf("high"),
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
