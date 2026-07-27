package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps

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
    keepsEveryWrittenKind()
    stampsTheGeneratorVersion()
    println("Recipes: ${AgePreset.entries.size} presets round-trip, all ${LEGACY_KINDS.size} written kinds still resolve.")
}

/** Every preset survives the trip to NBT and back, unchanged. */
private fun roundTripsEveryPreset() {
    for (preset in AgePreset.entries) {
        val recipe = AgeRecipe(preset, seed = SAMPLE_SEED)
        val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, recipe)
            .getOrThrow { problem -> IllegalStateException("${preset.key} would not encode: $problem") }
        val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, encoded)
            .getOrThrow { problem -> IllegalStateException("${preset.key} would not decode: $problem") }
        check(decoded == recipe) { "${preset.key} came back as $decoded, not $recipe" }
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
    val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, AgeRecipe(AgePreset.SPIRE, seed = SAMPLE_SEED))
        .getOrThrow { problem -> IllegalStateException("recipe would not encode: $problem") }
    val fields = encoded as? CompoundTag ?: error("A recipe should encode to a compound, not $encoded")
    check(fields.contains(GENERATOR_VERSION_KEY)) {
        "A written recipe carries no '$GENERATOR_VERSION_KEY', so its generation could never be told apart from today's"
    }
    check(fields.getInt(GENERATOR_VERSION_KEY) == AgeRecipe.CURRENT_GENERATOR_VERSION) {
        "Stamped generation ${fields.getInt(GENERATOR_VERSION_KEY)}, expected ${AgeRecipe.CURRENT_GENERATOR_VERSION}"
    }
}

/** Every generator kind that has ever been written into a save. Append-only; never edit a line. */
private val LEGACY_KINDS = listOf(
    "spire", "field", "pyramids", "pyrings", "pyrvaried",
    "hills", "shapes", "pillars", "caverns", "eroded",
    "vanilla", "vanillabare",
)

private const val GENERATOR_VERSION_KEY = "generator_version"
private const val SAMPLE_SEED = 0x5EED_A9EL
