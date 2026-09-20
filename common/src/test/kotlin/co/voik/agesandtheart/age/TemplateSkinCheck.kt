package co.voik.agesandtheart.age

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import co.voik.agesandtheart.MinecraftRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * **That a world's own skin is patches over its rock, and not a repaint of it.**
 *
 * Substituting `defaultBlock` is how a book changes what a world we did not lay is made of, and it shows
 * only where the surface tree paints patches and lets the rest decline. Two of vanilla's three do not:
 * `SurfaceRuleData.nether()` ends in a bare `NETHERRACK` arm taking every block its conditioned arms did
 * not, and `end()` is one unconditional `ENDSTONE` and nothing else. So a blackstone nether came out
 * netherrack throughout, and a blackstone void end stone (Jonah, 2026-08-25, walked).
 *
 * [SurfacingStrategy.asPatchesOver] takes that tail off. The claim being checked here is the one that makes
 * doing it always safe: **it changes nothing until a rock has been substituted**, because
 * `SurfaceSystem.buildSurface` consults the rule only where `old == this.defaultBlock` and leaves the block
 * as it found it when the rule declines. An arm painting netherrack onto netherrack was already a no-op.
 *
 * The trees are read through their own codec rather than walked. `SurfaceRules` makes its sequence and
 * block records private, and the two access-widener lines that let the strip name them are for building a
 * tree, not for taking one apart in a check — a check that read the tree the way the code does could not
 * catch the code being wrong about the shape.
 */
@Tags(NEEDS_REGISTRIES)
class TemplateSkinCheck : FunSpec({

    /** 26.2\'s surface rules ask for the biome registry; these are vanilla\'s own. */
    val BIOMES = MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME)

    /**
     * Registry-aware ops, because 26.2's surface rules may name biomes: a plain `JsonOps` cannot reach the
     * registry those holders live in and refuses the whole tree.
     */
    val ops = MinecraftRegistries.worldgen.createSerializationContext(JsonOps.INSTANCE)

    fun spelled(rule: SurfaceRules.RuleSource): JsonObject =
        SurfaceRules.RuleSource.CODEC.encodeStart(ops, rule).getOrThrow().asJsonObject

    /**
     * Whether [rule] paints something on every block it is offered.
     *
     * A `block` is unconditional and does. A `sequence` does exactly when its **last** arm does, the
     * earlier ones being alternatives that may decline. Anything else — a `condition` — may decline, and a
     * tree that may decline leaves the bulk to `defaultBlock`.
     */
    fun paintsEveryBlock(rule: JsonObject): Boolean = when (rule.get("type").asString) {
        "minecraft:block" -> true
        "minecraft:sequence" -> rule.getAsJsonArray("sequence").lastOrNull()
            ?.let { paintsEveryBlock(it.asJsonObject) } == true
        else -> false
    }

    /** How many arms a tree offers, at every depth — what a strip must not otherwise disturb. */
    fun arms(rule: JsonObject): Int = when (rule.get("type").asString) {
        "minecraft:sequence" -> rule.getAsJsonArray("sequence").sumOf { arms(it.asJsonObject) }
        else -> 1
    }

    /**
     * The control, and it has to come first: if no template's tree repainted the rock there would be
     * nothing here to fix, and every assertion below would pass over three trees already patch-shaped.
     */
    test("two of vanilla's three worlds do paint over every block of their rock") {
        val repainting = AgeTemplate.entries.filter { paintsEveryBlock(spelled(it.skin(BIOMES))) }
        check(repainting.map { it.key } == listOf("infernal", "dark_void")) {
            "the worlds whose skin paints every block are ${repainting.map { it.key }}, and the strip was " +
                "written for the nether and the End"
        }
    }

    test("and stripped of their last arm, none of the three does") {
        for (template in AgeTemplate.entries) {
            val patches = SurfacingStrategy.asPatchesOver(template.skin(BIOMES))
            check(!paintsEveryBlock(spelled(patches))) {
                "${template.key}'s skin still paints every block, so a rock named for it cannot be seen"
            }
        }
    }

    /**
     * **And it takes off exactly one arm.** A strip that removed a conditioned arm would cost the nether
     * its soul soil, its gravel and its basalt — which is the failure a "no netherrack anywhere" check
     * would happily pass.
     */
    test("and loses nothing but that arm") {
        for (template in AgeTemplate.entries) {
            val tree = spelled(template.skin(BIOMES))
            val stripped = SurfacingStrategy.asPatchesOver(template.skin(BIOMES))
            // A world whose whole tree was one unconditional block — the End's — has no conditioned arm to
            // keep, so what is left is the rule that never matches rather than a shorter sequence.
            if (tree.get("type").asString == "minecraft:block") {
                check(stripped == SurfacingStrategy.SUPPRESSED) {
                    "${template.key}'s skin is one block and stripping it gave ${spelled(stripped)}"
                }
                continue
            }
            val before = arms(tree)
            val after = arms(spelled(stripped))
            val expected = if (paintsEveryBlock(tree)) before - 1 else before
            check(after == expected) {
                "${template.key} went from $before arms to $after, where $expected was the whole of it"
            }
        }
    }

    /** The overworld's tree already paints patches, so nothing may happen to it at all. */
    test("and a world that already painted patches is untouched") {
        val overworld = AgeTemplate.OVERWORLD.skin(BIOMES)
        check(spelled(SurfacingStrategy.asPatchesOver(overworld)) == spelled(overworld)) {
            "the overworld's own tree was rewritten, and it had no tail to take off"
        }
    }
})
