package co.voik.agesandtheart.worldgen

/**
 * Marks a check that reads the **shape of a landform** — `./gradlew :common:landformTest`.
 *
 * These are not held to the same bargain as the rest of the suite, and the reasons are specific to what
 * they are (Jonah, 2026-08-05):
 *
 * - **They are almost all of the cost.** Sixteen specs sampling millions of terrain columns apiece were
 *   over three quarters of a five-minute cycle, paid on every commit by everyone, including the many that
 *   touch no terrain at all.
 * - **They are the fiddliest.** What they assert is the emergent shape of layered noise — that a share of
 *   the ground is low, that no neighbouring columns step — so their thresholds are calibrated against a
 *   field rather than derived from it, and hand-authoring a landform trips one long before the landform is
 *   wrong.
 * - **They change least.** The terrain system is built and shipped; a week of writing words does not move
 *   a drainage network.
 *
 * **So `landformTest` warns rather than fails** (`ignoreFailures`), and the honest cost of that is worth
 * stating: a check that cannot fail a build is a check that can drift. What holds it is that the task
 * prints a banner naming every failure and the count, so a run that went red is not a line to scroll past
 * — and that these are read when landforms are being worked on, which is exactly when someone is looking.
 *
 * **Run only when landforms are being changed** (Jonah, 2026-08-05) — nothing else may pull it in.
 * `build` and `check` reach `test` and no further, and that is not an accident to be tidied up later: a
 * task wired into a gate it cannot fail is the worst of both, minutes spent on an answer nobody has to
 * read. Not in `check`, not in `build`, and not in CI.
 *
 * A check belongs here if it **samples a field and asserts a shape**. Pure ones do not, however
 * terrain-adjacent they are: `SpansCheck` is arithmetic, `ChooseCheck` is a selection rule, `DepthCacheCheck`
 * is a cache agreeing with the thing it caches, and `BiomeWeightCheck` is a table. Those stay in the suite
 * everyone runs, where they cost nothing and catch real bugs.
 */
const val NEEDS_LANDFORMS = "NeedsLandforms"
