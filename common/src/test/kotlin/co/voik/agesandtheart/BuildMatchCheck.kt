package co.voik.agesandtheart

import co.voik.agesandtheart.BuildMatch.Comparison
import io.kotest.core.spec.style.FunSpec

/** Which builds a server lets in: patch and minor releases mix, major ones do not, and 0.x breaks on minor. */
class BuildMatchCheck : FunSpec({

    fun comparing(server: String, client: String) = BuildMatch.compare(server = server, client = client)

    test("patch releases and development builds play together") {
        check(comparing("1.2.3+26.3", "1.2.0+26.3") == Comparison.PLAYS_TOGETHER)
        check(comparing("1.2.3+26.3", "1.2.3+26.3.dev4.abc1234.dirty") == Comparison.PLAYS_TOGETHER)
    }

    test("minor releases play together from 1.0 on") {
        check(comparing("1.4.0+26.3", "1.1.7+26.3") == Comparison.PLAYS_TOGETHER)
    }

    test("a major release does not, and says which side is behind") {
        check(comparing("2.0.0+26.3", "1.9.9+26.3") == Comparison.CLIENT_IS_BEHIND)
        check(comparing("1.9.9+26.3", "2.0.0+26.3") == Comparison.CLIENT_IS_AHEAD)
    }

    test("before 1.0 the minor release is the breaking one") {
        check(comparing("0.3.1+26.3", "0.3.0+26.3") == Comparison.PLAYS_TOGETHER)
        check(comparing("0.4.0+26.3", "0.3.9+26.3") == Comparison.CLIENT_IS_BEHIND)
    }

    test("builds without a release must match exactly") {
        check(comparing("unknown", "unknown") == Comparison.PLAYS_TOGETHER)
        check(comparing("unknown", "0.3.1+26.3") == Comparison.UNRELATED)
    }

    test("Ephemeris must match exactly, even where the mod's builds play together") {
        val server = Builds("0.3.1+26.3", "0.1.0")
        check(BuildMatch.refusal(server, Builds("0.3.0+26.3", "0.1.0")) == null)
        check(BuildMatch.refusal(server, Builds("0.3.1+26.3", "0.1.1")) != null)
    }

    test("numbers compare as numbers, not text") {
        check(comparing("10.0.0", "9.0.0") == Comparison.CLIENT_IS_BEHIND)
    }
})
