package co.voik.agesandtheart.age

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import net.minecraft.util.StringRepresentable

/**
 * What kind of thing an Age's world is: assembled from aspects, or one of the handful that are not
 * assembled from anything.
 *
 * The split is the terrain architecture's own two tiers made explicit in the save format. Almost every
 * Age is [Composed] — a terrain, a sea, a carving, a dressing and a sky, chosen independently.
 * A few are [Bespoke]: `spire` is a hand-written generator kept as an easter egg, and the two vanilla
 * delegates *are* Minecraft's own pipeline, biome source and all. Trying to express those as
 * compositions would mean inventing aspects that exist only to hold them.
 *
 * Keeping the door open matters beyond the three: the design wants canonical Ages recognisable as
 * easter eggs (§7.6), and a bespoke arm is where such a thing lands without contorting the grammar.
 */
sealed interface AgeWorld {
    val kind: Kind

    /** An Age assembled from one preset per aspect — what the Art will eventually write. */
    data class Composed(val composition: AgeComposition) : AgeWorld {
        override val kind = Kind.COMPOSED
        override fun toString(): String = composition.toString()
    }

    /** A whole generator with a name, not assembled from parts. */
    data class Bespoke(val preset: AgePreset) : AgeWorld {
        override val kind = Kind.BESPOKE
        override fun toString(): String = preset.key
    }

    enum class Kind(private val key: String) : StringRepresentable {
        COMPOSED("composed"),
        BESPOKE("bespoke"),
        ;

        override fun getSerializedName(): String = key
    }

    companion object {
        private val KIND_CODEC: Codec<Kind> = StringRepresentable.fromEnum(Kind::values)

        /**
         * Dispatched on a `kind` field so the two arms stay distinguishable forever, rather than being
         * told apart by which other fields happen to be present. The casts are safe because the
         * dispatch already decided which arm it is; DFU has no way to say so in the type.
         */
        val MAP_CODEC: MapCodec<AgeWorld> = KIND_CODEC.dispatchMap("kind", AgeWorld::kind) { kind ->
            when (kind) {
                Kind.COMPOSED -> AgeComposition.MAP_CODEC.xmap(
                    ::Composed,
                    { world -> (world as Composed).composition },
                )
                Kind.BESPOKE -> AgePreset.CODEC.fieldOf("preset").xmap(
                    ::Bespoke,
                    { world -> (world as Bespoke).preset },
                )
            }
        }
    }
}
