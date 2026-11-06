package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import com.mojang.serialization.JsonOps
import net.minecraft.core.registries.Registries
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction
import net.minecraft.world.level.levelgen.material.rule.ConditionRule
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule
import net.minecraft.world.level.levelgen.material.rule.SequenceRule
import java.util.IdentityHashMap

/** Vanilla's rock with its large veins' toggle read stronger, and every rule holding none left as it was. */
@Tags(NEEDS_REGISTRIES)
class RicherVeinsCheck : FunSpec({

    fun ruleOf(key: ResourceKey<NoiseGeneratorSettings>) =
        MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value().materialRule().value()

    fun veinsIn(rule: MaterialRule): List<OreVeinRule> = when (rule) {
        is MaterialRule.HolderHolder -> veinsIn(rule.holder().value())
        is OreVeinRule -> listOf(rule)
        is SequenceRule -> rule.sequence().flatMap(::veinsIn)
        is ConditionRule -> veinsIn(rule.thenRun())
        else -> emptyList()
    }

    fun constantsIn(function: DensityFunction): Set<Float> {
        val found = HashSet<Float>()
        val seen = IdentityHashMap<DensityFunction, DensityFunction>()
        val walk = object : DfRewriteRule {
            override fun rewrite(function: DensityFunction): DensityFunction = seen.getOrPut(function) {
                if (function is ConstantFunction) found += function.value()
                function.rewriteChildren(this)
            }
        }
        walk.rewrite(function)
        return found
    }

    test("a rich underground reads the overworld's vein toggle stronger in both its veins") {
        val scale = RicherVeins.toggleScaleAt(1.0)
        val veins = veinsIn(RicherVeins.of(ruleOf(NoiseGeneratorSettings.OVERWORLD), scale))
        check(veins.size == 2) { "the overworld's rule held ${veins.size} veins, where copper and iron are two" }
        for (vein in veins) {
            check(scale.toFloat() in constantsIn(vein.density())) { "${vein.oreBlock()}'s density does not read the toggle stronger" }
            check(scale.toFloat() in constantsIn(vein.richness())) { "${vein.oreBlock()}'s richness does not read the toggle stronger" }
        }
    }

    test("the rebuilt rule is saved and read back with its veins") {
        val ops = RegistryOps.create(JsonOps.INSTANCE, MinecraftRegistries.worldgen)
        val richer = RicherVeins.of(ruleOf(NoiseGeneratorSettings.OVERWORLD), RicherVeins.toggleScaleAt(1.0))
        val saved = MaterialRule.DIRECT_CODEC.encodeStart(ops, richer).getOrThrow()
        val read = MaterialRule.DIRECT_CODEC.parse(ops, saved).getOrThrow()
        check(veinsIn(read).size == 2) { "the rule read back held ${veinsIn(read).size} veins" }
    }

    test("a rule left as it was where nothing is asked, or there are no veins") {
        val overworld = ruleOf(NoiseGeneratorSettings.OVERWORLD)
        check(RicherVeins.of(overworld, RicherVeins.toggleScaleAt(null)) === overworld) { "an unasked rule was rebuilt" }
        val nether = ruleOf(NoiseGeneratorSettings.NETHER)
        check(RicherVeins.of(nether, RicherVeins.toggleScaleAt(1.0)) === nether) { "the nether, which has no veins, was rebuilt" }
    }
})
