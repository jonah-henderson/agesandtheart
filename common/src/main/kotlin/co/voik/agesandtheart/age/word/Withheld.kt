package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.location
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier

/**
 * Words no channel may hand out — the timing fence on the summit materials (design §8.3).
 *
 * The scarab, the paper tree and nara sit on the **reproduction** rung: you may name what you can make
 * more of. Possessing one salvaged nara block is exactly the state in which naming it is §8.4's
 * duplication exploit, so both channels that could grant a word early have to consult this — the page
 * loot that scatters referents, and the analysis machine that names what you hold.
 *
 * **A fence on the granting, not on the word.** Writing is already gated, since you must know a word to
 * write it; what this closes is the two doors knowledge comes through. Once the colony is breeding the
 * word is granted by the rung itself and works like any other.
 *
 * Deliberately *not* [DerivedWords.FORBIDDEN]: that tag is a pack author's hard fence, and these stay
 * writable as the summit reward.
 */
object Withheld {
    val TAG_NAME: Identifier = "withheld".location()

    /** Whether [word] is held back from the channels. Authored words never are — they name no referent. */
    fun holdsBack(word: Word, registries: RegistryAccess): Boolean = registries.carriesTagNamed(word, TAG_NAME)
}
