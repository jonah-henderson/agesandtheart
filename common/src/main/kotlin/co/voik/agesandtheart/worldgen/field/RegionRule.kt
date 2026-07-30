package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * Paints each territory with its own rules.
 *
 * **Nothing constructs one today, and that is deliberate — do not delete it as dead code** (Jonah,
 * 2026-07-29: *"keep it for now, I suspect we may want to make use of surface rules in the future"*). It was
 * built for the **dressing**, which divided the painting; the dressing is deleted, and what the rock *is*
 * moved into the fill as a [Substance] in step 4, so a composed Age now wears one plain palette. Its sibling
 * `RegionBiomeSource` was deleted in step 7 for exactly this reason, and the difference is that per-territory
 * *painting* is expected back: the biome pass wants somewhere to put a crust, and design §9's bare-rock item
 * is still open. It stays registered in `AgeContent` and covered by `:common:codeccheck`, so it cannot rot
 * silently while it waits.
 *
 * It is also what the first two lines of the access widener are for — `SurfaceRules$Context` and
 * `SurfaceRules$SurfaceRule` — so those are retained on the same reasoning, not because anything needs them
 * right now.
 *
 * **Why this is a rule source and not a condition.** The natural way to express "only here" in vanilla's
 * surface system is a `ConditionSource`, and that route is closed: a condition is handed a
 * `SurfaceRules.Context` to read its position from, and `Context` has **no public members at all**, so
 * nothing outside Mojang's own package can ask it where it is without an access widener on every loader.
 *
 * A rule source needs none of that, because of where the coordinates arrive. `RuleSource` is a
 * `Function<Context, SurfaceRule>`, and this one only ever *passes the context through* to its children;
 * it never reads it. The coordinates come later, as plain arguments to
 * [SurfaceRules.SurfaceRule.tryApply] — which is public. So the door Mojang left open is the one further
 * in, and the whole problem dissolves by picking the right extension point.
 *
 * **Why it matters that this is not keyed on biome.** The obvious alternative was to give each dressing
 * its own biomes and let vanilla's biome conditions do the splitting. Jonah rejected it, correctly:
 * presets cannot be expected to own unique biomes, both because two dressings may sensibly want the same
 * one and because **players will eventually choose biomes themselves** — at which point dressing-to-biome
 * stops being a fixed mapping and anything keyed on it breaks. Splitting by *region* instead means two
 * dressings can resolve to the very same biome and still paint differently, so "granite plains beside
 * limestone plains" stays expressible.
 */
data class RegionRule(
    val members: List<SurfaceRules.RuleSource>,
    val map: RegionMap,
) : SurfaceRules.RuleSource {

    override fun codec(): KeyDispatchDataCodec<out SurfaceRules.RuleSource> = KEY_CODEC

    /**
     * Every member's rule, built once for this column stack, then chosen between per block.
     *
     * All of them are built rather than only the winner's, because [apply] is called per chunk column
     * while `tryApply` is called per block: building lazily would mean re-deciding the territory far
     * more often than deciding it here, and a rule is cheap to build and dear to build repeatedly.
     */
    override fun apply(context: SurfaceRules.Context): SurfaceRules.SurfaceRule {
        val rules = members.map { it.apply(context) }
        if (rules.isEmpty()) return SurfaceRules.SurfaceRule { _, _, _ -> null }
        return SurfaceRules.SurfaceRule { x, y, z ->
            rules[map.memberAt(x, z).coerceIn(rules.indices)].tryApply(x, y, z)
        }
    }

    companion object {
        val CODEC: MapCodec<RegionRule> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                SurfaceRules.RuleSource.CODEC.listOf().fieldOf("members").forGetter(RegionRule::members),
                RegionMap.MAP_CODEC.forGetter(RegionRule::map),
            ).apply(instance, ::RegionRule)
        }

        private val KEY_CODEC = KeyDispatchDataCodec.of(CODEC)

        /** [members] painted by territory, or the single rule itself when there is nothing to divide. */
        fun of(members: List<SurfaceRules.RuleSource>, map: RegionMap): SurfaceRules.RuleSource =
            members.singleOrNull() ?: RegionRule(members, map)
    }
}
