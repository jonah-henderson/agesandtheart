package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.material.MaterialRuleContext
import net.minecraft.world.level.levelgen.material.MaterialRules
import net.minecraft.world.level.levelgen.material.condition.ConditionEvaluator
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition
import net.minecraft.world.level.levelgen.material.rule.MaterialRule

/**
 * True where this column belongs to one particular territory — **how a surface is painted by region.**
 *
 * **A condition rather than a rule**, which is the sibling of the thing it replaces: vanilla paints by
 * biome with `MaterialRules.isBiome`, also a condition, and a territory is the same kind of fact about a
 * place. That makes it one term among the rest of the vocabulary — `steep`, `hole`, `temperature`,
 * `yBlockCheck`, a noise threshold — rather than a split above all of them, so whatever two territories
 * share is written once instead of once per territory.
 *
 * **Not keyed on biome**, because presets cannot be expected to own unique biomes — two may want the same
 * one, and players will eventually choose biomes themselves. Splitting by region means two presets can
 * resolve to the very same biome and still paint differently.
 *
 * The memo is the same one [NearTheSurface] keeps and for the same reason: a territory is a fact about a
 * *column*, and a surface is built column by column with this asked at every block of one. One entry is
 * enough because the walk never leaves a column until it is done with it.
 */
data class InRegion(val map: RegionMap, val member: Int) : MaterialCondition {

    override fun compile(context: MaterialRuleContext): ConditionEvaluator = InThisColumn(context)

    override fun codec(): MapCodec<out MaterialCondition> = CODEC

    private inner class InThisColumn(private val context: MaterialRuleContext) : ConditionEvaluator {
        private var knownX = Int.MIN_VALUE
        private var knownZ = Int.MIN_VALUE
        private var here = false

        override fun test(): Boolean {
            val blockX = context.blockX()
            val blockZ = context.blockZ()
            if (blockX != knownX || blockZ != knownZ) {
                knownX = blockX
                knownZ = blockZ
                here = map.memberAt(blockX, blockZ) == member
            }
            return here
        }
    }

    companion object {
        val CODEC: MapCodec<InRegion> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                RegionMap.MAP_CODEC.forGetter(InRegion::map),
                Codec.INT.fieldOf("member").forGetter(InRegion::member),
            ).apply(instance, ::InRegion)
        }

        val ID: Identifier = "in_region".location()

        /**
         * [members] painted by territory, or the single rule itself when there is nothing to divide.
         *
         * **A territory with no rule of its own falls through rather than borrowing its neighbour's.**
         * `RegionMap.memberAt` only ever answers inside `0..<members`, so this can only happen where the
         * caller passed fewer rules than the map has territories — and a column the sequence declines is
         * left to whatever stands after it, which shows up as the wrong *skin* rather than as a
         * neighbouring territory's paint bleeding somewhere it was never meant to reach.
         */
        fun paintedByTerritory(members: List<MaterialRule>, map: RegionMap): MaterialRule =
            members.singleOrNull() ?: MaterialRules.sequence(
                members.mapIndexed { member, rule -> MaterialRules.ifTrue(InRegion(map, member), rule) },
            )
    }
}
