package co.voik.agesandtheart.desk

import net.minecraft.server.level.ServerPlayer

/**
 * The seed the next book a writer binds will be written at — chosen **in advance**, like an enchanting
 * table's (design §7.3).
 *
 * **Why it has to exist at all.** A flaw is not a property of a sentence, it is a property of *resolving*
 * one: where two words tie on precision, `tieBreak` orders them by the seed and everything after the first
 * is charged as displaced. So "which of these two words loses" is unanswerable until a seed exists — and a
 * desk that showed a guess would be wrong exactly as often as it was interesting.
 *
 * Deciding it up front makes the desk's answer *exact* rather than probable, and it is the same bargain
 * vanilla already teaches at an enchanting table.
 *
 * **Per player, and rerolled the moment a book is bound**, so one desk cannot be read repeatedly for a
 * better Age. The consequence is accepted: two writers at one desk with the same pages see different
 * predictions, which is true to an Age being the writer's rather than the furniture's.
 *
 * **The bound book carries it** ([co.voik.agesandtheart.content.AgeComponents.BOOK_SEED]). Holding it only on
 * the player would let the prediction and the Age drift apart the moment the book changed hands or waited a
 * week in a chest — and a desk that promised one world and delivered another would be worse than one that
 * promised nothing.
 */
interface WritingSeedHolder {
    fun agesandtheart_writingSeed(): Long

    fun agesandtheart_rerollWritingSeed()
}

/** The seed this writer's next book will be written at. */
val ServerPlayer.writingSeed: Long
    get() = (this as WritingSeedHolder).agesandtheart_writingSeed()

/** Called once a book is bound, so the next one is a different world. */
fun ServerPlayer.rerollWritingSeed() {
    (this as WritingSeedHolder).agesandtheart_rerollWritingSeed()
}

/** Where the seed sits in a player's save data. */
const val WRITING_SEED_KEY = "writing_seed"
