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
 * predictions, which is true to an Age being the writer's rather than the furniture's. **A writer who has
 * set the frequency tuner writes at its seed instead**, and choosing a world that way is that implement's
 * whole reward (design §7.4).
 *
 * **The bound book carries it** ([co.voik.agesandtheart.content.AgeComponents.BOOK_SEED]). Holding it only on
 * the player would let the prediction and the Age drift apart the moment the book changed hands or waited a
 * week in a chest — and a desk that promised one world and delivered another would be worse than one that
 * promised nothing.
 */
interface WritingSeedHolder {
    fun agesandtheart_writingSeed(): Long

    fun agesandtheart_rerollWritingSeed()

    /** The frequency tuner's six dials as this writer left them, or null where they have not tuned. */
    fun agesandtheart_tuning(): IntArray?

    fun agesandtheart_setTuning(dials: IntArray?)
}

/**
 * The seed this writer's next book will be written at: **the tuned one where they have tuned**, which
 * binding leaves alone, and otherwise the one drawn for them.
 */
val ServerPlayer.writingSeed: Long
    get() = tuning?.seed ?: (this as WritingSeedHolder).agesandtheart_writingSeed()

/** Called once a book is bound, so the next one is a different world — unless the writer has tuned one. */
fun ServerPlayer.rerollWritingSeed() {
    (this as WritingSeedHolder).agesandtheart_rerollWritingSeed()
}

/** Where this writer has set the frequency tuner, or null to let the desk draw the seed again. */
var ServerPlayer.tuning: Tuning?
    get() = (this as WritingSeedHolder).agesandtheart_tuning()?.let { Tuning.ofDials(it.toList()) }
    set(value) = (this as WritingSeedHolder).agesandtheart_setTuning(value?.dials?.toIntArray())

/** Where the seed sits in a player's save data. */
const val WRITING_SEED_KEY = "writing_seed"

/** Where the tuner's dials sit in a player's save data. */
const val TUNING_KEY = "tuning"
