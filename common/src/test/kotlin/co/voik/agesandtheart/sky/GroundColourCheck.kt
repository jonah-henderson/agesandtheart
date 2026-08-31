package co.voik.agesandtheart.sky

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Colour
import co.voik.agesandtheart.age.aspect.Options
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier

/**
 * What a sentence about the grass turns into — the half that is arithmetic over options and needs no level.
 *
 * Two claims are held here and both are the reason the aspects are separate. **Grass is not leaves**: no
 * one saying `purple grass` means the trees as well, so a word reaching both would be wrong far more often
 * than it was convenient. And **a sited clause paints one corner**: `purple grass in swamp` has to leave
 * the rest of the world alone, which is the difference between a corner and a level.
 */
@Tags(NEEDS_REGISTRIES)
class GroundColourCheck : FunSpec({

    val seed = 4242L
    val swamp = Identifier.fromNamespaceAndPath("minecraft", "swamp")
    val jungle = Identifier.fromNamespaceAndPath("minecraft", "jungle")

    fun described(vararg parts: Pair<Aspect, Options>) = Described(options = parts.toMap())

    fun options(vararg chosen: Pair<String, List<String>>) = Options(chosen.toMap())

    fun grassOf(vararg spelled: String) =
        Aspect.GRASS to options(Atmosphere.GRASSCOLOUR.name to spelled.toList())

    fun leavesOf(vararg spelled: String) =
        Aspect.LEAVES to options(Atmosphere.LEAFCOLOUR.name to spelled.toList())

    fun lookOf(vararg parts: Pair<Aspect, Options>, biome: Identifier? = null) =
        Atmosphere.lookIn(described(*parts), seed, biome)

    val purple = Colour.named("purple") ?: error("no purple")
    val red = Colour.named("red") ?: error("no red")

    test("an Age that says nothing leaves the ground alone") {
        val look = lookOf()
        check(look.grass == null && look.foliage == null && look.dryFoliage == null) {
            "An Age nobody described repainted its ground anyway"
        }
    }

    test("purple grass is purple grass") {
        check(lookOf(grassOf("purple")).grass == purple) { "`purple grass` did not reach the grass" }
    }

    test("and leaves it at that") {
        // The reason `grass` and `leaves` are two aspects. One word doing both would be wrong every time
        // somebody meant only the one they said.
        val look = lookOf(grassOf("purple"))
        check(look.foliage == null) { "`purple grass` turned the leaves purple too" }
        check(look.dryFoliage == null) { "`purple grass` turned the leaf litter purple too" }
    }

    test("leaves are their own word, and take the litter with them") {
        // Litter and dead brush are leaves that have dried, so one word covers both — the same argument as
        // above, one level down: green litter under purple trees is the mistake, not the fix.
        val look = lookOf(leavesOf("red"))
        check(look.foliage == red) { "`red leaves` did not reach the leaves" }
        check(look.dryFoliage == red) { "`red leaves` left the litter behind" }
        check(look.grass == null) { "`red leaves` turned the grass red" }
    }

    test("both at once keep to themselves") {
        val look = lookOf(grassOf("purple"), leavesOf("red"))
        check(look.grass == purple) { "The grass took the leaves' colour" }
        check(look.foliage == red) { "The leaves took the grass's colour" }
    }

    test("a sited clause names the corner it paints") {
        // `Atmosphere.cornersOf` is what gathers these, and it reads every confinable parameter of every
        // aspect — so a new sited dial needs nothing here, which is the thing being confirmed.
        val parts = described(grassOf("purple[in=minecraft:swamp]"))
        check(Atmosphere.cornersOf(parts) == listOf(swamp)) {
            "The swamp was not gathered as a corner: ${Atmosphere.cornersOf(parts)}"
        }
    }

    test("a sited clause paints that corner and nowhere else") {
        val sited = grassOf("purple[in=minecraft:swamp]")
        check(lookOf(sited, biome = swamp).grass == purple) { "The swamp's grass was not painted" }
        check(lookOf(sited).grass == null) { "A clause sited in one biome painted the whole world" }
        check(lookOf(sited, biome = jungle).grass == null) { "It painted a biome it was not sited in" }
    }

    test("a level colour and a corner colour can both be said") {
        // The interesting sentence: purple everywhere, red in the swamp. The bare value is the floor and
        // the bracketed one wins where it says, which is `Options.of`'s own rule rather than anything here.
        val both = grassOf("purple", "red[in=minecraft:swamp]")
        check(lookOf(both).grass == purple) { "The unsited colour did not hold everywhere else" }
        check(lookOf(both, biome = swamp).grass == red) { "The sited colour did not win in its own corner" }
    }

    test("grass and leaves can be sited in different biomes") {
        val parts = described(
            grassOf("purple[in=minecraft:swamp]"),
            leavesOf("red[in=minecraft:jungle]"),
        )
        check(Atmosphere.cornersOf(parts).toSet() == setOf(swamp, jungle)) {
            "Both corners were not gathered: ${Atmosphere.cornersOf(parts)}"
        }
        check(Atmosphere.lookIn(parts, seed, swamp).grass == purple) { "The swamp's grass was missed" }
        check(Atmosphere.lookIn(parts, seed, swamp).foliage == null) { "The swamp took the jungle's leaves" }
        check(Atmosphere.lookIn(parts, seed, jungle).foliage == red) { "The jungle's leaves were missed" }
        check(Atmosphere.lookIn(parts, seed, jungle).grass == null) { "The jungle took the swamp's grass" }
    }
})
