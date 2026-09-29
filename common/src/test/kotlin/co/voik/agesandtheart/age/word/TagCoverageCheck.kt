package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **What a vague word can actually reach** — the instrument the tag pass is measured by
 * (`notes/the-tag-layer.md`).
 *
 * For an **open** aspect the candidate pool *is* the tag table (`Vocabulary.candidatesFor`), so a member
 * nobody tagged is not merely hard to describe: it is invisible to every vague word ever written, however
 * reachable it stays by name. That is why coverage is a property worth holding rather than a number worth
 * knowing, and why a floor here is a ratchet rather than a description.
 *
 * Offline, so it sees only what a corpus without a server sees. The tag layer reads vanilla's own tags at
 * runtime and this cannot (`VanillaRegistries` carries none), which is the split
 * `VocabularyOnServerCheck` holds the other half of.
 */
@Tags(NEEDS_REGISTRIES)
class TagCoverageCheck : FunSpec({

    /**
     * **A query must do one of its two jobs.** It chooses carriers — and, separately, it says what the word
     * *means* in tag space, which is how `Vocabulary.disagreement` finds that two words disagree. A
     * query doing neither is a page a writer can lay and be charged for that can never do the thing it
     * describes: §3.3's silent drop, in the vocabulary rather than in the resolver.
     *
     * **The second job is why a word about a range may still carry a query**, and why that is not the
     * "no range is ever tagged" mistake (world model §7). `lifeless` asks `barren` where no climate can
     * carry it, and that is what makes `verdant lifeless` diagnosable as lush against barren rather than as
     * two spans that happen not to meet. Tagging a *word* is describing the word; tagging a *range* would
     * be describing the world, and only the second is forbidden. Deleting those queries as dead cost the
     * fracture its provenance and `ResolverCheck` caught it.
     *
     * Carriers are asked of the word's *effective* reach: a narrowing word's declared aspects, and an evocative
     * word's purchase, which is what the resolver reads for one ([Resolver.purchaseFor]). Reading "declares no
     * aspect" as "everywhere" held only until an evocative word could declare one — by offering a sky colour,
     * say — and then it judged the word by the sky alone.
     */
    test("every query does something") {
        fun reachOf(word: Word): Collection<Aspect> =
            if (word.tier.narrows) word.aspects.ifEmpty { Aspect.entries.toSet() }
            else Resolver.purchaseFor(vocabulary, word)
        fun opposable(tag: String) = vocabulary.antonyms.any { it.first == tag || it.second == tag }
        val onlyAServerKnows = vocabulary.tagsOnlyAServerGrants
        val inert = vocabulary.authoredWords
            .filter { it.everyTagAsked.isNotEmpty() }
            .filter { word -> reachOf(word).none { vocabulary.answersIn(word, it) } }
            .filterNot { word -> word.wanted.any(::opposable) }
            .filterNot { word -> word.wanted.any(onlyAServerKnows::contains) }
        check(inert.isEmpty()) {
            "these words ask for tags nothing carries and no antonym knows, so the query does nothing:\n" +
                inert.joinToString("\n") { word ->
                    "  ${word.name} (${word.aspects.joinToString(" ") { it.key }}) asks ${word.everyTagAsked.keys}"
                }
        }
    }

    /**
     * **And every tag asked for is a tag something carries** — the same claim one level finer, which is
     * what catches a misspelling. `lovley` asked of nothing is silent where `lovely` asked of nothing is
     * merely unlucky. A tag only a bound registry tag can grant counts as carried, since offline it cannot
     * be and on a server it always is.
     */
    test("no word asks for a tag that does not exist") {
        val carried = vocabulary.carriedTags + vocabulary.tagsOnlyAServerGrants
        // **Leaned tags too.** A misspelling in a lean is exactly as inert as one in a restriction, and
        // rather quieter: a lean that finds nothing simply falls on nothing and says so nowhere.
        val asked = vocabulary.authoredWords
            .flatMap { word -> word.everyTagAsked.keys + word.leanedTags }
            .toSet()
        val unknown = (asked - carried).sorted()
        check(unknown.isEmpty()) {
            "no preset carries ${unknown.joinToString(", ")} — a typo, or a tag nothing was ever given"
        }
    }

    /**
     * **Every preset a sentence may reach in a closed aspect carries a tag.** A closed aspect has a fixed
     * pool, so an untagged member is reachable only by a word that names it outright — which for a landform
     * or a sky is a word we would have had to write and did not.
     */
    test("nothing available to broad words in a closed aspect is untagged") {
        val untagged = Aspect.entries.filter { !it.open }
            .flatMap { aspect -> vocabulary.availableToBroadWordsIn(aspect).map { aspect to it } }
            .filter { (_, preset) -> vocabulary.tagsOf(preset).isEmpty() }
        check(untagged.isEmpty()) {
            "these are available to broad words and carry no tags, so no vague word can find them: " +
                untagged.joinToString(", ") { (aspect, preset) -> "${aspect.key}.${preset.key}" }
        }
    }

    /**
     * **The ratchet.** What a vague word can reach today, per aspect, as a floor — so the pass can only
     * improve coverage and a deleted tag table is loud.
     *
     * **These are the offline numbers and the derivation's *fact* half is what carries them** — the feature
     * a placed feature places, a creature's spawn category, the band a biome's climate falls in. The tag
     * half doubles the sea and adds a third of the biomes again, and none of it is visible here because no
     * tag is bound offline (`the-tag-layer.md` §4). Raise these when coverage rises; a fall means a rule
     * stopped matching.
     */
    test("no aspect reaches less than it did") {
        val floors = mapOf(
            // Seventeen since `shapes` was deleted and `spire_islands` kept for the Spire alone.
            Aspect.TERRAIN to 17,
            Aspect.CARVERS to 4,
            // The four below each lost what `preset_tags` keeps from broad words — only a word naming one reaches it.
            Aspect.STRUCTURES to 12,
            Aspect.SPAWNS to 78,
            // Biomes also lost two earlier that `beautiful` weighs by name: admitting is a sentence's own
            // business, so those are in the bag when `beautiful` is in the book and not otherwise — §8.2's
            // promise kept rather than coverage lost.
            Aspect.BIOMES to 36,
            Aspect.FEATURES to 183,
            Aspect.SEA to 3,
        )
        val shortfall = floors.filter { (aspect, floor) -> vocabulary.availableToBroadWordsIn(aspect).size < floor }
        check(shortfall.isEmpty()) {
            "these reach fewer presets than they used to:\n" + shortfall.entries.joinToString("\n") { (aspect, floor) ->
                "  ${aspect.key}: ${vocabulary.availableToBroadWordsIn(aspect).size}, was ${floor}"
            }
        }
    }

    /**
     * **A population whose emptiness is spelled by a word must be emptiable by that word.**
     *
     * `untouched` means "nothing built here" and says it by excluding `#built` and `#inhabited`; the
     * recipe collapses to `built=nothing` only where *every* member the Art can reach is struck. So one
     * structure set carrying neither tag does not merely go untagged — it stops the whole word working,
     * and does it silently: the Age gets seventeen exclusions and an eighteenth structure set built in it.
     *
     * Found the day the tag editor could add a member: `nether_complexes` went in carrying `monumental`
     * alone and `untouched` stopped meaning anything. **Structure sets are the case** because a structure
     * set *is* a built thing — the tag is definitional there, where `wooded` on a biome is a judgement.
     */
    test("every structure set the Art can reach is built") {
        val emptying = vocabulary.words.filter { it.excludes[Aspect.STRUCTURES].orEmpty().isNotEmpty() }
        check(emptying.isNotEmpty()) { "no word strikes structure sets, so this checks nothing" }
        val reachable = vocabulary.availableToBroadWordsIn(Aspect.STRUCTURES)
        val unstruck = reachable.filterNot { set ->
            val tags = vocabulary.tagsOf(set)
            emptying.any { word -> word.excludes(set, tags) }
        }
        check(unstruck.isEmpty()) {
            "no word can empty the structures while these carry none of the tags one strikes — " +
                "`untouched` would leave them standing: ${unstruck.map { it.key }}"
        }
    }

    /**
     * **`mobs` is the whole spawn population, and that is what makes it worth striking.**
     *
     * The structure-set case above is the same invariant held member by member, and it needed holding
     * because `#built` and `#inhabited` are judgements that a new member can simply miss. Here the rule
     * is definitional: `Derivation.spawns` derives every entity type but `MISC`, and `mobs` is on every
     * `MobCategory` there is — so a word striking `#mobs` strikes all of them, and a mob added to the
     * game joins without anybody tagging it.
     *
     * Checked rather than trusted because the whole of that rests on the derivation naming *every*
     * category. One added to `MobCategory` and missed here would leave a word meaning "no creatures at
     * all" quietly leaving one kind in, which is precisely the fault `untouched` had.
     */
    test("every creature the Art can reach is a mob") {
        val reachable = vocabulary.availableToBroadWordsIn(Aspect.SPAWNS)
        check(reachable.isNotEmpty()) { "no creature is reachable, so this checks nothing" }
        val untagged = reachable.filterNot { MOBS in vocabulary.tagsOf(it) }
        check(untagged.isEmpty()) {
            "a word striking #$MOBS would leave these standing, so nothing can empty the spawns: " +
                untagged.map { it.key }
        }
    }

})

/** The tag that is the whole spawn population — every `MobCategory` grants it. */
private const val MOBS = "mobs"
