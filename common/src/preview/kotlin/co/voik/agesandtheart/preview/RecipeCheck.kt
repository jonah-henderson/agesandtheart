package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.AgeCharacter
import co.voik.agesandtheart.age.Alignment
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Landform
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Share
import co.voik.agesandtheart.age.slot.Sky
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import co.voik.agesandtheart.age.slot.Subsurface
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
 * codecs here reach for a registry.
 */
fun main() {
    roundTripsEveryPreset()
    roundTripsEverySlotPreset()
    roundTripsOptionsItCannotUnderstand()
    roundTripsAMingledParameter()
    spellsCompositionsTheWayItReadsThem()
    readsRecipesWrittenBeforeSlots()
    roundTripsASetValuedLandform()
    readsRecipesWrittenBeforeRegions()
    roundTripsAWrittenAge()
    roundTripsAnUnevenDivision()
    readsRecipesWrittenBeforeWords()
    keepsEveryWrittenKind()
    stampsTheGeneratorVersion()
    println(
        "Recipes: ${AgePreset.entries.size} presets and ${everySlotPreset().size} slot presets round-trip, " +
            "all ${LEGACY_KINDS.size} written kinds still resolve.",
    )
}

/** Every preset survives the trip to NBT and back, unchanged — composed and bespoke alike. */
private fun roundTripsEveryPreset() {
    for (preset in AgePreset.entries) {
        roundTrips(AgeRecipe(AgeRecipe.worldFor(preset), seed = SAMPLE_SEED), preset.key)
    }
}

/**
 * Every preset of every slot survives too.
 *
 * Presets are no longer whole worlds, so "every [AgePreset] round-trips" no longer reaches most of
 * what a recipe can say: a slot preset only appears above if some classic demo Age happens to name it.
 * This asks each one directly, so a family can gain a member without gaining a blind spot.
 */
private fun roundTripsEverySlotPreset() {
    for (preset in everySlotPreset()) {
        val composition = AgeComposition(landforms = listOf(Landform.HILLS)).withPreset(preset.slot, preset.key)
        roundTrips(AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED), "${preset.slot.key}=${preset.key}")
    }
}

/**
 * An option nobody recognises comes back out again.
 *
 * The invariant [co.voik.agesandtheart.age.slot.Options] promises, and the one most easily lost to a
 * well-meant cleanup: dropping an unreadable option on load is how a save quietly becomes a different
 * save, since the recipe is all there is. It has to survive the *write* as well as the read.
 */
private fun roundTripsOptionsItCannotUnderstand() {
    val composition = AgeComposition(landforms = listOf(Landform.PYRAMIDS))
        .withOption(Slot.LANDFORM, Landform.ARRANGEMENT.name, "rings")
        .withOption(Slot.LANDFORM, "elevation", "towering")
    check(composition.unknownOptions == listOf("landform.elevation")) {
        "Expected 'elevation' to be reported as unrecognised, got ${composition.unknownOptions}"
    }
    val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED), "unknown options")
    check(decoded.composition?.options?.of(Slot.LANDFORM)?.chosen?.get("elevation") == listOf("towering")) {
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
private fun roundTripsAMingledParameter() {
    val one = AgeComposition(landforms = listOf(Landform.HILLS))
        .withPreset(Slot.DRESSING, Dressing.BARE_ROCK.key)
        .withOption(Slot.DRESSING, Dressing.STONE.name, "minecraft:blackstone")
    val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, AgeRecipe(AgeWorld.Composed(one), SAMPLE_SEED))
        .getOrThrow { problem -> IllegalStateException("a single material would not encode: $problem") }
    check("[" !in encoded.toString()) {
        "A lone option was written as a list, so every recipe on disk now reads differently: $encoded"
    }
    roundTrips(AgeRecipe(AgeWorld.Composed(one), SAMPLE_SEED), "one material")

    val mingled = one.withOptions(
        Slot.DRESSING,
        Dressing.STONE.name,
        listOf("minecraft:blackstone", "minecraft:tuff"),
    )
    val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(mingled), SAMPLE_SEED), "two mingled materials")
    check(decoded.composition?.options?.of(Slot.DRESSING)?.allOf(Dressing.STONE)?.size == 2) {
        "A mingled parameter came back as ${decoded.composition?.options?.of(Slot.DRESSING)}"
    }
    val spelling = mingled.toString()
    check("dressing.stone=minecraft:blackstone,minecraft:tuff" in spelling) {
        "A mingled parameter spells itself wrong: '$spelling'"
    }
    check(AgeComposition.parse(spelling).getOrThrow() == mingled) { "'$spelling' does not read back as itself" }
}

