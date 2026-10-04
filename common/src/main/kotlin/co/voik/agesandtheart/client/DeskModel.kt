package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.age.word.grammar.ProseWriting
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.desk.DeskCapability
import co.voik.agesandtheart.desk.DeskNoticePayload
import co.voik.agesandtheart.desk.DeskSyncPayload
import co.voik.agesandtheart.desk.PagePrice
import co.voik.agesandtheart.desk.Quarrel
import co.voik.agesandtheart.desk.ReadWord
import co.voik.agesandtheart.desk.WriteCost
import net.minecraft.resources.Identifier
import kotlin.math.ceil
import kotlin.math.floor

/** One row in the word list: a word the writer knows, and what it is called. */
data class WordRow(val word: Identifier, val readable: String)

/**
 * What the open desk screen believes, kept between payloads.
 *
 * Searching never leaves the client: the learned set is already here from [KnownWords], so filtering a
 * thousand words is a string comparison rather than a round trip.
 */
object DeskModel {
    var state: DeskSyncPayload? = null
        private set

    fun remember(payload: DeskSyncPayload) {
        state = payload
    }

    /** The last refusal and when it arrived, so the screen can show it and let it fade. */
    var notice: String? = null
        private set
    var noticeAt: Long = 0L
        private set

    fun remember(notice: DeskNoticePayload) {
        this.notice = notice.reason
        noticeAt = System.currentTimeMillis()
    }

    fun forget() {
        state = null
        notice = null
        noticeAt = 0L
    }

    fun ink(tier: InkTier): Long = state?.ink?.get(tier) ?: 0L

    fun inkCapacity(): Long = state?.inkCapacity ?: 1L

    /**
     * [units] in **bottles**, the only measure of ink a player sees, to one place and with no `.0` on a
     * whole number. A price rounds up so it never reads as less than it is; what is held rounds down so it
     * never reads as more.
     *
     * The raw unit is the loader's — Fabric counts droplets, NeoForge millibuckets — and means nothing to
     * anybody. A bottle here is [WriteCost.BOTTLES_PER_BUCKET]'s share of a bucket on both loaders.
     */
    fun inBottles(units: Long, roundUp: Boolean = true): String {
        val perBottle = unitsPerBucket().toDouble() / WriteCost.BOTTLES_PER_BUCKET
        val exactTenths = units * TENTHS / perBottle
        val tenths = (if (roundUp) ceil(exactTenths) else floor(exactTenths)).toLong()
        return if (tenths % TENTHS == 0L) "${tenths / TENTHS}" else "${tenths / TENTHS}.${tenths % TENTHS}"
    }

    private const val TENTHS = 10

    private fun unitsPerBucket(): Long = (inkCapacity() / AgeFluids.TANK_CAPACITY_BUCKETS).coerceAtLeast(1)

    fun paper(tier: InkTier): Int = state?.paper?.get(tier) ?: 0

    /** Bindings held. A counted stock like paper, not a slot. */
    fun binding(): Int = state?.binding ?: 0

    fun can(capability: DeskCapability): Boolean =
        state?.capabilities?.contains(capability) == true

    fun pageLimit(): Int? = state?.pageLimit

    /** The template the server last read, and what it read it as. */
    fun template(): String? = state?.template

    fun read(): List<ReadWord> = state?.read.orEmpty()

    fun toWrite(): List<PagePrice> = state?.toWrite.orEmpty()

    fun drawn(): Int = state?.drawn ?: 0

    /** What disagrees with what, as the server resolved it against the seed the book will use. */
    fun quarrels(): List<Quarrel> = state?.quarrels.orEmpty()

    /** The sentence the template makes, as the bound book will read it. */
    fun reading(): String = ProseWriting.sentencesOf(state?.reading.orEmpty()).joinToString(" ")

    /** Every word the player knows, alphabetical by the name the list shows, filtered by [filter]. */
    fun knownRows(filter: String): List<WordRow> {
        val needle = filter.trim().lowercase()
        return KnownWords.words
            .map { WordRow(it, WordNames.readable(it).string) }
            .filter { row -> needle.isEmpty() || row.readable.lowercase().contains(needle) || row.word.toString().contains(needle) }
            .sortedBy { it.readable.lowercase() }
    }
}
