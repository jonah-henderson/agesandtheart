package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.aspect.Aspect
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
    everyUnaskablePresetIsPinnedByARecipe(vocabulary)
    everyAntonymCouldFire(vocabulary)
    vaguenessCannotReachDerivedContent(vocabulary)

    println(
        "Vocabulary: ${vocabulary.words.size} words over ${Aspect.entries.size} aspects, " +
            "${vocabulary.carriedTags.size} tags carried by " +
            "${Aspect.entries.sumOf { vocabulary.candidatesFor(it).size }} curated presets, " +
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
 * Every word has something in the world that can satisfy it, in every aspect it claims to be about.
 *
 * The check this file exists for. Two ways to fail it, and they want telling apart: a word asking for a
 * tag nothing carries (a misspelt tag, usually), and a word about a aspect whose presets happen not to carry
 * the tag it asks for at its own tier's strictness (an *exact* word aimed at a weak carrier — the subtler
 * one, because the tag is real and the aspect is real and the pair of them still cannot meet).
 */
private fun everyWordIsBackedByTheWorld(vocabulary: Vocabulary) {
    for (word in vocabulary.words) {
        val unknownTags = word.wanted - vocabulary.carriedTags
        check(unknownTags.isEmpty()) {
            "'${word.name}' asks for ${unknownTags.joinToString(" ")}, which nothing in the world carries"
        }
        val aspects = Resolver.aspectsSpokenTo(vocabulary, word)
        check(aspects.isNotEmpty()) {
            "'${word.name}' has a say in no aspect at all, so writing it would do nothing and cost nothing"
        }
        // A word that named its aspects must be satisfiable in each of them; an evocative word named none,
        // and having found purchase anywhere is what it promised.
        val declared = if (word.aspects.isEmpty()) emptyList() else aspects
        for (aspect in declared) {
            // "Backed" means something different for a word that *steers* rather than *chooses* (§3.2):
            // it constrains no presets, so it has no carriers by construction and asking for one would
            // condemn every material word. What it needs instead is a preset that offers the knob.
            //
            // Asked **per aspect**, because a derived block word does both: it names a sea, where the
            // aspect's value simply *is* a block, and sets a material on the terrain and the dressing, which
            // hold nothing called `minecraft:copper_block`. Asking globally condemned every block in the
            // game for failing to be a terrain.
            // A word may *narrow* in one aspect and merely *steer* in another, and then having no carrier here is
            // no fault at all: `arid` narrows the terrain on `dry`/`barren` tags and bounds the climate's axes
            // with spans, which is two real jobs. What the check is actually for is a word with *nothing* to do
            // in an aspect it claims — so the question is whether it turns a knob this aspect holds, not whether
            // it happens to also carry a query.
            val turnsAKnobHere = word.sets.keys.any { parameter ->
                vocabulary.candidatesFor(aspect).any { it.honoursParameterNamed(parameter) }
            }
            if (!word.constrainsPresetsIn(aspect) || turnsAKnobHere) {
                for (parameter in word.sets.keys) {
                    val presets = vocabulary.candidatesFor(aspect)
                    val offered = presets.flatMap { it.parameters }.filter { it.name == parameter }
                    check(offered.isNotEmpty()) {
                        "'${word.name}' sets ${aspect.key}.$parameter, which no ${aspect.key} offers"
                    }
                    // Declaring a knob and turning it are different things (`AspectPreset.honours`), and only
                    // the second makes a word mean anything. Continentalness and erosion shipped as climate
                    // axes that nothing could honour and were invisible in game for a whole session — this
                    // is the check that would have caught them before they were written.
                    val anythingTurnsIt = presets.any { it.honoursParameterNamed(parameter) }
                    check(anythingTurnsIt) {
                        "'${word.name}' sets ${aspect.key}.$parameter, which every ${aspect.key} declares and " +
                            "none acts on — so writing it would change nothing and say nothing"
                    }
                    for (option in word.sets.values) {
                        check(offered.any { it.accepts(option) }) {
                            "'${word.name}' sets ${aspect.key}.$parameter to '$option', which it does not take"
                        }
                    }
                }
                continue
            }
            val carriers = vocabulary.carriersOf(word, aspect)
            check(carriers.isNotEmpty()) {
                "'${word.name}' is ${word.tier.key} about ${aspect.key}, but no ${aspect.key} carries " +
                    "${word.wanted.joinToString(" ")} strongly enough (needs ${word.tier.threshold})"
            }
        }
    }
}

/**
 * Every word that narrows says which aspects it narrows.
 *
 * The spike's worst finding, guarded at the content layer where it is now possible to reintroduce by
 * forgetting a line of JSON: an unscoped precise word gets a say in every aspect its tags happen to touch,
 * so `stormy` pins the terrain to caverns and throws `floating` away in silence.
 */
