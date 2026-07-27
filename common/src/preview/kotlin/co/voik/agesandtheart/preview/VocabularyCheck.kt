package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.server.packs.resources.ResourceManager
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.isDirectory

/**
 * Reads the Art's vocabulary the way a server would and asks whether it is *sound content* —
 * a different question from whether the resolver works, which is [ResolverCheck]'s.
 *
 * It exists because of one rule (design §3.3): **we only make words we can back up.** A word nothing in
 * the world can satisfy is a content bug rather than a play outcome, and the Phase 3 spike showed what
 * that failure looks like from the outside — "moonless" cost nothing, changed nothing, was reported as
 * nothing, and the player simply got a world their sentence did not describe. Words are datapack data
 * now, so nothing but a check like this stands between a typo in a JSON file and that experience.
 *
 * Offline: the corpus is plain data read through a resource manager over the module's own
 * `src/main/resources`, which is also a rehearsal of the loading path the game uses. It does now need the
 * game's *built-in* registries, since §8's derived vocabulary is read from them — but those come from
 * `Bootstrap`, not from a server, so this still runs in a second and still needs no world.
 */
fun main() {
    // Blocks and fluids are registered at class-init rather than loaded from a datapack, so a bootstrap is
    // the whole of what deriving vocabulary needs. Biomes will not be so easy — they are datapack content,
    // and reading them means a server. Version detection first, for the reason CodecCheck spells out.
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()
    val vocabulary = Vocabulary.load(shippedData())

    everyFileWasUnderstood(vocabulary)
    everyWordIsBackedByTheWorld(vocabulary)
    everyNarrowingWordSaysWhatItIsAbout(vocabulary)
    everySlotHasWords(vocabulary)
    everyPresetCanBeAskedFor(vocabulary)
    everyAntonymCouldFire(vocabulary)
    vaguenessCannotReachDerivedContent(vocabulary)

    println(
        "Vocabulary: ${vocabulary.words.size} words over ${Slot.entries.size} slots, " +
            "${vocabulary.carriedTags.size} tags carried by " +
            "${Slot.entries.sumOf { vocabulary.candidatesFor(it).size }} curated presets, " +
            "${vocabulary.antonyms.size} antonym pairs — all backed, all reachable.",
    )
}

/**
 * Nothing failed to load.
 *
 * First because everything below is weaker than it looks if a file was quietly skipped: a word that never
 * loaded is trivially "backed", never contradicts anything, and passes every other check here.
 */
private fun everyFileWasUnderstood(vocabulary: Vocabulary) {
    check(vocabulary.problems.isEmpty()) {
        "The vocabulary did not load cleanly:\n  ${vocabulary.problems.joinToString("\n  ")}"
    }
    check(vocabulary.words.isNotEmpty()) { "No words loaded at all — is the data directory where it was?" }
}

/**
 * Every word has something in the world that can satisfy it, in every slot it claims to be about.
 *
 * The check this file exists for. Two ways to fail it, and they want telling apart: a word asking for a
 * tag nothing carries (a misspelt tag, usually), and a word about a slot whose presets happen not to carry
 * the tag it asks for at its own tier's strictness (an *exact* word aimed at a weak carrier — the subtler
 * one, because the tag is real and the slot is real and the pair of them still cannot meet).
 */
private fun everyWordIsBackedByTheWorld(vocabulary: Vocabulary) {
    for (word in vocabulary.words) {
        val unknownTags = word.wanted - vocabulary.carriedTags
        check(unknownTags.isEmpty()) {
            "'${word.name}' asks for ${unknownTags.joinToString(" ")}, which nothing in the world carries"
        }
        val slots = Resolver.slotsSpokenTo(vocabulary, word)
        check(slots.isNotEmpty()) {
            "'${word.name}' has a say in no slot at all, so writing it would do nothing and cost nothing"
        }
        // A word that named its slots must be satisfiable in each of them; an evocative word named none,
        // and having found purchase anywhere is what it promised.
        val declared = if (word.slots.isEmpty()) emptyList() else slots
        for (slot in declared) {
            // "Backed" means something different for a word that *steers* rather than *chooses* (§3.2):
            // it constrains no presets, so it has no carriers by construction and asking for one would
            // condemn every material word. What it needs instead is a preset that offers the knob.
            if (!word.constrainsPresets) {
                for (parameter in word.sets.keys) {
                    val offered = vocabulary.candidatesFor(slot)
                        .flatMap { it.parameters }
                        .filter { it.name == parameter }
                    check(offered.isNotEmpty()) {
                        "'${word.name}' sets ${slot.key}.$parameter, which no ${slot.key} offers"
                    }
                    for (option in word.sets.values) {
                        check(offered.any { it.accepts(option) }) {
                            "'${word.name}' sets ${slot.key}.$parameter to '$option', which it does not take"
                        }
                    }
                }
                continue
            }
            val carriers = vocabulary.carriersOf(word, slot)
            check(carriers.isNotEmpty()) {
                "'${word.name}' is ${word.tier.key} about ${slot.key}, but no ${slot.key} carries " +
                    "${word.wanted.joinToString(" ")} strongly enough (needs ${word.tier.threshold})"
            }
        }
    }
}

