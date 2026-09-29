package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * Divides the world's *rock* between several shapes, so an Age can be two terrains at once — the
 * machinery behind set-valued aspects (design §3.4), where a contradiction is satisfied **by coexistence**
 * rather than by one term winning and the other going silently missing.
 *
 * **A member is asked for a column only when it wins it**, so this costs one map lookup plus one winner's
 * evaluation, not the sum of everything the Age could have been.
 */
data class Regions(
    val members: List<TerrainField>,
    val map: RegionMap,
) : TerrainField {
    override val kind = FieldKind.REGIONS

    // Territories cover the world, so this is never a sensible instancing template.
    override val horizontalReach = Double.POSITIVE_INFINITY

    // The map's own claims, plus whatever the winner costs. The winner is unknown here, so the dearest
    // member is the honest answer for an ordering hint.
    override val samplesPerColumn = members.size + (members.maxOfOrNull { it.samplesPerColumn } ?: 0)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val chosen = members.getOrNull(map.memberAt(worldX, worldZ)) ?: return Spans.EMPTY
        return chosen.columnSpans(worldX, worldZ)
    }

    override fun resized(factor: Double, pivotY: Int) =
        Regions(members.map { it.resized(factor, pivotY) }, map.resized(factor))

    companion object {
        /** [members] divided by territory, or the single field itself when there is nothing to divide. */
        fun of(members: List<TerrainField>, map: RegionMap): TerrainField =
            members.singleOrNull() ?: Regions(members, map)

        /**
         * The map is **nested under a key**, as [CellCanyon] nests its own: a `RegionMap` writes a `members`
         * count, and inlined beside this node's `members` list the two share a name and nothing reads back.
         */
        fun codec(self: Codec<TerrainField>): MapCodec<Regions> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                self.listOf().fieldOf("members").forGetter(Regions::members),
                RegionMap.MAP_CODEC.codec().fieldOf("map").forGetter(Regions::map),
            ).apply(instance, ::Regions)
        }
    }
}
