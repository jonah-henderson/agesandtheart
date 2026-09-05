package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import com.mojang.datafixers.util.Pair
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList

/**
 * **Describing a thing, against naming it** (world model §3) — what an evocative word may bring into a
 * world, and what it may only ask for more or less of.
 *
 * A vague word reaches dozens of members through tags, and an evocative one's weight is held at a floor
 * rather than dropped (§3.3), so its *faintest* reaches used to arrive as claims of a fifth: read as
 * namings, those were introductions. A beautiful Age came out with an inferno burning at a fifth strength,
 * a ghast in every biome and an ender dragon overhead (Jonah, 2026-09-03).
 *
 * Two rules, and each aspect takes the one that suits what it holds:
 *
 * - Where the biome's own judgment decides what belongs — features, biomes, structures — a description
 *   only bends what is there. `teeming trees` means the trees this biome grows, not all seventy of them.
 * - Where introducing is sound — spawns, because vanilla re-checks every placement, and phenomena, because
 *   nothing is happening to bend — a description brings about only what it asks *more* of.
 *
 * And summoning is naming, whatever the aspect: the Age places the golems, the wither and the dragon
 * itself, and a boss is not an atmosphere.
 */
@Tags(NEEDS_REGISTRIES)
class DescribingCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen).also {
            check(it.problems.isEmpty()) { "vocabulary problems: ${it.problems}" }
        }
    }

    fun composed(vararg pages: String): AgeComposition {
        val sentence = Grammar.read(vocabulary, listOf("age", *pages)) ?: error("not a book: ${pages.toList()}")
        return Resolver.resolve(vocabulary, sentence, SAMPLE_SEED).composition
    }

    /**
     * What a book puts on a biome's spawn menu **that was not on it** — asked of `Spawns` itself, over a
     * menu with nothing on it, so everything that comes back is an introduction and nothing here restates
     * the rule it is checking.
     */
    fun stocks(vararg pages: String): Set<String> {
        val living = Spawns.livingIn(composed(*pages).optionsFor(Aspect.SPAWNS, 0), vocabulary.spawning)
        val nothingOffered = WeightedList.of<MobSpawnSettings.SpawnerData>(emptyList())
        // **Many places, lit and dark.** A creature declares which light it belongs in, and one that keeps
        // its distance is only ever tried on its own lattice — asked at a single spot under a single
        // brightness, this saw no villager and no monster at all.
        val places = (0..FAR_ENOUGH step A_FEW_CHUNKS).flatMap { away ->
            listOf(BRIGHT_AS_IT_GETS, PITCH_DARK).map { light ->
                Spawns.Situation(BlockPos(away, SOME_HEIGHT, away), skyIsOpen = true, brightness = light)
            }
        }
        return places
            .flatMap { place ->
                MobCategory.entries.flatMap { category -> living.at(null, category, place, nothingOffered).unwrap() }
            }
            .mapNotNull { BuiltInRegistries.ENTITY_TYPE.getKey(it.value().type())?.toString() }
            .toSet()
    }

    fun summons(vararg pages: String): Set<String> =
        Spawns.claimedCreatures(composed(*pages).optionsFor(Aspect.SPAWNS, 0)).map { it.first.toString() }.toSet()

    fun happens(vararg pages: String): Set<String> =
        Phenomena.claimsIn(composed(*pages).optionsFor(Aspect.PHENOMENA, 0)).map(Claim::value).toSet()

    test("a description does not stock a menu with what it asks less of") {
        val stocked = stocks("beautiful")
        val hostile = stocked.filter { it in NOTHING_BEAUTIFUL_ABOUT_THEM }
        check(hostile.isEmpty()) { "a beautiful Age was given $hostile to spawn" }
    }

    test("a description still stocks a menu with what it asks more of") {
        // No biome in the game offers a wandering trader, so this can only ever be an introduction — and
        // it is the case that says a description may make one at all.
        check("minecraft:wandering_trader" in stocks("villagers")) {
            "'villagers' put nobody on the menu: ${stocks("villagers")}"
        }
        check("minecraft:zombie" in stocks("undead")) { "'undead' stocked no zombie: ${stocks("undead")}" }
    }

    test("summoning takes naming, whatever a query brushed") {
        check(summons("beautiful").isEmpty()) { "a beautiful Age summoned ${summons("beautiful")}" }
        check(summons("foreboding").isEmpty()) { "a foreboding Age summoned ${summons("foreboding")}" }
        check("minecraft:ender_dragon" in summons("teeming", "minecraft:ender_dragon")) {
            "naming the dragon outright no longer summons one"
        }
    }

    /** The pool that starts empty, where a description asking for less asks for less of nothing. */
    /** The two phenomena that are seen rather than done — see [co.voik.agesandtheart.age.aspect.Phenomenon]. */
    val SIGHTS = setOf("aurora", "rainbow")

    test("a description brings about only the phenomenon it asks more of") {
        val admired = happens("beautiful")
        check(admired.isNotEmpty()) { "a beautiful Age had no weather at all" }
        check(admired.all { it in SIGHTS }) { "a beautiful Age was given a hazard: $admired" }
        // **By kind rather than by name.** Which hazards exist is a list that grows — a blizzard joined it
        // in 2026-09-05 and read as a failure here — where the rule that matters is that a dread word
        // reaches hazards and never sights.
        val dreaded = happens("foreboding")
        check(dreaded.isNotEmpty()) { "a foreboding Age had no weather at all" }
        check(SIGHTS.none { it in dreaded }) { "a foreboding Age was given something to look at: $dreaded" }
        check(happens("tempest") == setOf("tempest")) { "naming a tempest no longer brings one" }
    }

    /**
     * Biomes take the other rule: the table comes furnished and which biome belongs where is its own
     * judgment, so a description scales a biome where it stands and never summons one from elsewhere.
     */
    /**
     * And the table proves it, which the flags alone cannot: a described preference for a biome the table
     * has never heard of must earn no entries, where a named one is summoned in and given a home.
     */
    test("a described biome from elsewhere is never summoned into the table") {
        val biomes = MinecraftRegistries.worldgen.lookupOrThrow(Registries.BIOME)
        val keyed = MultiNoiseBiomeSourceParameterList.knownPresets()
            .getValue(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
        val table = Climate.ParameterList<Holder<Biome>>(
            keyed.values().map { Pair(it.first, biomes.getOrThrow(it.second) as Holder<Biome>) },
        )
        val from = Identifier.parse("minecraft:crimson_forest")
        fun holds(list: Climate.ParameterList<Holder<Biome>>) =
            list.values().count { it.second.unwrapKey().orElse(null)?.identifier() == from }
        check(holds(table) == 0) { "the overworld already grows $from, so this is checking nothing" }

        fun grownWith(preference: BiomePreference) =
            holds(BiomePreference.applied(table, listOf(preference), false, biomes, SAMPLE_SEED))

        check(grownWith(BiomePreference(from, WANTED, onlyWhereItGrows = true)) == 0) {
            "a description summoned $from into a table that never had it"
        }
        check(grownWith(BiomePreference(from, WANTED)) > 0) {
            "naming $from no longer summons it into a table that lacks it"
        }
    }

    test("a description never introduces a biome, and a naming still does") {
        val preferences = Biomes.preferencesIn(composed("beautiful").optionsFor(Aspect.BIOMES, 0))
        val introduced = preferences.filterNot { it.onlyWhereItGrows }.map { it.biome.toString() }.toSet()
        check(introduced == setOf("minecraft:sunflower_plains", "minecraft:jungle")) {
            "'beautiful' names exactly the two biomes no tag can reach, and introduced $introduced"
        }
        check(preferences.count { it.onlyWhereItGrows } > 1) {
            "'beautiful' reached nothing by query at all, so this is checking nothing"
        }
    }
})

private const val SAMPLE_SEED = 0x5EEDL

/** Well clear of the ground, so nothing is refused for standing in rock. */
private const val SOME_HEIGHT = 100

/** Past the widest spacing anything in the corpus keeps, so every lattice is met. */
private const val FAR_ENOUGH = 1024
private const val A_FEW_CHUNKS = 64

private const val BRIGHT_AS_IT_GETS = 15
private const val PITCH_DARK = 0

/** More than the world would have given it, which is what a claim strong enough to introduce asks. */
private const val WANTED = 2.0

/** Creatures a beautiful Age used to be given, every one of them asked for at a fifth. */
private val NOTHING_BEAUTIFUL_ABOUT_THEM = setOf(
    "minecraft:ghast", "minecraft:blaze", "minecraft:ender_dragon", "minecraft:warden",
    "minecraft:creeper", "minecraft:wither",
)
