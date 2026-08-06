package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.desk.DeskCapability
import co.voik.agesandtheart.desk.DeskSyncPayload
import net.minecraft.resources.Identifier

/** One row in a word list: what it is called, and what the desk can currently do with it. */
data class WordRow(
    val word: Identifier,
    val readable: String,
    val inArchive: Int,
)

/**
 * What the open desk screen believes, kept between payloads.
 *
 * Searching never leaves the client: the learned set is already here from [KnownWords], and the archive
 * arrives whole, so filtering a thousand words is a string comparison rather than a round trip.
 */
object DeskModel {
    var state: DeskSyncPayload? = null
        private set

    /** When the archive last grew, so the tab that owns it can react. */
    var archiveGrewAt: Long = 0L
        private set

    fun remember(payload: DeskSyncPayload) {
        val before = state?.archive?.values?.sum() ?: 0
        val after = payload.archive.values.sum()
        if (after > before) archiveGrewAt = System.currentTimeMillis()
        state = payload
    }

    /** The last quote the server gave, for whichever word is selected. */
    var price: co.voik.agesandtheart.desk.DeskPricePayload? = null
        private set

    fun remember(quote: co.voik.agesandtheart.desk.DeskPricePayload) {
        price = quote
    }

    /** What [paper] would cost for the quoted word, or null if nothing has been quoted for it. */
    fun priceFor(word: Identifier?, paper: InkTier): Pair<InkTier, Long>? {
        val quote = price ?: return null
        if (quote.word != word) return null
        return quote.prices[paper]
    }

    /** The last refusal and when it arrived, so the screen can show it and let it fade. */
    var notice: String? = null
        private set
    var noticeAt: Long = 0L
        private set

    fun remember(notice: co.voik.agesandtheart.desk.DeskNoticePayload) {
        this.notice = notice.reason
        noticeAt = System.currentTimeMillis()
    }

    fun forget() {
        state = null
        price = null
        archiveGrewAt = 0L
        notice = null
        noticeAt = 0L
    }

    fun ink(tier: InkTier): Long = state?.ink?.get(tier) ?: 0L

    fun inkCapacity(): Long = state?.inkCapacity ?: 1L

    fun paper(tier: InkTier): Int = state?.paper?.get(tier) ?: 0

    /** Bindings held. A counted stock like paper, not a slot. */
    fun binding(): Int = state?.binding ?: 0

    fun can(capability: DeskCapability): Boolean = state?.capabilities?.contains(capability) == true

    fun composing(): List<Identifier> = state?.composing.orEmpty()

    fun pageLimit(): Int? = state?.pageLimit

    fun archiveCount(word: Identifier): Int = state?.archive?.get(word) ?: 0

    /** What disagrees with what, as the server resolved it against the seed the book will use. */
    fun quarrels(): List<co.voik.agesandtheart.desk.Quarrel> = state?.quarrels.orEmpty()

    /** The sentence the laid-out pages make, as the server read it back. */
    fun reading(): String = state?.reading.orEmpty()

    /**
     * Every word the player knows, each with however many pages of it the desk holds.
     *
     * **Knowledge rather than stock**, which is the archive's whole shape now: a word you know and have no
     * page for is a row offering to write one, where a list of only what you hold cannot offer anything.
     * A row is therefore *your* word against *this desk's* pages — two writers at one desk see different
     * rows over one pile.
     */
    fun knownRows(filter: String): List<WordRow> =
        KnownWords.words.map(::rowFor).matching(filter)

    private fun rowFor(word: Identifier) =
        WordRow(word, WordNames.readable(word).string, archiveCount(word))

    /** Case-insensitive on the readable name and the id both, so `blackstone` and `minecraft:` both find. */
    private fun List<WordRow>.matching(filter: String): List<WordRow> {
        val needle = filter.trim().lowercase()
        val found = if (needle.isEmpty()) this else filter { row ->
            row.readable.lowercase().contains(needle) || row.word.toString().contains(needle)
        }
        return found.sortedBy { it.readable }
    }
}
