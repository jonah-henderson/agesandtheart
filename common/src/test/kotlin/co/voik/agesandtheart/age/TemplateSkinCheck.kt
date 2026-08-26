package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * **Whether a rock a book names can be seen through the skin the world lays over it.**
 *
 * A book changes what a world we did not lay is made of by substituting `defaultBlock`, and that only
 * shows where the world's surface tree paints *patches* and leaves the bulk alone. The overworld's does.
 * The nether's ends in a bare `NETHERRACK` arm that takes whatever its conditioned arms did not, and the
 * End's whole tree is one unconditional `ENDSTONE` — so both paint over the substitution entirely, and a
 * blackstone nether came out netherrack with nothing said about it (Jonah, 2026-08-25, walked, twice: the
 * first reading was that the rock was merely under the topsoil and would show in a cave, which is true of
 * the overworld and of neither of the others).
 *
 * [AgeTemplate.skinRepaintsTheWholeRock] is written down because the tree cannot be walked — `SurfaceRules`
 * makes both its sequence and its block records private. It can be *read*, though: every rule source has a
 * codec, so this follows the encoded tree down its last arm and asks whether what it ends in is a block
 * with no condition on it. That is the same claim, checked against the thing it describes.
 */
@Tags(NEEDS_REGISTRIES)
class TemplateSkinCheck : FunSpec({

    fun spelled(rule: SurfaceRules.RuleSource): JsonObject =
        SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow().asJsonObject

    /**
     * Whether [rule] paints something on every solid block it is offered.
     *
     * A `block` is unconditional and does. A `sequence` does exactly when its **last** arm does, earlier
     * arms being alternatives that may not match. Anything else — a `condition`, in practice — may decline,
     * and a tree that may decline leaves the bulk to `defaultBlock`.
     */
    fun paintsEverything(rule: JsonObject): Boolean = when (rule.get("type").asString) {
        "minecraft:block" -> true
        "minecraft:sequence" -> rule.getAsJsonArray("sequence").last().asJsonObject.let(::paintsEverything)
        else -> false
    }

    test("each template says truly whether its skin paints over a rock a book named") {
        MinecraftRegistries.ensureStoodUp()
        for (template in AgeTemplate.entries) {
            val paints = paintsEverything(spelled(template.skin))
            check(paints == template.skinRepaintsTheWholeRock) {
                "${template.key} says skinRepaintsTheWholeRock=${template.skinRepaintsTheWholeRock} " +
                    "where its own tree ${if (paints) "does" else "does not"} paint every block"
            }
        }
    }

    /**
     * The control. All three answering the same way would make the reading above unfalsifiable — and it is
     * the *difference* between them that a writer runs into, so it is the thing worth pinning.
     */
    test("and the three worlds do not all answer alike") {
        MinecraftRegistries.ensureStoodUp()
        val answers = AgeTemplate.entries.map { it.skinRepaintsTheWholeRock }.distinct()
        check(answers.size > 1) { "every template paints the same way, so nothing here distinguishes them" }
    }

    /**
     * **And a book naming a rock those two cannot show is told so**, rather than being given netherrack and
     * left to wonder. Silent where the book also named a skin, which replaces the tree that was painting
     * over it.
     */
    test("a rock that will be painted over is reported, and a skin makes it visible again") {
        MinecraftRegistries.ensureStoodUp()
        fun reportOn(template: AgeTemplate, said: String): List<String> {
            val composition = AgeComposition.parse("template=${template.key} landmass=vanilla $said")
                .getOrElse { error("'$said' over ${template.key} is not a composition this build parses: $it") }
            return AgeRecipe(AgeWorld.Composed(composition), SOME_SEED, template = template).unhonoured
        }

        fun mentionsThePainting(report: List<String>) = report.any { "paints every block" in it }

        val rockAlone = "landmass.stone=$BLACKSTONE"
        val rockAndSkin = "$rockAlone surface.material=$BLACKSTONE"

        check(mentionsThePainting(reportOn(AgeTemplate.INFERNAL, rockAlone))) {
            "a blackstone nether is given netherrack and told nothing: ${reportOn(AgeTemplate.INFERNAL, rockAlone)}"
        }
        check(mentionsThePainting(reportOn(AgeTemplate.DARK_VOID, rockAlone))) {
            "a blackstone void is given end stone and told nothing: ${reportOn(AgeTemplate.DARK_VOID, rockAlone)}"
        }
        check(!mentionsThePainting(reportOn(AgeTemplate.INFERNAL, rockAndSkin))) {
            "a nether that named its skin too is still told its rock cannot be seen"
        }
        check(!mentionsThePainting(reportOn(AgeTemplate.OVERWORLD, rockAlone))) {
            "an overworld Age is told its rock is painted over, and the overworld's tree paints patches"
        }
    }
})

private const val SOME_SEED = 4242L
private const val BLACKSTONE = "minecraft:blackstone"
