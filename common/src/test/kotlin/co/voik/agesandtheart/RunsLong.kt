package co.voik.agesandtheart

import io.kotest.core.NamedTag

/**
 * Marks a check that takes ten seconds or more. It is left out of `:common:test` and `:common:serverTest` and run
 * by `./gradlew :common:longTest` before a release; while developing, what it checks is seen in the game.
 *
 * `@Tags(RUNS_LONG)` on a spec, `.config(tags = setOf(RUNS_LONG_TAG))` on one test. A test another depends on takes
 * its whole spec with it, or the dependent would run without what it reads.
 */
const val RUNS_LONG = "RunsLong"

val RUNS_LONG_TAG = NamedTag(RUNS_LONG)
