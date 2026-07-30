package co.voik.agesandtheart.age

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

/**
 * The generation presets an Age can be written from — the whole structural vocabulary the mod has
 * today, and currently the only thing an [AgeRecipe] names.
 *
 * These were loose strings matched in a `when` with a fallback clause. They are the *same* strings
 * still — [key] is the save format, and an Age written before this enum existed names its preset by
 * that string — but the match over them is now exhaustive, so a preset added here has to be given a
 * world rather than quietly falling through to Spire's.
 *
 * **Renaming a [key] orphans every Age already written with it**, which is what `recipecheck` guards.
 *
 * **Most of these are no longer generators.** Slots arrived (see [co.voik.agesandtheart.age.aspect.Aspect])
 * and all but three became *compositions* — a terrain, a sea, a carving, a dressing and a sky,
 * named separately. What survives here is the name each classic demo goes by, which
 * [AgeRecipe.worldFor] turns into the composition it describes, and which every Age written before
 * aspects existed still says on disk.
 *
 * Only [SPIRE], [VANILLA] and [VANILLA_BARE] remain whole generators, because they genuinely are not
 * assembled from parts — see [AgeWorld].
 */
enum class AgePreset(val key: String) : StringRepresentable {
    /** The bespoke floating-island generator, kept as an easter egg rather than a field tree. */
    SPIRE("spire"),

    /** The same islands rebuilt as a field tree — the toolkit's first world. */
    FIELD("field"),

    /** Instanced pyramids on a plain: a density-gradient grid, its rings, and posed variants. */
    PYRAMIDS("pyramids"),
    PYRINGS("pyrings"),
    PYRVARIED("pyrvaried"),

    /** Rolling noise hills over a sea — vanilla's biomes, carvers and structures. */
    HILLS("hills"),

    /** A walkable sampler of the shape vocabulary and its combinators. */
    SHAPES("shapes"),

    /** Colossal rectangular monoliths over an ocean. */
    PILLARS("pillars"),

    /** Ridged 3D noise riddled with caverns; its caves are the field itself, not carvers. */
    CAVERNS("caverns"),

    /** Billowy 3D noise, weathered into mesa-like relief. */
    ERODED("eroded"),

    /** Tier-B delegates to Minecraft's own generation — our benchmark reference points. */
    VANILLA("vanilla"),
    VANILLA_BARE("vanillabare"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<AgePreset> = StringRepresentable.fromEnum { entries.toTypedArray() }

        /**
         * The preset [key] names, or null if nothing does — an Age written against a preset we no
         * longer have. (`fromEnum` hands back a lookup of its own, but the type carrying it is
         * deprecated in 1.21.1, and a scan of twelve entries during migration costs nothing.)
         */
        fun byKey(key: String): AgePreset? = entries.firstOrNull { it.key == key }
    }
}
