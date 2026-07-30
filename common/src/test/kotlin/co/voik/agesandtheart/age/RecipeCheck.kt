package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Density
import co.voik.agesandtheart.age.aspect.Population
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceLocation

/**
 * Checks the one thing about [AgeRecipe] that cannot be checked by looking at a world: that what we
 * write to disk is what we read back, and that an Age already written still names something.
 *
 * A recipe is the *only* record of what an Age is — the dimension is rebuilt from it on every open —
 * so a recipe that fails to round-trip does not corrupt a save, it silently replaces someone's world
 * with a different one. That makes this worth a check of its own rather than a wait for symptoms.
 *
 * Registry-free by design, so it runs offline in a second: recipes are plain data, and none of the
 * codecs here reach for a registry. That is also why it carries no `NeedsRegistries` tag while
 * [CodecCheck] does — and why `CodecCheck` exists at all, since staying registry-free means this file
 * never loads a chunk generator.
 */
class RecipeCheck : FunSpec({

    /** Every preset survives the trip to NBT and back, unchanged — composed and bespoke alike. */
    withData(nameFn = { "the '${it.key}' preset round-trips" }, AgePreset.entries.toList()) { preset ->
        roundTrips(AgeRecipe(AgeRecipe.worldFor(preset), seed = SAMPLE_SEED), preset.key)
    }

    /**
     * Every preset of every aspect survives too.
     *
     * Presets are no longer whole worlds, so "every [AgePreset] round-trips" no longer reaches most of
     * what a recipe can say: an aspect preset only appears above if some classic demo Age happens to name
     * it. This asks each one directly, so a family can gain a member without gaining a blind spot.
     */
    withData(
        nameFn = { "the '${it.aspect.key}=${it.key}' aspect preset round-trips" },
        everyAspectPreset(),
    ) { preset ->
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS)).withPreset(preset.aspect, preset.key)
        roundTrips(
            AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED),
            "${preset.aspect.key}=${preset.key}",
        )
    }

    /**
     * An option nobody recognises comes back out again.
     *
     * The invariant [co.voik.agesandtheart.age.aspect.Options] promises, and the one most easily lost to a
     * well-meant cleanup: dropping an unreadable option on load is how a save quietly becomes a different
     * save, since the recipe is all there is. It has to survive the *write* as well as the read.
     */
    test("options it cannot understand round-trip") {
        val composition = AgeComposition(terrains = listOf(Terrain.PYRAMIDS))
            .withOption(Aspect.TERRAIN, Terrain.ARRANGEMENT.name, "rings")
            .withOption(Aspect.TERRAIN, "elevation", "towering")
        check(composition.unknownOptions == listOf("terrain.elevation")) {
            "Expected 'elevation' to be reported as unrecognised, got ${composition.unknownOptions}"
        }
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED), "unknown options")
        check(decoded.composition?.options?.of(Aspect.TERRAIN)?.chosen?.get("elevation") == listOf("towering")) {
            "An unrecognised option was dropped in the round trip: $decoded"
        }
    }

    /**
     * A parameter holding several values survives — and one holding a single value is spelled exactly as it
     * always was.
     *
     * The second half is the load-bearing one. Several values on a parameter is what a grammatical
     * *conjunction* will resolve to (design §3.2 — "blackstone and tuff" is two words joined, never one word
     * meaning both), so `Options` had to start holding lists before the grammar exists. That change was made
     * free by spelling a lone value as a bare string, which is what every recipe already on disk contains —
     * so no generator version had to move. If that ever regresses, every Age ever written reads back wrong,
     * and nothing else would notice.
     */
    test("a mingled parameter round-trips") {
        val one = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withOption(Aspect.TERRAIN, Terrain.STONE.name, "minecraft:blackstone")
        val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, AgeRecipe(AgeWorld.Composed(one), SAMPLE_SEED))
            .getOrThrow { problem -> IllegalStateException("a single material would not encode: $problem") }
        check("[" !in encoded.toString()) {
            "A lone option was written as a list, so every recipe on disk now reads differently: $encoded"
        }
        roundTrips(AgeRecipe(AgeWorld.Composed(one), SAMPLE_SEED), "one material")

        val mingled = one.withOptions(
            Aspect.TERRAIN,
            Terrain.STONE.name,
            listOf("minecraft:blackstone", "minecraft:tuff"),
        )
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(mingled), SAMPLE_SEED), "two mingled materials")
        check(decoded.composition?.options?.of(Aspect.TERRAIN)?.allOf(Terrain.STONE)?.size == 2) {
            "A mingled parameter came back as ${decoded.composition?.options?.of(Aspect.TERRAIN)}"
        }
        val spelling = mingled.toString()
        check("terrain.stone=minecraft:blackstone,minecraft:tuff" in spelling) {
            "A mingled parameter spells itself wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == mingled) { "'$spelling' does not read back as itself" }
    }

    /**
     * A population's `only` and `except` survive the trip — the marks `Claim` spells them with.
     *
     * The recipe is the only record of an Age (§4.6), so an exclusion that failed to round-trip would be an
     * Age that quietly regained the thing it was written to be without. It nearly *did* fail: a marked value
     * is not a well-formed `ResourceLocation`, so reading it through `allOf` filters it out and the exclusion
     * simply does not happen — which is why `Options.claimsOn` strips the mark before validating and this
     * asserts the result rather than the spelling alone.
     */
    test("an excluded structure round-trips") {
        val written = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withPreset(Aspect.STRUCTURES, Structures.VANILLA.key)
            .withOptions(
                Aspect.STRUCTURES,
                Structures.BUILT.name,
                listOf("!minecraft:villages@teeming", "minecraft:woodland_mansions", "-minecraft:ocean_monuments"),
            )
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(written), SAMPLE_SEED), "a steered population")
        val asked = Population.of(
            decoded.composition?.optionsFor(Aspect.STRUCTURES, 0)?.claimsOn(Structures.BUILT).orEmpty(),
        )
        check(asked.exclusive) { "'only' did not survive the round trip: $asked" }
        check(asked.wanted.map { it.value } == listOf("minecraft:villages", "minecraft:woodland_mansions")) {
            "the wanted sets came back as ${asked.wanted}"
        }
        // All three marks at once, because they are read from one string and a greedy parse would eat the others.
        check(asked.wanted.first().density == Density.TEEMING) {
            "a density rung did not survive beside an 'only': ${asked.wanted.first()}"
        }
        check(asked.wanted.last().density == Density.ORDINARY) { "an unmarked value invented a density rung" }
        check(asked.struck == listOf("minecraft:ocean_monuments")) { "the struck sets came back as ${asked.struck}" }

        val spelling = written.toString()
        check(
            "structures.built=!minecraft:villages@teeming,minecraft:woodland_mansions,-minecraft:ocean_monuments"
                in spelling,
        ) {
            "a steered population spells itself wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == written) { "'$spelling' does not read back as itself" }
    }

    /**
     * Two territories of one aspect, steered differently — copper spires beside andesite hills.
     *
     * The property an aspect-wide `Options` could not hold at all: one parameter named twice with two values,
     * which used to contend so that one won and painted both territories. A sentence that reads perfectly and
     * quietly does something else, so nothing but this notices if it comes back.
     *
     * Checks both directions, because the collapse is as load-bearing as the division: territories that
     * *agree* must still spell themselves once, or every recipe already on disk reads differently.
     */
    test("two territories are steered apart") {
        val divided = AgeComposition(terrains = listOf(Terrain.SPIRE_ISLANDS, Terrain.HILLS))
            .withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf("minecraft:copper_block"))
            .withOptionsFor(Aspect.TERRAIN, 1, Terrain.STONE.name, listOf("minecraft:andesite"))

        for ((member, expected) in listOf("minecraft:copper_block", "minecraft:andesite").withIndex()) {
            val held = divided.optionsFor(Aspect.TERRAIN, member).allOf(Terrain.STONE)
            check(held == listOf(expected)) { "Territory $member holds $held rather than $expected" }
        }

        val spelling = divided.toString()
        check("terrain=spire_islands{stone=minecraft:copper_block},hills{stone=minecraft:andesite}" in spelling) {
            "Two steered territories spell themselves wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == divided) { "'$spelling' does not read back as itself" }

        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(divided), SAMPLE_SEED), "two territories steered apart")
        check(
            decoded.composition?.optionsFor(Aspect.TERRAIN, 1)?.allOf(Terrain.STONE) == listOf("minecraft:andesite"),
        ) {
            "The second territory's material did not survive the codec: ${decoded.composition}"
        }

        // The other half: territories that agree collapse back to one entry, spelled the way they always were.
        val agreeing = AgeComposition(terrains = listOf(Terrain.SPIRE_ISLANDS, Terrain.HILLS))
            .withOption(Aspect.TERRAIN, Terrain.STONE.name, "minecraft:tuff")
        val together = agreeing.toString()
        check("terrain.stone=minecraft:tuff" in together) {
            "Agreeing territories stopped spelling themselves once: '$together'"
        }
        check(AgeComposition.parse(together).getOrThrow() == agreeing) { "'$together' does not read back as itself" }
    }

    /**
     * What a composition prints is what the composer reads back.
     *
     * These are two hand-written halves of one grammar, and nothing but this makes them agree. The cost of
     * their drifting apart is that `/age list` prints recipes `/age compose` cannot accept — the sort of
     * thing that survives for months, because each half looks right on its own.
     */
    test("compositions are spelled the way they are read") {
        val compositions = everyAspectPreset().map { preset ->
            AgeComposition(terrains = listOf(Terrain.HILLS)).withPreset(preset.aspect, preset.key)
        } + AgeComposition(terrains = listOf(Terrain.PYRAMIDS))
            .withOption(Aspect.TERRAIN, Terrain.ARRANGEMENT.name, "rings")
            .withOption(Aspect.SEA, Sea.DEPTH.name, "deep")

        for (composition in compositions) {
            val spelling = composition.toString()
            val read = AgeComposition.parse(spelling).getOrThrow()
            check(read == composition) { "'$spelling' reads back as '$read', which is not what wrote it" }
        }
    }

    /**
     * An Age written before aspects existed still opens, and opens as the same Age.
     *
     * The migration lives in a codec default rather than anywhere obvious, so it is exactly the kind of
     * path that goes unexercised until somebody's save is the thing exercising it.
     */
    test("recipes written before slots still read") {
        for (preset in AgePreset.entries) {
            val written = CompoundTag().apply {
                put("preset", StringTag.valueOf(preset.key))
                putLong("seed", SAMPLE_SEED)
                putInt(GENERATOR_VERSION_KEY, PRE_SLOTS_GENERATOR_VERSION)
            }
            val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
                .getOrThrow { problem ->
                    IllegalStateException("a pre-aspects '${preset.key}' would not load: $problem")
                }
            check(decoded.world == AgeRecipe.worldFor(preset)) {
                "A pre-aspects '${preset.key}' migrated to ${decoded.world}, not ${AgeRecipe.worldFor(preset)}"
            }
            check(decoded.generatorVersion == PRE_SLOTS_GENERATOR_VERSION) {
                "Migration overwrote the stamp on '${preset.key}', which is how an Age forgets what made it"
            }
        }
    }

    /**
     * A terrain aspect holding several presets survives, and prints in a form the composer reads back.
     *
     * The set is the whole point of regions (§3.4), and it is the part of the recipe most recently changed
     * shape — so it is the part most likely to round-trip as *something*, just not the same something.
     */
    test("a set-valued landform round-trips") {
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS, Terrain.CAVERNS))
            .withPreset(Aspect.SEA, Sea.WATER.key)
            .withPresets(Aspect.SEA, listOf(Sea.WATER.key, Sea.LAVA.key))
            .withPresets(Aspect.CARVERS, listOf(Carvers.CAVES.key, Carvers.SOLID.key))
        val recipe = AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED, character = SAMPLE_CHARACTER)
        val decoded = roundTrips(recipe, "a three-terrain Age")

        check(decoded.composition?.terrains == composition.terrains) {
            "The terrain set came back as ${decoded.composition?.terrains}, not ${composition.terrains}"
        }
        check(decoded.composition?.seas == composition.seas) {
            "The sea set came back as ${decoded.composition?.seas}"
        }
        check(decoded.composition?.carvers == composition.carvers) {
            "The carving set came back as ${decoded.composition?.carvers}"
        }
        check(decoded.character == SAMPLE_CHARACTER) {
            "An Age's character did not survive: ${decoded.character}, not $SAMPLE_CHARACTER"
        }

        val spelling = composition.toString()
        check("terrain=hills,pillars,caverns" in spelling) { "A set should print comma-joined, got '$spelling'" }
        check("carvers=caves,solid" in spelling) { "So should a carving set, got '$spelling'" }
        // Ids, because the sea aspect is open (design §3.1) — the referent is the value, not a preset naming it.
        check("sea=minecraft:water,minecraft:lava" in spelling) { "And a sea set, got '$spelling'" }
        check(AgeComposition.parse(spelling).getOrThrow() == composition) {
            "'$spelling' does not read back as what wrote it"
        }
    }

    /**
     * An Age written before terrain was a set still opens, as the single-terrain Age it was.
     *
     * Its `terrain` is a bare string where today's is a list, and both spellings have to keep working —
     * this is the second time that field has changed shape, and the first migration is still load-bearing.
     */
    test("recipes written before regions still read") {
        val written = CompoundTag().apply {
            put(
                "world",
                CompoundTag().apply {
                    put("kind", StringTag.valueOf("composed"))
                    put("terrain", StringTag.valueOf(Terrain.ERODED.key))
                    put("sea", StringTag.valueOf(Sea.WATER.key))
                },
            )
            putLong("seed", SAMPLE_SEED)
            putInt(GENERATOR_VERSION_KEY, PRE_REGIONS_GENERATOR_VERSION)
        }
        val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
            .getOrThrow { problem -> IllegalStateException("a pre-regions recipe would not load: $problem") }

        check(decoded.composition?.terrains == listOf(Terrain.ERODED)) {
            "A pre-regions terrain read back as ${decoded.composition?.terrains}"
        }
        check(decoded.character == AgeCharacter.LEGACY) {
            "A recipe with no character should read as LEGACY, not ${decoded.character}"
        }
        check(decoded.generatorVersion == PRE_REGIONS_GENERATOR_VERSION) { "Migration overwrote the stamp" }
    }

    /**
     * An Age somebody *wrote* keeps its words and its flaws.
     *
     * Both are new to version 6 and both are provenance: nothing rebuilds a world from them, which is exactly
     * why they are easy to lose without noticing. The words are the only record of what a book said, and the
     * flaws are what makes an unstable Age diagnosable rather than merely punished — a wound has to be sited
     * at the contradiction (§5.1), and the contradiction is only written down here.
     */
    test("a written Age keeps its words and its flaws") {
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withPresets(Aspect.CARVERS, listOf(Carvers.CAVES.key, Carvers.SOLID.key))
        val instability = Instability(
            listOf(
                Flaw(
                    Register.FRACTURE, listOf("riddled", "unbroken"), Aspect.CARVERS,
                    listOf("cavernous", "solid"), severity = 3,
                ),
                Flaw(
                    Register.TENSION, listOf("riddled", "unbroken"), Aspect.CARVERS,
                    listOf("cavernous", "solid"), severity = 1,
                ),
            ),
        )
        val recipe = AgeRecipe(
            AgeWorld.Composed(composition),
            seed = SAMPLE_SEED,
            character = SAMPLE_CHARACTER,
            instability = instability,
            words = listOf("verdant", "lifeless"),
        )
        val decoded = roundTrips(recipe, "a written Age")

        check(decoded.words == listOf("verdant", "lifeless")) { "the words were lost: ${decoded.words}" }
        check(decoded.instability.flaws == instability.flaws) { "the flaws were lost: ${decoded.instability.flaws}" }
        check(decoded.instability.index == instability.index) {
            "the index came back as ${decoded.instability.index}, not ${instability.index}"
        }
        // The severity is stored rather than recomputed, so retuning the charges cannot rewrite an Age that
        // has already been written — the same argument as persisting the recipe rather than the words (§4.6).
        check(instability.index == EXPECTED_SAMPLE_INDEX) { "the sample flaws no longer sum to what they did" }
    }

    /**
     * An Age whose aspects divide unevenly keeps its shares, through NBT and through its own spelling.
     *
     * Shares are generation inputs — they decide how much ground each territory covers — so losing one
     * silently would hand back a different world on the next open. And an even division has to keep spelling
     * itself the way it always did, or every recipe written before shares existed would read as something else.
     */
    test("an uneven division round-trips") {
        val uneven = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withPresets(
                Aspect.CARVERS,
                listOf(Carvers.CAVES.key, Carvers.POROUS.key, Carvers.WEATHERED.key),
                listOf(Share.DOMINANT, Share.SCATTERED, Share.RARE),
            )
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(uneven), seed = SAMPLE_SEED), "an uneven division")
        check(
            decoded.composition?.sharesOf(Aspect.CARVERS) == listOf(Share.DOMINANT, Share.SCATTERED, Share.RARE),
        ) {
            "the shares came back as ${decoded.composition?.sharesOf(Aspect.CARVERS)}"
        }

        val spelling = uneven.toString()
        check("carvers=caves,porous@scattered,weathered@rare" in spelling) {
            "an uneven division spells itself wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == uneven) { "'$spelling' does not read back as itself" }

        // An even division says nothing about shares at all, which is what keeps a hand-composed Age — and
        // every recipe written before shares existed — spelled exactly as it was.
        val even = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS))
        check("@" !in even.toString()) { "an even division should not mention shares: '$even'" }
        check(even.sharesOf(Aspect.TERRAIN) == listOf(Share.DOMINANT, Share.DOMINANT)) {
            "an unmentioned division should be even, not ${even.sharesOf(Aspect.TERRAIN)}"
        }
    }

    /**
     * An Age written before words existed still opens, and opens as a coherent Age with nothing to say for
     * itself — which is the truth about it, since nobody wrote it from a sentence.
     */
    test("recipes written before words still read") {
        val written = CompoundTag().apply {
            put(
                "world",
                CompoundTag().apply {
                    put("kind", StringTag.valueOf("composed"))
                    put("terrain", StringTag.valueOf(Terrain.HILLS.key))
                },
            )
            putLong("seed", SAMPLE_SEED)
            putInt(GENERATOR_VERSION_KEY, PRE_WORDS_GENERATOR_VERSION)
        }
        val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
            .getOrThrow { problem -> IllegalStateException("a pre-words recipe would not load: $problem") }

        check(decoded.words.isEmpty()) { "a recipe with no words read back with ${decoded.words}" }
        check(decoded.instability == Instability.NONE) {
            "a recipe with no flaws read back as ${decoded.instability}"
        }
        check(decoded.generatorVersion == PRE_WORDS_GENERATOR_VERSION) { "Migration overwrote the stamp" }
    }

    /**
     * Every generator-kind string ever persisted still names a preset.
     *
     * The list is frozen history, not a mirror of the enum — that is the entire point. Renaming an
     * [AgePreset.key] compiles perfectly and orphans every Age already written with the old name, sending
     * it to the fallback preset and quietly handing the player a different world.
     */
    test("every written kind still resolves") {
        for (kind in LEGACY_KINDS) {
            checkNotNull(AgePreset.byKey(kind)) {
                "No preset named '$kind' any more — Ages written with it would fall back to Spire. " +
                    "Restore the key, or migrate those Ages deliberately in AgeSavedData."
            }
        }
    }

    /**
     * The generator stamp is actually written down.
     *
     * Guards a specific trap: had the field been declared `optionalFieldOf(name, default)`, the codec
     * would *omit* it whenever it matched the current version, and an old Age would then be read back
     * claiming to have been made by whatever code is current — which is the one question the stamp exists
     * to answer (design §6.5).
     */
    test("the generator version is stamped") {
        val recipe = AgeRecipe(AgeRecipe.worldFor(AgePreset.SPIRE), seed = SAMPLE_SEED)
        val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, recipe)
            .getOrThrow { problem -> IllegalStateException("recipe would not encode: $problem") }
        val fields = encoded as? CompoundTag ?: error("A recipe should encode to a compound, not $encoded")
        check(fields.contains(GENERATOR_VERSION_KEY)) {
            "A written recipe carries no '$GENERATOR_VERSION_KEY', so its generation could never be told " +
                "apart from today's"
        }
        check(fields.getInt(GENERATOR_VERSION_KEY) == AgeRecipe.CURRENT_GENERATOR_VERSION) {
            "Stamped generation ${fields.getInt(GENERATOR_VERSION_KEY)}, " +
                "expected ${AgeRecipe.CURRENT_GENERATOR_VERSION}"
        }
    }
})

