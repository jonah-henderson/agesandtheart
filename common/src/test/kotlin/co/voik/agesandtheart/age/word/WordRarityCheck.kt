package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.RandomSource

/**
 * How hard a page is to find.
 *
 * A different axis from tier: tier is what a word costs to write, rarity is how often one turns up. The
 * corpus has two halves and the buckets treat them differently — an authored word is listed by name, and
 * the whole derived half is one weighted entry so that sixteen hundred block words do not drown the
 * hundred somebody wrote.
 */
@Tags(NEEDS_REGISTRIES)
class WordRarityCheck : FunSpec({

    /** A derived word is reachable by its full id, which is how the authoring tool names one. */
    test("a derived word can be named by its id") {
        val ore = vocabulary.word("minecraft:diamond_ore")
            ?: error("the corpus has no word for minecraft:diamond_ore")
        check(vocabulary.isDerived(ore)) { "'${ore.name}' should be a derived word" }
    }

    /**
     * **A word named in a bucket leaves the anonymous mass.**
     *
     * The authored half always did this and the derived half did not, so naming `minecraft:diamond_ore`
     * rare made it drawable as rare *and* as an ordinary derived page — which is not what setting a
     * rarity means.
     *
     * Read through a bucket that names nothing and offers only the derived mass: with the draw narrowed
     * to the one word, it can answer only if that word is still in the mass. Naming it elsewhere in the
     * same table has to empty it.
     */
    test("a named derived word is gone from the derived mass") {
        val ore = vocabulary.word("minecraft:diamond_ore") ?: error("no word for minecraft:diamond_ore")
        val onlyTheOre = { word: Word -> word.name == ore.name }
        val random = RandomSource.create(SEED)

        val unnamed = WordRarity(listOf(RarityBucket("mass", 1.0, emptySet(), 1.0, catchAll = false)))
        check(unnamed.draw(vocabulary, random, onlyTheOre) != null) {
            "the derived mass did not offer '${ore.name}' at all, so this proves nothing"
        }

        val named = WordRarity(
            listOf(
                RarityBucket("mass", 1.0, emptySet(), 1.0, catchAll = false),
                RarityBucket("rare", 0.0, setOf(ore.name), 0.0, catchAll = false),
            ),
        )
        check(named.draw(vocabulary, random, onlyTheOre) == null) {
            "'${ore.name}' is named in a bucket and is still being drawn from the anonymous mass"
        }
    }

    /** And a page says the bucket it was drawn from, under whichever of its two names it was listed. */
    test("a derived word listed by its full id is in that bucket") {
        val ore = vocabulary.word("minecraft:diamond_ore") ?: error("no word for minecraft:diamond_ore")
        val rarity = WordRarity(
            listOf(
                RarityBucket("mass", 1.0, emptySet(), 1.0, catchAll = false),
                RarityBucket("rare", 0.0, setOf(ore.id.toString()), 0.0, catchAll = false),
            ),
        )
        check(rarity.bucketOf(vocabulary, ore)?.name == "rare") {
            "'${ore.id}' is listed as rare and its page says ${rarity.bucketOf(vocabulary, ore)?.name}"
        }
    }

    /** And the same for an authored word, which is the rule this was brought into line with. */
    test("a named authored word is gone from the catch-all") {
        val word = vocabulary.authoredWords.first()
        val onlyThatOne = { each: Word -> each.name == word.name }
        val random = RandomSource.create(SEED)

        val catchAll = WordRarity(listOf(RarityBucket("common", 1.0, emptySet(), 0.0, catchAll = true)))
        check(catchAll.draw(vocabulary, random, onlyThatOne) != null) {
            "the catch-all did not offer '${word.name}', so this proves nothing"
        }

        val named = WordRarity(
            listOf(
                RarityBucket("common", 1.0, emptySet(), 0.0, catchAll = true),
                RarityBucket("rare", 0.0, setOf(word.name), 0.0, catchAll = false),
            ),
        )
        check(named.draw(vocabulary, random, onlyThatOne) == null) {
            "'${word.name}' is named in a bucket and is still coming out of the catch-all"
        }
    }
}) {
    private companion object {
        const val SEED = 1L
    }
}
