package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import co.voik.agesandtheart.age.aspect.Ground
import co.voik.agesandtheart.age.aspect.Hour
import co.voik.agesandtheart.age.aspect.Lit
import net.minecraft.resources.Identifier
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.SpawnPlacements
import net.minecraft.world.entity.SpawnPlacementTypes
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.MobSpawnSettings

/**
 * **A creature a world never offered, written into one** — the same operation as skewing one that was
 * already there, from zero (world model §6).
 *
 * Safe because a biome's spawn list is a **menu and not a promise**: every attempt vanilla makes goes
 * through `SpawnPlacements.isSpawnPositionOk` and `checkSpawnRules`, so a cod written into a desert is
 * refused at the position. What that leaves us to answer is the two things vanilla cannot — which pass a
 * creature arrives in, and where the ones it never spawns at all may be tried.
 */
@Tags(NEEDS_REGISTRIES)
class SpawningCheck : FunSpec({

    /** **The point of the feature**: naming a creature the world does not offer puts it in the world. */
    test("a creature the world never offered arrives") {
        val arrived = livingWith("minecraft:warden", MobCategory.MONSTER, skyIsOpen = false)
        check("warden" in arrived) { "a warden was written and did not arrive: $arrived" }
        // And what was already there is untouched — this adds, it does not replace.
        check("cow" in arrived) { "adding a creature took the meadow's own away: $arrived" }
    }

    /**
     * **A creature arrives in the pass its own category names.** Vanilla's spawner runs a pass per
     * category and asks the generator for that pass's list, so a monster offered to the creature pass
     * would be tried under the creature rules — in daylight, on grass, against the wrong cap.
     */
    test("a creature arrives in its own pass and no other") {
        val asAMonster = livingWith("minecraft:illusioner", MobCategory.MONSTER, skyIsOpen = true)
        check("illusioner" in asAMonster) { "an illusioner did not arrive in the monster pass: $asAMonster" }
        val asACreature = livingWith("minecraft:illusioner", MobCategory.CREATURE, skyIsOpen = true)
        check("illusioner" !in asACreature) { "an illusioner arrived in the creature pass: $asACreature" }
    }

    /**
     * **A golem is carried past a refusal vanilla makes of everyone else.** `SpawnerData`'s constructor
     * reads `this.type = type.getCategory() == MobCategory.MISC ? EntityType.PIG : type`, so a misc entity
     * put in a list *becomes a pig* — which is how the first attempt at this filled meadows with pork.
     *
     * The guard stays for every other path and is lifted only where the Age's own words named the
     * creature, which is what `SpawnerDataMixin` is and where it is reached from.
     */
    test("a golem never arrives as a pig, whatever else it does") {
        check(EntityType.SNOW_GOLEM.category == MobCategory.MISC) {
            "a snow golem is no longer misc, so this check is guarding nothing"
        }
        // Vanilla's own refusal, pinned: if this ever stops being true the bypass can go.
        val substituted = MobSpawnSettings.SpawnerData(EntityType.SNOW_GOLEM, 1, 1).type()
        check(substituted == EntityType.PIG) {
            "vanilla no longer swaps a misc entity for a pig — SpawnerDataMixin may be deleted"
        }
        // **Offline there is no mixin**, since nothing transforms classes outside a launched game — so what
        // this can hold is the half that matters most: the entry is dropped rather than offered as the pig
        // vanilla made of it. `SpawningOnServerCheck` holds the other half, where the golem does arrive.
        val arrived = livingWith("minecraft:snow_golem", MobCategory.CREATURE, skyIsOpen = true)
        check("pig" !in arrived) { "a golem was written and a pig arrived: $arrived" }
    }

    /**
     * **A creature vanilla never spawns has no placement rules**, so `SpawnPlacements` answers
     * `NO_RESTRICTIONS` and a dragon would be tried inside a mountain. `art/spawning.json` says which need
     * the sky, and this is the gate.
     */
    /**
     * **Where a creature belongs, and it cuts both ways.** A dragon in a cave is not a surprise, it is a
     * bug with wings; a warden out on a hillside is the same mistake facing the other direction. Both are
     * read off `on_the_surface` and `under_the_ground` in `art/spawning.json` — see [Ground].
     */
    test("a creature is tried on the ground it belongs to and no other") {
        val illusionerAbove = livingWith("minecraft:illusioner", MobCategory.MONSTER, skyIsOpen = true)
        check("illusioner" in illusionerAbove) { "an illusioner could not arrive under the sky: $illusionerAbove" }
        val illusionerBelow = livingWith("minecraft:illusioner", MobCategory.MONSTER, skyIsOpen = false)
        check("illusioner" !in illusionerBelow) { "an illusioner was offered inside the rock: $illusionerBelow" }

        val wardenBelow = livingWith("minecraft:warden", MobCategory.MONSTER, skyIsOpen = false)
        check("warden" in wardenBelow) { "a warden could not arrive under the ground: $wardenBelow" }
        val wardenAbove = livingWith("minecraft:warden", MobCategory.MONSTER, skyIsOpen = true)
        check("warden" !in wardenAbove) { "a warden was offered out on the surface: $wardenAbove" }
    }

    /**
     * **And a creature nobody judged is tried anywhere**, which is vanilla's own behaviour and what keeps
     * the tags a list of judgements rather than a census. A zombie is in neither file.
     */
    test("a creature nobody judged is tried on either ground") {
        for (sky in listOf(true, false)) {
            val arrived = livingWith("minecraft:zombie", MobCategory.MONSTER, skyIsOpen = sky)
            check("zombie" in arrived) { "a zombie was refused with the sky open=$sky: $arrived" }
        }
    }

    /**
     * The control, and it is what a corpus that stopped carrying the grounds would trip: with both lists
     * empty every creature reads as belonging anywhere, and every gate above passes for a reason that has
     * nothing to do with the rule it is checking.
     */
    test("the grounds are actually read, or nothing above means anything") {
        val spawning = MinecraftRegistries.spawning
        fun groundOf(path: String) = spawning.groundOf(Identifier.withDefaultNamespace(path))
        check(groundOf("illusioner") == Ground.SURFACE) { "the surface list did not load" }
        check(groundOf("ender_dragon") == Ground.IN_THE_AIR) { "the air list did not load" }
        check(groundOf("warden") == Ground.UNDERGROUND) { "the underground list did not load" }
        check(groundOf("zombie") == Ground.ANYWHERE) { "a creature nobody judged was pinned" }
        // In both lists, which is a reader saying "yes, really both" where silence is nobody having looked.
        check(groundOf("silverfish") == Ground.ANYWHERE) { "a creature in both lists was pinned" }
    }

    /**
     * **A rung reaches an arrival too**, since arriving is skewing from zero and a quantifier is how a
     * writer says how much. Without this `teeming golems` and one golem would be the same sentence.
     */
    test("a rung scales what arrives") {
        fun weightOf(claim: String) = Spawns.livingIn(
            Options(mapOf(Spawns.LIVES.name to listOf(claim))),
            MinecraftRegistries.spawning,
        ).at(null, MobCategory.MONSTER, Spawns.Situation(BlockPos.ZERO, true, true, DARK), aMeadow()).unwrap()
            .firstOrNull { it.value().type() == EntityType.ILLUSIONER }?.weight()

        // An illusioner, which is held apart from nothing — see the test below for what a rung does to
        // one that is, where it moves the spacing and cannot move the weight.
        val plain = weightOf("minecraft:illusioner")
        val teeming = weightOf("minecraft:illusioner[amount=4.0]")
        checkNotNull(plain) { "the illusioner did not arrive at all" }
        checkNotNull(teeming) { "the illusioner did not arrive when asked for teemingly" }
        check(teeming > plain) { "a rung changed nothing: $plain then $teeming" }
    }

    /**
     * **A rung on a creature held apart moves the spacing**, because it has nowhere else to go: what is
     * held to a four-hundredth of the map is handed that back as weight, and something already most of
     * what arrives inside its own window cannot be asked for more loudly. `teeming ender_dragon` and a
     * plain one came out identical — both at the ceiling — until this (Jonah, 2026-08-26).
     */
    test("a rung on a creature held apart brings its windows closer instead") {
        fun spacingOf(density: Double) = MinecraftRegistries.spawning.of(DRAGON).spacedAt(density)

        val plain = spacingOf(1.0)
        val teeming = spacingOf(4.0)
        check(teeming < plain) { "asking for more dragons did not bring them closer: $plain then $teeming" }
        // As the square root, so it is the *population* that doubles rather than the spacing halving.
        check(teeming == plain / 2) { "four times as many should stand half as far apart: $plain then $teeming" }

        val sparse = spacingOf(0.25)
        check(sparse > plain) { "asking for fewer did not spread them out: $plain then $sparse" }
    }

    /**
     * **How the ones an Age places are kept apart**, which is a rule about *where* because a spawn attempt
     * knows its position and nothing else.
     *
     * Only for a placed creature now. In a biome list the same window made a creature absent from a
     * hundred and forty-three cells in a hundred and forty-four and most of what arrived in the one, which
     * walks as "they do not exist" and then "they are everywhere". Where `AgeSpawner` places one it also
     * counts what is already nearby, and the two together describe one arrangement.
     */
    test("a creature the Age places is tried in one window of each cell") {
        val dragon = MinecraftRegistries.spawning.of(Identifier.withDefaultNamespace("ender_dragon"))
        val spacing = dragon.spacedAt(1.0)
        fun triedAt(x: Int, z: Int) = dragon.mayBeTriedAt(x, z, spacing)

        check(spacing == 320) { "the dragon's own cell is $spacing" }
        check(triedAt(0, 0)) { "a dragon was not tried at the corner of its own cell" }
        check(!triedAt(160, 160)) { "a dragon was tried in the middle of a cell it is held out of" }
        // And the cell repeats, in both directions and on both sides of the origin.
        check(triedAt(320, 320)) { "the grid did not repeat" }
        check(triedAt(-320, -320)) { "the grid did not repeat behind the origin" }
    }

    /** And a creature that arrives by the ordinary spawner is held apart from nothing — weight scatters it. */
    test("a creature vanilla spawns is thinned by its weight alone") {
        for (natural in listOf("warden", "illusioner", "giant")) {
            val arrival = MinecraftRegistries.spawning.of(Identifier.withDefaultNamespace(natural))
            check(arrival.spacedAt(1.0) == 0) { "$natural is held apart, and nothing counts what arrives" }
        }
    }

    /**
     * **A creature the Age places itself is taken out of the natural list**, not merely refused by it.
     *
     * Vanilla declines these *after* the draw — a `MobCategory.MISC` entity outright, and anything whose
     * box will not clear where it stands — so offering one costs the biome's own creatures a share of
     * every attempt and puts nothing in the world. `AgeSpawner` has them instead.
     */
    test("what the Age places itself is never offered to vanilla's spawner") {
        for (sky in listOf(true, false)) {
            for (creature in listOf("iron_golem", "snow_golem", "ender_dragon")) {
                val arrived = livingWith("minecraft:$creature", MobCategory.MONSTER, skyIsOpen = sky)
                check(creature !in arrived) { "$creature was offered to the natural spawner: $arrived" }
            }
        }
    }

    /** And it is picked up by the one that will place it, at the ground the corpus gives it. */
    test("and is picked up by the Age's own spawner") {
        val placing = AgeSpawner.placing(
            Options(mapOf(Spawns.LIVES.name to listOf("minecraft:ender_dragon", "minecraft:iron_golem"))),
            MinecraftRegistries.spawning,
        )
        checkNotNull(placing) { "an Age that wrote a dragon and a golem places neither" }

        val grounds = placing.placedCreatures.associate { it.type to it.ground }
        check(grounds[EntityType.ENDER_DRAGON] == Ground.IN_THE_AIR) { "the dragon is placed on $grounds" }
        check(grounds[EntityType.IRON_GOLEM] == Ground.SURFACE) { "the golem is placed on $grounds" }
    }

    /**
     * **A creature an Age puts aloft is one vanilla places on the ground**, and that is why the aloft
     * placement skips vanilla's two standing tests rather than passing them.
     *
     * The fact that misled this twice: `ENDER_DRAGON` is *not* an unplaced type. `SpawnPlacements`
     * registers it `ON_GROUND` with `Mob::checkMobSpawnRules`, and both of those want a valid spawn block
     * directly below — which nothing thirty blocks up has. So a dragon put where a dragon belongs was
     * refused for not standing on anything, and the log said nothing because refusing is not an error.
     *
     * If vanilla ever stops placing it on the ground, the exemption stops being needed and this says so.
     */
    test("what an Age places aloft is placed on the ground by vanilla") {
        MinecraftRegistries.ensureStoodUp()
        for (aloft in listOf(EntityType.ENDER_DRAGON)) {
            check(SpawnPlacements.getPlacementType(aloft) === SpawnPlacementTypes.ON_GROUND) {
                "${aloft.description.string} is no longer placed on the ground, so the aloft exemption " +
                    "may not be needed — it exists because `ON_GROUND` wants a block below"
            }
        }
    }

    /**
     * **When a creature comes**, which vanilla has no notion of — its own rule is about light, so a zombie
     * spawns in a dark cave at noon. This is the judgement the light cannot make, and it reaches the list
     * vanilla is handed as well as the Age's own spawner.
     */
    test("a creature judged to one watch is not offered on the other") {
        val byDay = livingWith("minecraft:illusioner", MobCategory.MONSTER, skyIsOpen = true, isBrightOutside = true)
        check("illusioner" in byDay) { "an illusioner did not arrive by day: $byDay" }
        val byNight = livingWith("minecraft:illusioner", MobCategory.MONSTER, skyIsOpen = true, isBrightOutside = false)
        check("illusioner" !in byNight) { "an illusioner was offered after dark: $byNight" }
    }

    /** And one nobody judged comes whenever the light allows, which is most of them. */
    test("a creature nobody judged comes at any hour") {
        for (bright in listOf(true, false)) {
            val arrived = livingWith("minecraft:zombie", MobCategory.MONSTER, skyIsOpen = true, isBrightOutside = bright)
            check("zombie" in arrived) { "a zombie was refused with bright=$bright: $arrived" }
        }
    }

    /**
     * **And what light it comes in**, which vanilla does test — for the creatures it tests. A golem's own
     * rules read no light and a placed creature has none read over it, so this is the whole judgement for
     * those two rather than a second opinion.
     */
    test("a creature judged to one light is not offered in the other") {
        val dark = livingWith("minecraft:warden", MobCategory.MONSTER, skyIsOpen = false, brightness = DARK)
        check("warden" in dark) { "a warden did not arrive in the dark: $dark" }
        val lit = livingWith("minecraft:warden", MobCategory.MONSTER, skyIsOpen = false, brightness = LIT)
        check("warden" !in lit) { "a warden was offered somewhere lit: $lit" }
    }

    /** The control: the hours and the light are read off the corpus, or the tests above prove nothing. */
    test("the hours are actually read") {
        val spawning = MinecraftRegistries.spawning
        fun hourOf(path: String) = spawning.hourOf(Identifier.withDefaultNamespace(path))
        check(hourOf("illusioner") == Hour.BY_DAY) { "the day list did not load" }
        check(hourOf("wither") == Hour.BY_NIGHT) { "the night list did not load" }
        check(hourOf("zombie") == Hour.ANY) { "a creature nobody judged was pinned to a watch" }
        fun lightOf(path: String) = spawning.lightOf(Identifier.withDefaultNamespace(path))
        check(lightOf("warden") == Lit.IN_THE_DARK) { "the dark list did not load" }
        check(lightOf("zombie") == Lit.ANY) { "a creature nobody judged was pinned to a light" }
    }

    /** And an Age that wrote none of them carries no spawner at all, which is nearly every Age. */
    test("an Age that asked for none of them carries no spawner") {
        val ordinary = AgeSpawner.placing(
            Options(mapOf(Spawns.LIVES.name to listOf("minecraft:zombie"))),
            MinecraftRegistries.spawning,
        )
        check(ordinary == null) { "an Age that wrote only a zombie was given a spawner of its own" }
    }
})