private fun everyNarrowingWordSaysWhatItIsAbout(vocabulary: Vocabulary) {
    for (word in vocabulary.words.filter { it.tier.narrows }) {
        check(word.aspects.isNotEmpty()) {
            "'${word.name}' is ${word.tier.key} but names no aspect, so it would narrow every aspect its tags " +
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
    val curated = Aspect.entries.flatMap { aspect -> vocabulary.candidatesFor(aspect).map { it.key } }.toSet()
    val derived = vocabulary.words.filter { it.names != null }
    check(derived.isNotEmpty()) {
        "No derived words at all — the pack has fluids, so this means derivation is not running"
    }
    for (word in derived) {
        for (aspect in word.aspects) {
            val reachable = vocabulary.words.any { vague ->
                !vague.tier.narrows && word.names in vocabulary.carriersOf(vague, aspect).map { it.key }
            }
            check(!reachable || word.names in curated) {
                "'${word.name}' is derived content a vague word can reach in ${aspect.key}, and it was never " +
                    "curated — which is §8.2's promise broken, and invisible from the outside"
            }
        }
    }
}

/** Every aspect has at least one word about it, or part of the world is unwritable. */
private fun everySlotHasWords(vocabulary: Vocabulary) {
    for (aspect in Aspect.entries) {
        val about = vocabulary.words.count { aspect in it.aspects }
        check(about > 0) { "No word is about the ${aspect.key} aspect, so nothing a writer says can steer it" }
    }
}

/**
 * Every preset can be reached by some word.
 *
 * A preset no sentence can ask for is content nobody can use: it will still turn up when the seed draws
 * an unconstrained aspect, but a writer who wants it has no way to say so. Cheap to fix (a word, or a tag
 * weight nudged) and invisible without asking.
 */
private fun everyPresetCanBeAskedFor(vocabulary: Vocabulary) {
    for (aspect in Aspect.entries) {
        // An aspect with a single candidate needs no word to ask for it, and demanding one asks the wrong
        // question. This check exists because a preset nobody can name "will still turn up when the seed draws
        // an unconstrained aspect" — but where there is nothing to draw *between*, no chance is involved and the
        // preset arrives by construction. Climate is the case: one preset, and all of its writing happens in
        // ranged parameters (see [co.voik.agesandtheart.age.aspect.Climate]).
        if (vocabulary.candidatesFor(aspect).size <= 1) continue
        // The curated pool, not the registry: a derived word reaches every referent by construction, so
        // the only presets that can go unreachable are the ones somebody chose to curate (design §8.2).
        for (preset in vocabulary.candidatesFor(aspect)) {
            // A preset that says it is unaskable is exempt from needing a word — but not from scrutiny: the
            // pinned-preset check below insists it really is pinned somewhere, so a careless `false` still fails.
            if (!preset.askableInASentence) continue
            val reachable = vocabulary.words.any { word ->
                aspect in Resolver.aspectsSpokenTo(vocabulary, word) && word.tier.narrows &&
                    preset in vocabulary.carriersOf(word, aspect)
            }
            check(reachable) {
                "No word can ask for ${aspect.key}=${preset.key}, so it can only ever arrive by chance. " +
                    "Its tags are ${vocabulary.tagsOf(preset)}. If that is deliberate — a preset only a pinned " +
                    "recipe names — say so with `askableInASentence = false` rather than adding a word for it."
            }
        }
    }
}

/**
 * Every preset that opted out of being askable is actually **pinned by a recipe**.
 *
 * The other half of [everyPresetCanBeAskedFor]'s exemption, and the reason that exemption is safe. An omission and
 * an intention look identical from outside — a preset with no word and no tags could be an easter egg or an
 * oversight — so `askableInASentence = false` buys an exemption from *one* check and immediately owes this one.
 *
 * A preset that is neither askable nor pinned is reachable by nothing at all: dead content that still occupies a
 * candidate slot and can still be drawn by an unconstrained aspect.
 */
private fun everyUnaskablePresetIsPinnedByARecipe(vocabulary: Vocabulary) {
    val pinned = AgePreset.entries
        .mapNotNull { preset -> (AgeRecipe.worldFor(preset) as? AgeWorld.Composed)?.composition }
        .flatMap { composition -> composition.presets }
        .toSet()
    for (aspect in Aspect.entries) {
        for (preset in vocabulary.candidatesFor(aspect).filterNot { it.askableInASentence }) {
            check(preset in pinned) {
                "${aspect.key}=${preset.key} says it is unaskable, but no pinned recipe names it either — so " +
                    "nothing can reach it deliberately. Either pin it in `AgeRecipe.worldFor` or give it a word."
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
