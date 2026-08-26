package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import co.voik.agesandtheart.age.aspect.Ground
import net.minecraft.resources.Identifier
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
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
        val asAMonster = livingWith("minecraft:wither", MobCategory.MONSTER, skyIsOpen = true)
        check("wither" in asAMonster) { "a wither did not arrive in the monster pass: $asAMonster" }
        val asACreature = livingWith("minecraft:wither", MobCategory.CREATURE, skyIsOpen = true)
        check("wither" !in asACreature) { "a wither arrived in the creature pass: $asACreature" }
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
        ).at(null, MobCategory.MONSTER, true, BlockPos.ZERO, aMeadow()).unwrap()
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
     * **How the big ones are kept apart.** A spawn attempt knows its position and nothing else, so a rule
     * about *how many* dragons there are is not answerable there — where a rule about *where* one may be
     * tried is, and gives the same thing: one window per cell, so an Age of dragons is spread over the
     * country rather than piled into one valley.
     */
    test("a creature held apart is tried in one window of each cell") {
        fun triedAt(x: Int, z: Int) = "warden" in Spawns.livingIn(
            Options(mapOf(Spawns.LIVES.name to listOf("minecraft:warden"))),
            MinecraftRegistries.spawning,
        ).at(null, MobCategory.MONSTER, false, BlockPos(x, 30, z), aMeadow()).unwrap()
            .map { it.value().type().builtInRegistryHolder().key().identifier().path }

        // The warden's own cell is 192 blocks.
        check(triedAt(0, 0)) { "a warden was not tried at the corner of its own cell" }
        check(!triedAt(96, 96)) { "a warden was tried in the middle of a cell it is held out of" }
        // And the cell repeats, in both directions and on both sides of the origin.
        check(triedAt(192, 192)) { "the grid did not repeat" }
        check(triedAt(-192, -192)) { "the grid did not repeat behind the origin" }
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
private fun livingWith(claim: String, category: MobCategory, skyIsOpen: Boolean): List<String> {
    val options = Options(mapOf(Spawns.LIVES.name to listOf(claim)))
    return Spawns.livingIn(options, MinecraftRegistries.spawning)
        .at(null, category, skyIsOpen, BlockPos.ZERO, aMeadow())
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
