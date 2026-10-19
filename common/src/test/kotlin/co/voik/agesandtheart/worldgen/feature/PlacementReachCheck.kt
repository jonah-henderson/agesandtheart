package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.placement.InSquarePlacement
import net.minecraft.world.level.levelgen.placement.OffsetPlacement
import net.minecraft.world.level.levelgen.placement.PlacementModifier

/**
 * No placement, however a size reshapes it, moves a position so far from the chunk being decorated that reading its
 * biome asks for a chunk two away — which crashes generation, since the features step holds only the chunks next door.
 */
@Tags(NEEDS_REGISTRIES)
class PlacementReachCheck : FunSpec({

    /** The farthest west and east, from the decorated chunk's own west edge, that [placement] can move a position. */
    fun horizontalReach(placement: List<PlacementModifier>): IntRange =
        placement.fold(0..0) { reach, modifier ->
            when (modifier) {
                is InSquarePlacement -> reach.first..(reach.last + LAST_IN_CHUNK)
                is OffsetPlacement -> {
                    val west = minOf(modifier.x().minInclusive(), modifier.z().minInclusive())
                    val east = maxOf(modifier.x().maxInclusive(), modifier.z().maxInclusive())
                    (reach.first + west)..(reach.last + east)
                }
                else -> reach
            }
        }

    test("every shipped placement, at every size, reads biomes only in the chunks next door") {
        val registries = MinecraftRegistries.worldgen
        val placedFeatures = registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList()
        check(placedFeatures.isNotEmpty()) { "no placed features to measure" }
        val tooFar = placedFeatures.flatMap { placed ->
            SIZES.mapNotNull { size ->
                val reshaped = FeatureShape.reshaped(placed, size, null, null, emptyList(), registries)
                val reach = horizontalReach(reshaped.value().placement())
                val staysWithinBiomeReads = reach.first >= WESTMOST_BIOME_READ && reach.last <= EASTMOST_BIOME_READ
                if (staysWithinBiomeReads) null else "${placed.key().identifier()} at size $size reaches $reach"
            }
        }
        check(tooFar.isEmpty()) {
            "these reach past $WESTMOST_BIOME_READ..$EASTMOST_BIOME_READ, where a biome read leaves the chunks next door: $tooFar"
        }
    }
}) {
    private companion object {
        /** What `InSquarePlacement` adds at most, east and south. */
        const val LAST_IN_CHUNK = 15

        /** A biome read at a block blends cells from two blocks west of it, so the chunk next door's edge is two in. */
        const val WESTMOST_BIOME_READ = -14

        /** ...and from cells up to a block and a quarter-cell east, so its far edge is two short. */
        const val EASTMOST_BIOME_READ = 29

        val SIZES = listOf(null, -1.0, -0.5, 0.5, 1.0)
    }
}
