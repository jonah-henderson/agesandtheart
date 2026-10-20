package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Structures
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a clause closing on members of a weighted set says what its modifiers hold **of those members**
 * (`notes/strange-words-plan.md` §2): `deserted villages`, `motionless monsters`, `bare trees`.
 */
@Tags(NEEDS_REGISTRIES)
class MemberScopingCheck : FunSpec({

    fun aimed(seed: Long, vararg pages: String): AgeComposition = ShippedCorpus.resolved(seed, *pages).composition

    fun AgeComposition.struck(): List<String> =
        optionsFor(Aspect.STRUCTURES).claimsOn(Structures.BUILT).filter { it.polarity == Polarity.EXCEPT }.map { it.value }

    test("deserted villages are abandoned and still built") {
        for (seed in SEEDS) {
            val age = aimed(seed, "deserted", "villages")
            val abandoned = age.optionsFor(Aspect.STRUCTURES).isTrue(Structures.ABANDONED, member = Structures.VILLAGES)
            check(abandoned) { "seed $seed: 'deserted villages' left the villages lived in" }
            check(Structures.VILLAGES !in age.struck()) { "seed $seed: 'deserted villages' struck the villages out" }
        }
    }

    test("a deserted Age still builds nothing anybody lives in") {
        val struck = aimed(SEEDS.first, "deserted", "age").struck()
        check(Structures.VILLAGES in struck) { "a deserted Age kept its villages: it struck $struck" }
    }

    test("motionless monsters root the monsters and leave everything else walking") {
        for (seed in SEEDS) {
            val spawns = aimed(seed, "motionless", "monsters").optionsFor(Aspect.SPAWNS)
            check(spawns.isTrue(Spawns.STILL, member = ZOMBIE)) { "seed $seed: 'motionless monsters' left a zombie walking" }
            check(!spawns.isTrue(Spawns.STILL, member = COW)) { "seed $seed: 'motionless monsters' rooted a cow" }
        }
    }

    test("charged creepers and charged monsters charge the creepers, and a charged Age charges none") {
        for (seed in SEEDS) {
            check(aimed(seed, "charged", "creeper").optionsFor(Aspect.SPAWNS).isTrue(Spawns.CHARGED, member = CREEPER)) {
                "seed $seed: 'charged creeper' left the creeper uncharged"
            }
            check(aimed(seed, "charged", "monsters").optionsFor(Aspect.SPAWNS).isTrue(Spawns.CHARGED, member = CREEPER)) {
                "seed $seed: 'charged monsters' left the creepers uncharged"
            }
            check(!aimed(seed, "charged", "age").optionsFor(Aspect.SPAWNS).isTrue(Spawns.CHARGED, member = CREEPER)) {
                "seed $seed: a charged Age charged its creepers"
            }
        }
    }

    test("motionless spawns root everything") {
        val spawns = aimed(SEEDS.first, "motionless", "spawns").optionsFor(Aspect.SPAWNS)
        check(spawns.isTrue(Spawns.STILL, member = COW)) { "'motionless spawns' left a cow walking" }
    }

    test("bare trees strip the trees and leave the ores alone") {
        for (seed in SEEDS) {
            val bare = aimed(seed, "bare", "trees").optionsFor(Aspect.FEATURES).holders(Features.BARE)
            check(!bare.everyone) { "seed $seed: 'bare trees' stripped every feature" }
            check(bare.only.any { "tree" in it }) { "seed $seed: 'bare trees' stripped ${bare.only}" }
            check(bare.only.none { it.startsWith(ORE_FEATURE) }) { "seed $seed: 'bare trees' reached an ore" }
        }
    }

    test("monsters said to be motionless are only picked out, and no more of them are asked for") {
        fun asked(vararg pages: String) = aimed(SEEDS.first, *pages).optionsFor(Aspect.SPAWNS).claimsOn(Spawns.LIVES)
        check(asked("monsters").isNotEmpty()) { "'monsters' on its own asked for nothing, so this proves nothing" }
        check(asked("motionless", "monsters").isEmpty()) {
            "'motionless monsters' asked for more monsters: ${asked("motionless", "monsters")}"
        }
        check(asked("motionless", "teeming", "monsters").isNotEmpty()) {
            "'motionless teeming monsters' lost the rung it laid on the monsters"
        }
    }

    test("a page costs the same said of members as said of an aspect") {
        fun costOf(vararg pages: String) = ShippedCorpus.resolved(SEEDS.first, *pages).cost
        val alone = costOf("deserted", "age") + costOf("villages") - costOf()
        check(costOf("deserted", "villages") == alone) {
            "'deserted villages' cost ${costOf("deserted", "villages")}, where its two pages cost $alone apart"
        }
    }
})

private val SEEDS = 0L..<10L
private const val ZOMBIE = "minecraft:zombie"
private const val COW = "minecraft:cow"
private const val CREEPER = "minecraft:creeper"
private const val ORE_FEATURE = "minecraft:ore_"
