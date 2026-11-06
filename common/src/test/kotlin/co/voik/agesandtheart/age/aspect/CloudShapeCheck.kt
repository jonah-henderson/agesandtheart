package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import co.voik.ephemeris.sky.CloudPattern
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

@Tags(NEEDS_REGISTRIES)
class CloudShapeCheck : FunSpec({

    fun patternOf(row: String): CloudPattern? = CloudShape.patternIn(resolved(row).composition.optionsFor(Aspect.CLOUD), SEED)
    fun coverOf(row: String): Float = patternOf(row)?.cover ?: CloudPattern.VANILLA_COVER

    test("the cover runs from a tenth of vanilla's through vanilla's to all but gaps, and a multiple lands where it says") {
        check(CloudShape.shareAt(0.0) == CloudPattern.VANILLA_COVER) { "the middle of the axis was ${CloudShape.shareAt(0.0)}" }
        check(CloudShape.shareAt(-1.0) < CloudPattern.VANILLA_COVER / 9) { "the bottom was ${CloudShape.shareAt(-1.0)}" }
        check(CloudShape.shareAt(1.0) > 0.95f) { "the top was ${CloudShape.shareAt(1.0)}" }
        val half = CloudShape.shareAt(CloudShape.coverAt(0.5))
        check(kotlin.math.abs(half - CloudPattern.VANILLA_COVER / 2) < 0.005f) { "half as many clouds made a cover of $half" }
    }

    test("the amounts and adjectives order the sky from clear to overcast") {
        val few = coverOf("age few clouds")
        val scattered = coverOf("age scattered clouds")
        val constant = coverOf("age constant clouds")
        val unbroken = coverOf("age unbroken clouds")
        val teeming = coverOf("age teeming clouds")
        check(few < CloudPattern.VANILLA_COVER && scattered < CloudPattern.VANILLA_COVER) { "few $few, scattered $scattered" }
        check(constant > 0.5f && unbroken > 0.85f && teeming > 0.85f) { "constant $constant, unbroken $unbroken, teeming $teeming" }
    }

    test("scattered lies in bits, patchy in patches, torn in rags, and colossal clouds are large") {
        check((patternOf("age scattered clouds")?.clumping ?: 0.0f) < -0.5f) { "scattered clouds clumped as ${patternOf("age scattered clouds")}" }
        check((patternOf("age patchy clouds")?.clumping ?: 0.0f) > 0.5f) { "patchy clouds clumped as ${patternOf("age patchy clouds")}" }
        check(patternOf("age torn clouds")?.torn == true) { "torn clouds were ${patternOf("age torn clouds")}" }
        val colossal = patternOf("age colossal clouds")
        check(colossal != null && colossal.cellScale > 3.0f && !colossal.drawsItsOwnPicture) { "colossal clouds were $colossal" }
    }

    test("an Age that says nothing of its clouds keeps vanilla's") {
        check(patternOf("age") == null) { "a plain Age shaped its clouds: ${patternOf("age")}" }
    }
})

private const val SEED = 17L