/** The creatures a meadow holds after [claim] is written into it. */
private fun livingWith(
    claim: String,
    category: MobCategory,
    skyIsOpen: Boolean,
    isBrightOutside: Boolean = true,
    brightness: Int = DARK,
): List<String> {
    val options = Options(mapOf(Spawns.LIVES.name to listOf(claim)))
    return Spawns.livingIn(options, MinecraftRegistries.spawning)
        .at(null, category, Spawns.Situation(BlockPos.ZERO, skyIsOpen, isBrightOutside, brightness), aMeadow())
        .unwrap()
        .map { it.value().type().builtInRegistryHolder().key().identifier().path }
}

/** What a biome offers before anybody writes anything: two creatures and two monsters. */
private fun aMeadow(): WeightedList<MobSpawnSettings.SpawnerData> {
    MinecraftRegistries.ensureStoodUp()
    return WeightedList.of(
        Weighted(MobSpawnSettings.SpawnerData(EntityType.COW, 4, 4), 8),
        Weighted(MobSpawnSettings.SpawnerData(EntityType.SHEEP, 4, 4), 12),
        Weighted(MobSpawnSettings.SpawnerData(EntityType.ZOMBIE, 4, 4), 95),
    )
}

/** The dragon's own id, for the arrival read straight out of `art/spawning.json`. */
private val DRAGON: Identifier = Identifier.withDefaultNamespace("ender_dragon")

/** Either side of vanilla's own line between somewhere lit and somewhere a monster will come. */
private const val DARK = 0
private const val LIT = 15