/**
 * What a composition prints is what the composer reads back.
 *
 * These are two hand-written halves of one grammar, and nothing but this makes them agree. The cost of
 * their drifting apart is that `/age list` prints recipes `/age compose` cannot accept — the sort of
 * thing that survives for months, because each half looks right on its own.
 */
private fun spellsCompositionsTheWayItReadsThem() {
    val compositions = everySlotPreset().map { preset ->
        AgeComposition(landforms = listOf(Landform.HILLS)).withPreset(preset.slot, preset.key)
    } + AgeComposition(landforms = listOf(Landform.PYRAMIDS))
        .withOption(Slot.LANDFORM, Landform.ARRANGEMENT.name, "rings")
        .withOption(Slot.MEDIUM, Medium.DEPTH.name, "deep")

    for (composition in compositions) {
        val spelling = composition.toString()
        val read = AgeComposition.parse(spelling).getOrThrow()
        check(read == composition) { "'$spelling' reads back as '$read', which is not what wrote it" }
    }
}

/**
 * An Age written before slots existed still opens, and opens as the same Age.
 *
 * The migration lives in a codec default rather than anywhere obvious, so it is exactly the kind of
 * path that goes unexercised until somebody's save is the thing exercising it.
 */
private fun readsRecipesWrittenBeforeSlots() {
    for (preset in AgePreset.entries) {
        val written = CompoundTag().apply {
            put("preset", StringTag.valueOf(preset.key))
            putLong("seed", SAMPLE_SEED)
            putInt(GENERATOR_VERSION_KEY, PRE_SLOTS_GENERATOR_VERSION)
        }
        val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
            .getOrThrow { problem -> IllegalStateException("a pre-slots '${preset.key}' would not load: $problem") }
        check(decoded.world == AgeRecipe.worldFor(preset)) {
            "A pre-slots '${preset.key}' migrated to ${decoded.world}, not ${AgeRecipe.worldFor(preset)}"
        }
        check(decoded.generatorVersion == PRE_SLOTS_GENERATOR_VERSION) {
            "Migration overwrote the stamp on '${preset.key}', which is how an Age forgets what made it"
        }
    }
}

/**
 * A landform slot holding several presets survives, and prints in a form the composer reads back.
 *
 * The set is the whole point of regions (§3.4), and it is the part of the recipe most recently changed
 * shape — so it is the part most likely to round-trip as *something*, just not the same something.
 */
private fun roundTripsASetValuedLandform() {
    val composition = AgeComposition(landforms = listOf(Landform.HILLS, Landform.PILLARS, Landform.CAVERNS))
        .withPreset(Slot.MEDIUM, Medium.SEA.key)
        .withPresets(Slot.DRESSING, listOf(Dressing.VERDANT.key, Dressing.BARE_ROCK.key))
        .withPresets(Slot.MEDIUM, listOf(Medium.SEA.key, Medium.LAVA.key))
        .withPresets(Slot.SUBSURFACE, listOf(Subsurface.CAVES.key, Subsurface.SOLID.key))
    val recipe = AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED, character = SAMPLE_CHARACTER)
    val decoded = roundTrips(recipe, "a three-landform Age")

    check(decoded.composition?.landforms == composition.landforms) {
        "The landform set came back as ${decoded.composition?.landforms}, not ${composition.landforms}"
    }
    check(decoded.composition?.dressings == composition.dressings) {
        "The dressing set came back as ${decoded.composition?.dressings}, not ${composition.dressings}"
    }
    check(decoded.composition?.mediums == composition.mediums) {
        "The medium set came back as ${decoded.composition?.mediums}"
    }
    check(decoded.composition?.subsurfaces == composition.subsurfaces) {
        "The subsurface set came back as ${decoded.composition?.subsurfaces}"
    }
    check(decoded.character == SAMPLE_CHARACTER) {
        "An Age's character did not survive: ${decoded.character}, not $SAMPLE_CHARACTER"
    }

    val spelling = composition.toString()
    check("landform=hills,pillars,caverns" in spelling) { "A set should print comma-joined, got '$spelling'" }
    check("dressing=verdant,bare_rock" in spelling) { "So should a dressing set, got '$spelling'" }
    // Ids, because the medium slot is open (design §3.1) — the referent is the value, not a preset naming it.
    check("medium=minecraft:water,minecraft:lava" in spelling) { "And a medium set, got '$spelling'" }
    check("subsurface=caves,solid" in spelling) { "And a subsurface set, got '$spelling'" }
    check(AgeComposition.parse(spelling).getOrThrow() == composition) {
        "'$spelling' does not read back as what wrote it"
    }
}

