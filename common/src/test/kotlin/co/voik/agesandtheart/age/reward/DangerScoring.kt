package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.Manifestation
import java.util.concurrent.ConcurrentHashMap

/** How the shipped table scores a row of pages, offline, as the survey would before the Age exists. */
object DangerScoring {

    /**
     * The Ages the scale was rated against, each as a writer writes it and what it was rated: the Nether and the End
     * in the Art's own words, the plainest Age for each rare material, and the combinations that showed how hazards add.
     */
    val ANCHORS = listOf(
        "nether age" to 0.5,
        "end age" to 0.45,
        "age frozen climate blizzard phenomena" to 0.4,
        "age scorching climate volcanic features" to 0.4,
        "age tempest phenomena green aurora" to 0.05,
        "age deep sea deluge phenomena" to 0.6,
        "age meteors phenomena" to 0.65,
        "age monsters lightless sky" to 1.0,
        "age teeming monsters" to 2.0,
        "age frozen climate blizzard meteors phenomena" to 1.0,
        "age lava sea monsters inferno phenomena" to 0.75,
        "age floating landmass meteors phenomena" to 1.0,
        "desolate age" to 0.2,
    )

    init {
        MinecraftRegistries.ensureStoodUp()
    }

    val table by lazy { DangerTable.load(MinecraftRegistries.shippedData()) }
    private val ladders = Manifestation.entries.associateWith(Ladder::ordinaryFor)
    private val censuses = ConcurrentHashMap<AgeTemplate, MonsterCensus>()

    fun dangerOf(pages: List<String>, seed: Long): Danger {
        val resolution = ShippedCorpus.resolved(seed, *pages.toTypedArray())
        val census = censuses.getOrPut(resolution.template) {
            MonsterCensus.over(MinecraftRegistries.worldgen, resolution.template, ShippedCorpus.vocabulary.spawning)
        }
        return Danger.of(resolution.composition, resolution.instability, seed, table, ladders, census = census)
    }
}
