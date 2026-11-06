package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.language.ShippedLexicon.resolved
import co.voik.ephemeris.sky.StarField
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

@Tags(NEEDS_REGISTRIES)
class StarMotionCheck : FunSpec({

    fun starsOf(row: String): StarField = Sky.specFor(resolved(row).composition, SEED).stars

    test("slow and swift stars wheel slower and faster than the day, and motionless ones stand still") {
        check(starsOf("age slow stars").pace < StarField.VANILLAS_PACE) { "slow stars wheeled at ${starsOf("age slow stars").pace}" }
        check(starsOf("age swift stars").pace > StarField.VANILLAS_PACE) { "swift stars wheeled at ${starsOf("age swift stars").pace}" }
        check(starsOf("age motionless stars").pace == 0.0f) { "motionless stars wheeled at ${starsOf("age motionless stars").pace}" }
        check(starsOf("age").pace == StarField.VANILLAS_PACE) { "a plain Age's stars wheeled at ${starsOf("age").pace}" }
    }

    test("large stars are larger and small ones smaller") {
        check(starsOf("age large stars").size > StarField.VANILLAS_SIZE) { "large stars were ${starsOf("age large stars").size}" }
        check(starsOf("age small stars").size < StarField.VANILLAS_SIZE) { "small stars were ${starsOf("age small stars").size}" }
    }
})

private const val SEED = 23L
