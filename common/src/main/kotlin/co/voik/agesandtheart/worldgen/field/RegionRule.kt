package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * Paints each territory with its own rules.
 *
 * **Nothing constructs one today — do not delete it as dead code.** Per-territory painting is expected
 * back for the biome pass, and it stays registered in `AgeContent` and covered by `CodecCheck` so it
 * cannot rot silently while it waits. The first two access-widener lines (`SurfaceRules$Context` and
 * `SurfaceRules$SurfaceRule`) are retained on the same reasoning.
 *
 * **A rule source and not a condition**, because a `ConditionSource` is handed a `SurfaceRules.Context`
 * to read its position from, and `Context` has no public members at all. A `RuleSource` only *passes the
 * context through*; the coordinates arrive later as plain arguments to the public
 * [SurfaceRules.SurfaceRule.tryApply].
 *
 * **Not keyed on biome**, because presets cannot be expected to own unique biomes — two may want the same
 * one, and players will eventually choose biomes themselves. Splitting by region means two presets can
 * resolve to the very same biome and still paint differently.
 */
data class RegionRule(
    val members: List<SurfaceRules.RuleSource>,
    val map: RegionMap,
) : SurfaceRules.RuleSource {

    override fun codec(): KeyDispatchDataCodec<out SurfaceRules.RuleSource> = KEY_CODEC

    /**
     * Every member's rule, built once for this column stack, then chosen between per block. All of them
     * rather than the winner's alone, because [apply] runs per chunk column while `tryApply` runs per
     * block — building lazily would re-decide the territory far more often.
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
