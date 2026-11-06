package co.voik.agesandtheart.generation

import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.structure.Structure
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a `varied structures` Age lets each structure stand: its own biomes, and those of the Age's biomes with a
 * climate like one of them ([ClimateNeighbours]) — a mansion in any cool forest, a desert temple in any dry country.
 * Answered off a thread-local the Age's generator sets around its own structure starts, and asked through
 * [IncongruousStructures.biomesFor], so anywhere else it costs one read.
 */
object LooserHomes {

    /** One Age's homes, worked out once per structure. */
    class Homes(biomes: Collection<Holder<Biome>>, variety: Double) {
        private val possible = biomes.toList()
        private val neighbours = ClimateNeighbours(possible, (variety * WARMTH_APART_AT_MOST).toFloat())
        private val workedOut = ConcurrentHashMap<Structure, HolderSet<Biome>>()

        fun of(structure: Structure): HolderSet<Biome> = workedOut.computeIfAbsent(structure) {
            val own = possible.filter(structure.biomes()::contains)
            HolderSet.direct((own + own.flatMap(neighbours::of)).distinct())
        }
    }

    private val homes = ThreadLocal<Homes?>()

    /** Runs [starting] with this thread letting structures stand in [these] homes. */
    fun starting(these: Homes?, starting: () -> Unit) {
        if (these == null) return starting()
        val was = homes.get()
        homes.set(these)
        try {
            starting()
        } finally {
            homes.set(was)
        }
    }

    /** The biomes [structure] may stand in while [starting], or null to leave it its own. */
    fun biomesFor(structure: Structure): HolderSet<Biome>? = homes.get()?.of(structure)

    /** How much warmer or colder a home's neighbour may be at the most variety, on vanilla's temperature. */
    private const val WARMTH_APART_AT_MOST = 0.4
}