/** [recipe] written and read back, unchanged — the check every case above is a variation of. */
private fun roundTrips(recipe: AgeRecipe, what: String): AgeRecipe {
    val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, recipe)
        .getOrThrow { problem -> IllegalStateException("$what would not encode: $problem") }
    val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, encoded)
        .getOrThrow { problem -> IllegalStateException("$what would not decode: $problem") }
    check(decoded == recipe) { "$what came back as $decoded, not $recipe" }
    return decoded
}

/**
 * Every preset a composition can currently be written from: every authored one, the three seas the mod
 * names, and one referent naming content this pack does not have.
 *
 * That last is the point of the list now that an aspect can be open (design §3.1) — a recipe must be able to
 * hold an id from a mod that is not installed and give it back unchanged, because a save moving between
 * modpacks is ordinary and an Age that quietly lost its sea would be the worst kind of data loss.
 */
private fun everyAspectPreset(): List<AspectPreset> =
    Aspect.entries.flatMap { it.authored } +
        listOf(Sea.NONE, Sea.WATER, Sea.LAVA, Sea(ResourceLocation.parse("examplemod:creosote")))

/** Every generator kind that has ever been written into a save. Append-only; never edit a line. */
private val LEGACY_KINDS = listOf(
    "spire", "field", "pyramids", "pyrings", "pyrvaried",
    "hills", "shapes", "pillars", "caverns", "eroded",
    "vanilla", "vanillabare",
)

/** A character unlike the default in every field, so a lazy round trip cannot pass by accident. */
private val SAMPLE_CHARACTER =
    AgeCharacter(seam = Seam.FUZZED, alignment = Alignment.INDEPENDENT, regionBlocks = 1600)

private const val GENERATOR_VERSION_KEY = "generator_version"

/** What every Age written before aspects is stamped with. */
private const val PRE_SLOTS_GENERATOR_VERSION = 1

/** And what every Age written after aspects but before regions is stamped with. */
private const val PRE_REGIONS_GENERATOR_VERSION = 2

/** And what every Age written after every aspect became positional but before words could write one is. */
private const val PRE_WORDS_GENERATOR_VERSION = 5

/** What the sample flaws add up to: a division at exact precision, plus the tension behind it. */
private const val EXPECTED_SAMPLE_INDEX = 4

private const val SAMPLE_SEED = 0x5EED_A9EL
