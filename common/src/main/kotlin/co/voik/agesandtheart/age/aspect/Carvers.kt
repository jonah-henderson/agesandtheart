package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.location
import co.voik.agesandtheart.worldgen.carver.Porosity
import co.voik.agesandtheart.worldgen.carver.Weathering
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.WaterTable
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver

/**
 * What is going on beneath the surface: what has been cut back out of the rock, and where water stands
 * inside it.
 *
 * The two belong together because they are answers to the same question. Carving a cave system and
 * then flooding it to the waterline gives you a drowned Age nobody can walk; carving one and leaving it
 * dry gives you the thing players actually go underground for. So a carving preset names both, and
 * the pairing cannot be got wrong by composing carelessly.
 *
 * Carvers are named here rather than inherited from a biome deliberately — field Ages sit on biomes
 * that carry none, and an Age already describes its whole world as replayable data, so its carvers
 * belong in the recipe. See [co.voik.agesandtheart.worldgen.AgeChunkGenerator.applyCarvers].
 */
enum class Carvers(override val key: String) : AspectPreset {
    /**
     * Solid rock. Whatever the shape laid down stays there.
     *
     * **The empty set, and therefore the identity of the union** (Jonah's call, design §3.4). Carving is
     * populative: every seated carving's carvers run, so `solid` names no members and `solid ∪ caves` is
     * `caves`. That is correct set semantics rather than a word being dropped — "nothing is cut" contributes
     * nothing to cut.
     *
     * **The known gap it leaves:** there is no way to say *"caves here, solid ground there"* any more, which
     * the old per-chunk selection could express by accident. That is a claim about *absence in a place*, and
     * absence is what `except` is for — a grammar modifier (Phase 4), not a preset. Until then, a sentence
     * naming both gets the caves and `solid` is inert. Recorded rather than worked around, because the
     * workaround would be reinstating a selection the union is better than.
     */
    SOLID("solid"),

    /**
     * Rock riddled with small pockets, wet through near the surface and dry far below — isolated voids you
     * break into rather than tunnels you walk along.
     *
     * **This did nothing at all until 2026-07-27**, and the way it failed is worth keeping: it named no
     * carvers, on the reading that porosity is about where water *stands* rather than about cutting. But an
     * Age's water table is only ever consulted while something is being carved, so the table it defined was
     * never asked a question and the preset was byte-identical to [SOLID]. Giving it a cut ([Porosity]) is
     * what makes both halves of the description true at once.
     */
    POROUS("porous"),

    /** Vanilla's caves and canyons, running mostly dry, with flooded pockets where the rock is wet. */
    CAVES("caves"),

    /** The same caves, but the water table sits at the sea, so everything below it floods. */
    FLOODED_CAVES("flooded_caves"),

    /** Our own wind erosion — the pass that pares blocky masses back to ribs and spires. */
    WEATHERED("weathered"),
    ;

    override val aspect = Aspect.CARVERS

    override fun getSerializedName(): String = key

    /** The carvers this preset runs, resolved from the registry the Age is being opened against. */
    fun configuredCarvers(server: MinecraftServer): Map<GenerationStep.Carving, HolderSet<ConfiguredWorldCarver<*>>> {
        val configured = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_CARVER)
        val keys = when (this) {
            SOLID -> return emptyMap()
            CAVES, FLOODED_CAVES -> UNDERGROUND_CARVERS.map(::vanillaCarver)
            // **Weathering is not a carver any more, and this empty return is the whole of that move.** It is a
            // pure positional predicate, not a walk; it wants to run before the surface is painted, not after;
            // it needs no cross-chunk mask; and `replaceable` fenced it off from the very rock a writer had put
            // in the ground. It is subtracted from the *shape* now — see [weathering] and `Weathered`.
            WEATHERED -> return emptyMap()
            // Small vugs rather than tunnels, which is what gives this preset's water table something to
            // stand in — see `Porosity`. Without a cut it was byte-identical to SOLID.
            POROUS -> listOf(ResourceKey.create(Registries.CONFIGURED_CARVER, POROSITY))
        }
        return mapOf(GenerationStep.Carving.AIR to HolderSet.direct(keys.map(configured::getOrThrow)))
    }

    /**
     * Where water stands in the rock. Null means "a flat table at the sea's own level", which is what a
     * world with nothing hollow underneath wants anyway.
     *
     * [seed] varies the table per Age so two Ages are not wet in the same places.
     */
    /**
     * The wind that wears this carving's rock away, or null for one that does no weathering.
     *
     * Read by `AgeGeneration`, which subtracts it from the Age's shape rather than handing it to the carving
     * pipeline. Kept on **Carvers** rather than moved to Terrain so that `weathered` stays one word a writer can
     * say about any terrain, and so no vocabulary moves: what changed is the mechanism, not the meaning.
     *
     * **And that home is temporary by agreement** (Jonah, 2026-07-29): *"weathered on Carvers is fine for now, but
     * eventually, we will make Terrain smart enough to allow the player to compose primitives together."* When that
     * lands, weathering is a terrain operation and this method retires — no code change is needed for the
     * capability, because `Weathered` is already an ordinary `FieldKind`. Only the vocabulary is holding it here.
     * See `notes/terrain-architecture.md`, "Where this is going".
     */
    fun weathering(): Weathering? = if (this == WEATHERED) Weathering.SPIRE else null

    fun waterTable(seaFill: SeaFill, seed: Long): WaterTable? = when (this) {
        SOLID, WEATHERED -> null
        // Dry enough to walk, with wet pockets — the reason to go underground at all. A world with no
        // sea has no waterline to hang a table on, and wants none: nothing was going to flood.
        CAVES, POROUS -> seaFill.surfaceY?.let { WaterTable.matching(seaFill, seaLevel = seaFill.level, seed = seed) }
        // What this preset's name has always promised and never delivered: it used to return no table at
        // all, and the generator's substitute for an absent one is the same wandering table CAVES gets — so
        // the two differed by a noise seed and nothing else.
        FLOODED_CAVES -> seaFill.surfaceY?.let {
            WaterTable.matching(seaFill, seaLevel = seaFill.level, seed = seed).copy(floods = true)
        }
    }

    companion object {
        private val UNDERGROUND_CARVERS = listOf("cave", "cave_extra_underground", "canyon")
        private val POROSITY: ResourceLocation = "porosity".location()

        private fun vanillaCarver(name: String): ResourceKey<ConfiguredWorldCarver<*>> =
            ResourceKey.create(Registries.CONFIGURED_CARVER, ResourceLocation.withDefaultNamespace(name))
    }
}