/**
 * Every word that narrows says which slots it narrows.
 *
 * The spike's worst finding, guarded at the content layer where it is now possible to reintroduce by
 * forgetting a line of JSON: an unscoped precise word gets a say in every slot its tags happen to touch,
 * so `stormy` pins the landform to caverns and throws `floating` away in silence.
 */
private fun everyNarrowingWordSaysWhatItIsAbout(vocabulary: Vocabulary) {
    for (word in vocabulary.words.filter { it.tier.narrows }) {
        check(word.slots.isNotEmpty()) {
            "'${word.name}' is ${word.tier.key} but names no slot, so it would narrow every slot its tags " +
                "reach — which is how a word about the sky ends up choosing the ground"
        }
    }
}

/**
 * The promise of §8.2, asserted rather than trusted: **vagueness draws only from the curated pool.**
 *
 * It holds structurally — the curated pool is what `preset_tags` names, and a derived word arrives with its
 * carrier in hand rather than searching — so this check should never fire. That is exactly why it is worth
 * having: a structural guarantee is one refactor away from becoming a convention, and the failure it would
 * become is quiet. "A beautiful world" that draws a sea of somebody's radioactive sludge is the promise
 * broken, and nobody would think to look here for the reason.
 *
 * The other half of the promise, that precision *can* reach anything, is [everyWordIsBackedByTheWorld]'s
 * carrier assertion applied to derived words: each has a carrier because it names one.
 */
private fun vaguenessCannotReachDerivedContent(vocabulary: Vocabulary) {
    val curated = Slot.entries.flatMap { slot -> vocabulary.candidatesFor(slot).map { it.key } }.toSet()
    val derived = vocabulary.words.filter { it.names != null }
    check(derived.isNotEmpty()) {
        "No derived words at all — the pack has fluids, so this means derivation is not running"
    }
    for (word in derived) {
        for (slot in word.slots) {
            val reachable = vocabulary.words.any { vague ->
                !vague.tier.narrows && word.names in vocabulary.carriersOf(vague, slot).map { it.key }
            }
            check(!reachable || word.names in curated) {
                "'${word.name}' is derived content a vague word can reach in ${slot.key}, and it was never " +
                    "curated — which is §8.2's promise broken, and invisible from the outside"
            }
        }
    }
}

/** Every slot has at least one word about it, or part of the world is unwritable. */
private fun everySlotHasWords(vocabulary: Vocabulary) {
    for (slot in Slot.entries) {
        val about = vocabulary.words.count { slot in it.slots }
        check(about > 0) { "No word is about the ${slot.key} slot, so nothing a writer says can steer it" }
    }
}

/**
 * Every preset can be reached by some word.
 *
 * A preset no sentence can ask for is content nobody can use: it will still turn up when the seed draws
 * an unconstrained slot, but a writer who wants it has no way to say so. Cheap to fix (a word, or a tag
 * weight nudged) and invisible without asking.
 */
private fun everyPresetCanBeAskedFor(vocabulary: Vocabulary) {
    for (slot in Slot.entries) {
        // The curated pool, not the registry: a derived word reaches every referent by construction, so
        // the only presets that can go unreachable are the ones somebody chose to curate (design §8.2).
        for (preset in vocabulary.candidatesFor(slot)) {
            val reachable = vocabulary.words.any { word ->
                slot in Resolver.slotsSpokenTo(vocabulary, word) && word.tier.narrows &&
                    preset in vocabulary.carriersOf(word, slot)
            }
            check(reachable) {
                "No word can ask for ${slot.key}=${preset.key}, so it can only ever arrive by chance. " +
                    "Its tags are ${vocabulary.tagsOf(preset)}"
            }
        }
    }
}

/**
 * Every antonym pair could actually fire.
 *
 * A pair naming a tag nothing carries is a dead row: harmless, but it looks like coverage where there is
 * none, and the table's whole job is explaining tensions the data found.
 */
private fun everyAntonymCouldFire(vocabulary: Vocabulary) {
    for (antonym in vocabulary.antonyms) {
        for (tag in listOf(antonym.first, antonym.second)) {
            check(tag in vocabulary.carriedTags) {
                "The antonym '${antonym.first} vs ${antonym.second}' names '$tag', which nothing carries"
            }
        }
        check(vocabulary.words.any { antonym.first in it.wanted }) {
            "No word asks for '${antonym.first}', so the pair '${antonym.first} vs ${antonym.second}' can never fire"
        }
        check(vocabulary.words.any { antonym.second in it.wanted }) {
            "No word asks for '${antonym.second}', so the pair '${antonym.first} vs ${antonym.second}' can never fire"
        }
    }
}

/**
 * The mod's own data as a resource manager — the same class the server reads packs through, over the
 * source tree rather than a built jar so this runs in a second and needs no build.
 */
internal fun shippedData(): ResourceManager {
    val root = listOf(Path.of("src/main/resources"), Path.of("common/src/main/resources"))
        .firstOrNull { it.isDirectory() }
        ?: error("Cannot find the mod's resources from ${Path.of("").toAbsolutePath()}")
    val where = PackLocationInfo("agesandtheart", Component.literal("Ages and the Art"), PackSource.BUILT_IN, Optional.empty())
    return MultiPackResourceManager(PackType.SERVER_DATA, listOf(PathPackResources(where, root)))
}
