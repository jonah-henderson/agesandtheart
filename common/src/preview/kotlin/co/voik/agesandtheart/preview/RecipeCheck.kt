package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Landform
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Sky
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import co.voik.agesandtheart.age.slot.Subsurface
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag

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
    spellsCompositionsTheWayItReadsThem()
    readsRecipesWrittenBeforeSlots()
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
        val composition = AgeComposition(landform = Landform.HILLS).withPreset(preset.slot, preset.key)
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
    val composition = AgeComposition(landform = Landform.PYRAMIDS)
        .withOption(Slot.LANDFORM, Landform.ARRANGEMENT.name, "rings")
        .withOption(Slot.LANDFORM, "elevation", "towering")
    check(composition.unknownOptions == listOf("landform.elevation")) {
        "Expected 'elevation' to be reported as unrecognised, got ${composition.unknownOptions}"
    }
    val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED), "unknown options")
    check(decoded.composition?.options?.of(Slot.LANDFORM)?.chosen?.get("elevation") == "towering") {
        "An unrecognised option was dropped in the round trip: $decoded"
    }
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
        AgeComposition(landform = Landform.HILLS).withPreset(preset.slot, preset.key)
    } + AgeComposition(landform = Landform.PYRAMIDS)
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

/** Every preset of every slot, which is every word a composition can currently be written from. */
private fun everySlotPreset(): List<SlotPreset> =
    Landform.entries + Medium.entries + Subsurface.entries + Dressing.entries + Sky.entries

/** Every generator kind that has ever been written into a save. Append-only; never edit a line. */
private val LEGACY_KINDS = listOf(
    "spire", "field", "pyramids", "pyrings", "pyrvaried",
    "hills", "shapes", "pillars", "caverns", "eroded",
    "vanilla", "vanillabare",
)

private const val GENERATOR_VERSION_KEY = "generator_version"

/** What every Age written before slots is stamped with. */
private const val PRE_SLOTS_GENERATOR_VERSION = 1

private const val SAMPLE_SEED = 0x5EED_A9EL
