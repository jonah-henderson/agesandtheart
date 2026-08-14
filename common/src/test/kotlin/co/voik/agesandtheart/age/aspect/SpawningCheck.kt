package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
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
        val arrived = livingWith("minecraft:warden", MobCategory.MONSTER, skyIsOpen = true)
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
    test("what needs the sky is not tried under the ground") {
        val above = livingWith("minecraft:ender_dragon", MobCategory.MONSTER, skyIsOpen = true)
        check("ender_dragon" in above) { "a dragon could not arrive under an open sky: $above" }
        val below = livingWith("minecraft:ender_dragon", MobCategory.MONSTER, skyIsOpen = false)
        check("ender_dragon" !in below) { "a dragon was offered inside the rock: $below" }
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
            .firstOrNull { it.value().type() == EntityType.WARDEN }?.weight()

        val plain = weightOf("minecraft:warden")
        val teeming = weightOf("minecraft:warden[amount=4.0]")
        checkNotNull(plain) { "the warden did not arrive at all" }
        checkNotNull(teeming) { "the warden did not arrive when asked for teemingly" }
        check(teeming > plain) { "a rung changed nothing: $plain then $teeming" }
    }

    /**
     * **How the big ones are kept apart.** A spawn attempt knows its position and nothing else, so a rule
     * about *how many* dragons there are is not answerable there — where a rule about *where* one may be
     * tried is, and gives the same thing: one window per cell, so an Age of dragons is spread over the
     * country rather than piled into one valley.
     */
    test("a creature held apart is tried in one window of each cell") {
        fun triedAt(x: Int, z: Int) = "ender_dragon" in Spawns.livingIn(
            Options(mapOf(Spawns.LIVES.name to listOf("minecraft:ender_dragon"))),
            MinecraftRegistries.spawning,
        ).at(null, MobCategory.MONSTER, true, BlockPos(x, 80, z), aMeadow()).unwrap()
            .map { it.value().type().builtInRegistryHolder().key().identifier().path }

        check(triedAt(0, 0)) { "a dragon was not tried at the corner of its own cell" }
        check(!triedAt(160, 160)) { "a dragon was tried in the middle of a cell it is held out of" }
        // And the cell repeats, in both directions and on both sides of the origin.
        check(triedAt(320, 320)) { "the grid did not repeat" }
        check(triedAt(-320, -320)) { "the grid did not repeat behind the origin" }
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