/**
 * An Age written before landform was a set still opens, as the single-landform Age it was.
 *
 * Its `landform` is a bare string where today's is a list, and both spellings have to keep working —
 * this is the second time that field has changed shape, and the first migration is still load-bearing.
 */
private fun readsRecipesWrittenBeforeRegions() {
    val written = CompoundTag().apply {
        put(
            "world",
            CompoundTag().apply {
                put("kind", StringTag.valueOf("composed"))
                put("landform", StringTag.valueOf(Landform.ERODED.key))
                put("medium", StringTag.valueOf(Medium.SEA.key))
            },
        )
        putLong("seed", SAMPLE_SEED)
        putInt(GENERATOR_VERSION_KEY, PRE_REGIONS_GENERATOR_VERSION)
    }
    val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
        .getOrThrow { problem -> IllegalStateException("a pre-regions recipe would not load: $problem") }

    check(decoded.composition?.landforms == listOf(Landform.ERODED)) {
        "A pre-regions landform read back as ${decoded.composition?.landforms}"
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
private fun roundTripsAWrittenAge() {
    val composition = AgeComposition(landforms = listOf(Landform.HILLS))
        .withPresets(Slot.DRESSING, listOf(Dressing.BARE_ROCK.key, Dressing.VERDANT.key))
    val instability = Instability(
        listOf(
            Flaw(Register.DIVISION, listOf("lifeless", "verdant"), Slot.DRESSING, listOf("barren", "lush"), severity = 3),
            Flaw(Register.TENSION, listOf("lifeless", "verdant"), Slot.DRESSING, listOf("barren", "lush"), severity = 1),
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
 * An Age whose slots divide unevenly keeps its shares, through NBT and through its own spelling.
 *
 * Shares are generation inputs — they decide how much ground each territory covers — so losing one silently
 * would hand back a different world on the next open. And an even division has to keep spelling itself the
 * way it always did, or every recipe written before shares existed would read as something else.
 */
private fun roundTripsAnUnevenDivision() {
    val uneven = AgeComposition(landforms = listOf(Landform.HILLS))
        .withPresets(
            Slot.DRESSING,
            listOf(Dressing.OVERWORLD.key, Dressing.BARE_ROCK.key, Dressing.VERDANT.key),
            listOf(Share.DOMINANT, Share.SCATTERED, Share.RARE),
        )
    val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(uneven), seed = SAMPLE_SEED), "an uneven division")
    check(decoded.composition?.sharesOf(Slot.DRESSING) == listOf(Share.DOMINANT, Share.SCATTERED, Share.RARE)) {
        "the shares came back as ${decoded.composition?.sharesOf(Slot.DRESSING)}"
    }

    val spelling = uneven.toString()
    check("dressing=overworld,bare_rock@scattered,verdant@rare" in spelling) {
        "an uneven division spells itself wrong: '$spelling'"
    }
    check(AgeComposition.parse(spelling).getOrThrow() == uneven) { "'$spelling' does not read back as itself" }

    // An even division says nothing about shares at all, which is what keeps a hand-composed Age — and every
    // recipe written before shares existed — spelled exactly as it was.
    val even = AgeComposition(landforms = listOf(Landform.HILLS, Landform.PILLARS))
    check("@" !in even.toString()) { "an even division should not mention shares: '$even'" }
    check(even.sharesOf(Slot.LANDFORM) == listOf(Share.DOMINANT, Share.DOMINANT)) {
        "an unmentioned division should be even, not ${even.sharesOf(Slot.LANDFORM)}"
    }
}

/**
 * An Age written before words existed still opens, and opens as a coherent Age with nothing to say for
 * itself — which is the truth about it, since nobody wrote it from a sentence.
 */
private fun readsRecipesWrittenBeforeWords() {
    val written = CompoundTag().apply {
        put(
            "world",
            CompoundTag().apply {
                put("kind", StringTag.valueOf("composed"))
                put("landform", StringTag.valueOf(Landform.HILLS.key))
            },
        )
        putLong("seed", SAMPLE_SEED)
        putInt(GENERATOR_VERSION_KEY, PRE_WORDS_GENERATOR_VERSION)
    }
    val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
        .getOrThrow { problem -> IllegalStateException("a pre-words recipe would not load: $problem") }

    check(decoded.words.isEmpty()) { "a recipe with no words read back with ${decoded.words}" }
    check(decoded.instability == Instability.NONE) { "a recipe with no flaws read back as ${decoded.instability}" }
    check(decoded.generatorVersion == PRE_WORDS_GENERATOR_VERSION) { "Migration overwrote the stamp" }
}

/**
 * Every generator-kind string ever persisted still names a preset.
 *
 * The list is frozen history, not a mirror of the enum — that is the entire point. Renaming an
 * [AgePreset.key] compiles perfectly and orphans every Age already written with the old name, sending
 * it to the fallback preset and quietly handing the player a different world.
 */
private fun keepsEveryWrittenKind() {
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
private fun stampsTheGeneratorVersion() {
    val recipe = AgeRecipe(AgeRecipe.worldFor(AgePreset.SPIRE), seed = SAMPLE_SEED)
    val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, recipe)
        .getOrThrow { problem -> IllegalStateException("recipe would not encode: $problem") }
    val fields = encoded as? CompoundTag ?: error("A recipe should encode to a compound, not $encoded")
    check(fields.contains(GENERATOR_VERSION_KEY)) {
        "A written recipe carries no '$GENERATOR_VERSION_KEY', so its generation could never be told apart from today's"
    }
    check(fields.getInt(GENERATOR_VERSION_KEY) == AgeRecipe.CURRENT_GENERATOR_VERSION) {
        "Stamped generation ${fields.getInt(GENERATOR_VERSION_KEY)}, expected ${AgeRecipe.CURRENT_GENERATOR_VERSION}"
    }
}

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
 * Every preset a composition can currently be written from: every authored one, the three mediums the mod
 * names, and one referent naming content this pack does not have.
 *
 * That last is the point of the list now that a slot can be open (design §3.1) — a recipe must be able to
 * hold an id from a mod that is not installed and give it back unchanged, because a save moving between
 * modpacks is ordinary and an Age that quietly lost its sea would be the worst kind of data loss.
 */
private fun everySlotPreset(): List<SlotPreset> =
    Slot.entries.flatMap { it.authored } +
        listOf(Medium.VOID, Medium.SEA, Medium.LAVA, Medium(ResourceLocation.parse("examplemod:creosote")))

/** Every generator kind that has ever been written into a save. Append-only; never edit a line. */
private val LEGACY_KINDS = listOf(
    "spire", "field", "pyramids", "pyrings", "pyrvaried",
    "hills", "shapes", "pillars", "caverns", "eroded",
    "vanilla", "vanillabare",
)

/** A character unlike the default in every field, so a lazy round trip cannot pass by accident. */
private val SAMPLE_CHARACTER =
    AgeCharacter(seam = Seam.BLURRED, alignment = Alignment.INDEPENDENT, regionBlocks = 1600)

private const val GENERATOR_VERSION_KEY = "generator_version"

/** What every Age written before slots is stamped with. */
private const val PRE_SLOTS_GENERATOR_VERSION = 1

/** And what every Age written after slots but before regions is stamped with. */
private const val PRE_REGIONS_GENERATOR_VERSION = 2

/** And what every Age written after every slot became positional but before words could write one is. */
private const val PRE_WORDS_GENERATOR_VERSION = 5

/** What the sample flaws add up to: a division at exact precision, plus the tension behind it. */
private const val EXPECTED_SAMPLE_INDEX = 4

private const val SAMPLE_SEED = 0x5EED_A9EL
