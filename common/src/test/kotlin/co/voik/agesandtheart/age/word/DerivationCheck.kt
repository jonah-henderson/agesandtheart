package co.voik.agesandtheart.age.word

import io.kotest.core.spec.style.FunSpec

/**
 * How a derivation rule decides it applies (`notes/the-tag-layer.md` §4).
 *
 * Registry-free: these are the matching rules themselves, and nothing here needs a world.
 */
class DerivationCheck : FunSpec({

    /**
     * **A rule matches a whole key and never a substring**, which is the property the whole layer's
     * safety rests on. `by_kind` keys on the `Feature` the game places with — `minecraft:ore`, the
     * *algorithm* — so a mod writing `mymod:ore_zinc` matches because its feature is vanilla's, while a
     * block called `heavy_core` cannot become an ore by containing the letters.
     */
    test("a rule matches a whole key, never a substring") {
        val rules = Derivation(byKind = mapOf("ore" to mapOf("buried" to 1.0)))
        check(rules.profileFor(emptyList(), listOf("ore")).isNotEmpty()) { "the exact kind did not match" }
        for (nearMiss in listOf("heavy_core", "nautilus_core", "ores", "ore_zinc", "core", "restore")) {
            check(rules.profileFor(emptyList(), listOf(nearMiss)).isEmpty()) {
                "'$nearMiss' matched a rule keyed 'ore', so the lookup is not on the whole key"
            }
        }
    }

    /** The same of a tag, where the whole id including its namespace has to agree. */
    test("a tag rule matches the whole id") {
        val rules = Derivation(byTag = mapOf("#minecraft:logs" to mapOf("wooded" to 1.0)))
        check(rules.profileFor(listOf("#minecraft:logs"), emptyList()).isNotEmpty()) { "the exact tag missed" }
        for (nearMiss in listOf("#c:logs", "minecraft:logs", "#minecraft:logs_that_burn", "#minecraft:log")) {
            check(rules.profileFor(listOf(nearMiss), emptyList()).isEmpty()) {
                "'$nearMiss' matched a rule keyed '#minecraft:logs'"
            }
        }
    }

    /**
     * **Two rules calling a thing the same are two reasons for one fact**, not twice as much of it — a
     * feature that is `wooded` by its type and by the logs it places is wooded once, at the stronger claim.
     */
    test("claims take the highest, never the sum") {
        val rules = Derivation(
            byKind = mapOf("tree" to mapOf("wooded" to 0.6)),
            byTag = mapOf("#minecraft:logs" to mapOf("wooded" to 1.0)),
        )
        val both = rules.profileFor(listOf("#minecraft:logs"), listOf("tree"))
        check(both["wooded"] == 1.0) { "two claims on one tag came out ${both["wooded"]}, not the stronger" }
    }

    /**
     * **A tag read off something the member merely contains is worth the share that carries it.** A
     * mushroom patch names one block and it is a mushroom; a fallen birch names four and one is, so it is
     * a quarter fungal — which is what keeps every tree in the game out of the fungi.
     */
    test("a tag believed in part claims in proportion") {
        val rules = Derivation(byTag = mapOf("#agesandtheart:fungal" to mapOf("fungal" to 1.0)))
        val wholly = rules.profileFor(mapOf("#agesandtheart:fungal" to 1.0), emptyList())
        val aQuarter = rules.profileFor(mapOf("#agesandtheart:fungal" to 0.25), emptyList())
        check(wholly["fungal"] == 1.0) { "a block that is the whole of a feature claimed ${wholly["fungal"]}" }
        check(aQuarter["fungal"] == 0.25) { "a quarter of a feature claimed ${aQuarter["fungal"]}" }
    }
})
