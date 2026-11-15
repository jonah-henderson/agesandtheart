package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.netty.buffer.Unpooled

/** What a client is told of the words survives the wire, and is small enough to send on joining. */
@Tags(NEEDS_REGISTRIES)
class LexiconPayloadCheck : FunSpec({

    test("the lexicon a client is sent arrives as it left, well inside what one payload may carry") {
        val told = LexiconPayload.of(ShippedCorpus.vocabulary)
        val buffer = Unpooled.buffer()
        LexiconPayload.STREAM_CODEC.encode(buffer, told)
        val size = buffer.readableBytes()
        val arrived = LexiconPayload.STREAM_CODEC.decode(buffer)
        val fields = mapOf(
            "rarity" to LexiconPayload::rarity, "headings" to LexiconPayload::headings, "appliesTo" to LexiconPayload::appliesTo,
            "members" to LexiconPayload::members, "parts" to LexiconPayload::parts, "reach" to LexiconPayload::reach,
            "materials" to LexiconPayload::materials, "shapes" to LexiconPayload::shapes, "structural" to LexiconPayload::structural,
            "renamed" to LexiconPayload::renamed, "headingParts" to LexiconPayload::headingParts, "wholes" to LexiconPayload::wholes,
            "ofTheGame" to LexiconPayload::ofTheGame,
        )
        val changed = fields.filter { (_, field) -> field(arrived) != field(told) }.keys
        check(changed.isEmpty()) { "the lexicon's $changed changed in transit" }
        check(size < MOST_A_PAYLOAD_SHOULD_TAKE) { "the lexicon is $size bytes" }
    }
})

/** A quarter of the megabyte vanilla allows one custom payload, so a pack with more words still fits. */
private const val MOST_A_PAYLOAD_SHOULD_TAKE = 256 * 1024
