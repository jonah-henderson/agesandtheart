package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.AmbientMedium
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks

/**
 * The Spire *shape* re-expressed as a Tier-A field tree — the first proof that the analytic-span
 * contract survives a non-trivial shape (a flattish body plus tapering spires above and below).
 *
 * This is deliberately a single hand-placed island, not the full scattered archipelago: it tests
 * shape composition (ellipsoid ∪ cones), which is the span-contract question. Generalising to the
 * scattered, per-island-randomised archipelago is the *instancing* layer, a separate next step.
 * The bespoke [SpireChunkGenerator] is untouched and stays as its own preset.
 */
object SpireField {

    /** One island centred on the origin: a walkable lens with stalagmite and stalactite spires. */
    fun island(): TerrainField {
        val centerY = 150
        val bodyTop = centerY + 13
        val bodyBottom = centerY - 13

        val body = Ellipsoid(centerX = 0, centerY = centerY, centerZ = 0, radiusXZ = 100.0, radiusY = 32.0)
        val upSpires = listOf(
            Cone(baseX = -30, baseZ = 10, baseRadius = 18.0, baseY = bodyTop, tipY = bodyTop + 70),
            Cone(baseX = 25, baseZ = -20, baseRadius = 14.0, baseY = bodyTop, tipY = bodyTop + 55),
        )
        val downSpires = listOf(
            Cone(baseX = 0, baseZ = 0, baseRadius = 24.0, baseY = bodyBottom, tipY = bodyBottom - 120),
            Cone(baseX = -15, baseZ = 20, baseRadius = 16.0, baseY = bodyBottom, tipY = bodyBottom - 80),
        )
        return Union(listOf(body) + upSpires + downSpires)
    }

    fun generator(biomeSource: BiomeSource): FieldChunkGenerator =
        FieldChunkGenerator(biomeSource, island(), AmbientMedium.sea(Blocks.WATER.defaultBlockState(), level = 0))
}
