package co.voik.agesandtheart.age

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec

/**
 * **That everything a screen can name has a name**, which the seismograph found out the hard way.
 *
 * `Manifestation` grew to seven entries and the language file had five, so an Age whose instability bought
 * a blizzard or a meteor storm listed the raw translation key on the instrument's own screen. Nothing
 * fails, nothing logs, and it only shows up if a walk happens to write an Age unstable enough to buy that
 * particular one — which is why it survived a walk that settled everything else about the instrument.
 *
 * The list is read out of the enum rather than written down here, so adding an entry is what fails this.
 */
class NamedForReadingCheck : FunSpec({

    test("every manifestation is named on the seismograph") {
        val said = shippedEnglish()
        for (manifestation in Manifestation.entries) {
            val key = "$ON_THE_SEISMOGRAPH${manifestation.key}"
            check(said.has(key)) {
                "instability can buy ${manifestation.key} and the seismograph has no name for it, so it " +
                    "would list '$key' at a player"
            }
        }
    }
}) {
    private companion object {
        private const val ON_THE_SEISMOGRAPH = "container.agesandtheart.seismograph.manifest_"

        /** The file that actually ships, read off the classpath rather than by path. */
        private fun shippedEnglish() =
            NamedForReadingCheck::class.java.getResourceAsStream("/assets/agesandtheart/lang/en_us.json")
                .use { JsonParser.parseReader(it!!.reader()).asJsonObject }
    }
}
