package co.voik.agesandtheart.age

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

/**
 * The name each classic demo Age goes by. [AgeRecipe.worldFor] turns most of them into the composition
 * they describe; only [VANILLA] and [VANILLA_BARE] are whole generators (see [AgeWorld]).
 *
 * [key] is the save format, so renaming one orphans every Age already written with it.
 */
enum class AgePreset(val key: String) : StringRepresentable {
    /** Floating islands over a green sea, under the Spire's own sky — the toolkit's first world. */
    SPIRE("spire"),

    /** Instanced pyramids on a plain: a density-gradient grid, its rings, and posed variants. */
    PYRAMIDS("pyramids"),
    PYRINGS("pyrings"),
    PYRVARIED("pyrvaried"),

    /** Rolling noise hills over a sea — vanilla's biomes, carvers and structures. */
    HILLS("hills"),

    /** A level plain to the horizon, with caves under it — our superflat. */
    FLATLANDS("flatlands"),

    /** A walkable sampler of the shape vocabulary and its combinators. */
    SHAPES("shapes"),

    /** Colossal rectangular monoliths over an ocean. */
    PILLARS("pillars"),

    /** Hills over a network of ridged-noise tunnels — the `tunnels` underground. */
    TUNNELS("tunnels"),

    /** Plain 3D noise, weathered into mesa-like relief. */
    ERODED("eroded"),

    /** Solid rock to the height limit, with one canyon cut through the origin and a river in it. */
    CANYON("canyon"),

    /** A world cut in two: ocean one way, plateau the other, one cliff between them. */
    CLIFFS("cliffs"),

    /** Mesa country: a tableland under open sky, cut to pieces by canyons running three ways. */
    CANYONLANDS("canyonlands"),

    /** The same table cracked into cells instead, with a gorge down every join. */
    SHATTERED("shattered"),

    /** Rolling upland carved by a river system: headwaters branching down into trunks. */
    RIVERLANDS("riverlands"),

    /** Islands in an endless sea — one at the origin, the rest a voyage away. */
    ISLANDS("islands"),

    /** One island at the origin, and no other anywhere. */
    ISLE("isle"),

    /** An alpine range: a foreland plain, foothills, and a glaciated crest behind them. */
    ALPS("alps"),

    /** One colossal impact basin at the origin, with an ordinary cratered plain beyond its ejecta. */
    CRATERLANDS("craterlands"),

    /** Minecraft's noise caves inside out: solid where they carve, open air everywhere else. */
    INVERSE_CAVES("inversecaves"),

    /** Ordinary ground, with storey upon storey of pillared hall taken out from under it. */
    HALLS("halls"),

    /** Rock floor to ceiling, hollowed by vanilla's own caves — a world that is all underground. */
    SOLID("solid"),

    /** The same rock, with great lake-floored vaults in it instead. */
    CHAMBERS("chambers"),

    /** Tier-B delegates to Minecraft's own generation — our benchmark reference points. */
    VANILLA("vanilla"),
    VANILLA_BARE("vanillabare"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<AgePreset> = StringRepresentable.fromEnum { entries.toTypedArray() }
    }
}
