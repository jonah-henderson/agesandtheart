package co.voik.agesandtheart.desk

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries

/**
 * The writer profession, as far as offline can see it.
 *
 * **The job site's block states are not checked here, and cannot be.** `jobSiteStates` needs the desk
 * block, every block builds an intrusive holder in its constructor, and `Bootstrap.bootStrap()` freezes
 * the registries that hand those out — so `AgeContent` cannot be touched from a spec at all. What is left
 * is the half that *is* a string joining code to data, which is the half that drifts.
 */
@Tags(NEEDS_REGISTRIES)
class WriterProfessionCheck : FunSpec({

    /**
     * The five trade sets are named in code and shipped as data, with nothing but the string joining them.
     * `WriterStockCheck` holds the other end — that a file exists for each of these.
     */
    test("the profession names one trade set per level") {
        val profession = WriterProfession.profession()
        val named = (1..5).map { level ->
            val key = profession.getTrades(level)
            checkNotNull(key) { "level $level has no trade set, so a writer reaching it gains no trades" }
            check(key.registry() == Registries.TRADE_SET.identifier()) {
                "level $level's trade set is registered against ${key.registry()}"
            }
            key.identifier().toString()
        }
        val expected = (1..5).map { "agesandtheart:writer_level_$it" }
        check(named == expected) { "the trade sets are named $named, and the shipped files are $expected" }
    }

    /** A sixth level would be a set nothing ships, and vanilla's cap is five. */
    test("no level beyond the fifth claims a trade set") {
        check(WriterProfession.profession().getTrades(6) == null) {
            "level 6 names a trade set, and villagers stop at 5"
        }
    }
})
