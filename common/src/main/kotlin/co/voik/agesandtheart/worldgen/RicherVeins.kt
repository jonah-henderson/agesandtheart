package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule
import net.minecraft.world.level.levelgen.material.rule.ConditionRule
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule
import net.minecraft.world.level.levelgen.material.rule.SequenceRule
import java.util.IdentityHashMap

/**
 * Vanilla's rock with its large ore veins come oftener, for a `rich` underground.
 *
 * In 26.3 a vein is a material rule, `minecraft:ore_vein`, deciding by density functions that all read one noise,
 * `overworld/ore_vein/toggle`: a vein stands where it is far enough from nought, and is richer the further. Each vein
 * rule is rebuilt with that toggle read `toggleScale` times stronger, so more of the rock is far enough and more of
 * what is is rich. A rule holding no vein, or a scale of one, comes back as it was.
 */
object RicherVeins {

    fun of(rule: MaterialRule, toggleScale: Double): MaterialRule =
        if (toggleScale == UNCHANGED) rule else Rebuilding(Strengthening(toggleScale)).rule(rule)

    /** How strongly the toggle reads where [veins] is asked: vanilla's at nought, [STRONGEST] times at one. */
    fun toggleScaleAt(veins: Double?): Double =
        if (veins == null) UNCHANGED else 1.0 + veins.coerceIn(0.0, 1.0) * (STRONGEST - 1.0)

    /** Walks a rule tree, rebuilding only what holds a vein, so every untouched arm keeps its reference. */
    private class Rebuilding(private val strengthening: Strengthening) {
        fun rule(rule: MaterialRule): MaterialRule = when (rule) {
            is MaterialRule.HolderHolder -> {
                val named = rule.holder().value()
                val rebuilt = rule(named)
                if (rebuilt === named) rule else rebuilt
            }
            is OreVeinRule -> OreVeinRule(
                rule.oreBlock(),
                rule.rawOreBlock(),
                rule.fillerBlock(),
                rule.rawOreChance(),
                strengthening.rewrite(rule.density()),
                strengthening.rewrite(rule.richness()),
                rule.fillerGap(),
            )
            is SequenceRule -> {
                val arms = rule.sequence().map(::rule)
                val nothingChanged = arms.indices.all { arms[it] === rule.sequence()[it] }
                if (nothingChanged) rule else SequenceRule(arms)
            }
            is ConditionRule -> {
                val inner = rule(rule.thenRun())
                if (inner === rule.thenRun()) rule else ConditionRule(rule.ifTrue(), inner)
            }
            else -> rule
        }
    }

    /** Multiplies the toggle wherever a function reads it, remembered by identity as the tree reaches it often. */
    private class Strengthening(private val toggleScale: Double) : DfRewriteRule {
        private val rebuilt = IdentityHashMap<DensityFunction, DensityFunction>()

        override fun rewrite(function: DensityFunction): DensityFunction {
            rebuilt[function]?.let { return it }
            val result =
                if (isTheToggle(function)) DensityFunctions.mul(DensityFunctions.constant(toggleScale.toFloat()), function)
                else function.rewriteChildren(this)
            rebuilt[function] = result
            return result
        }

        private fun isTheToggle(function: DensityFunction): Boolean {
            val holder = (function as? DensityFunctions.HolderHolder)?.function() ?: return false
            return holder.unwrapKey().map { it.identifier().path.endsWith(TOGGLE) }.orElse(false)
        }
    }

    private const val TOGGLE = "ore_vein/toggle"
    private const val UNCHANGED = 1.0

    /** How much stronger the toggle reads at the far end of the dial. */
    private const val STRONGEST = 1.8
}
