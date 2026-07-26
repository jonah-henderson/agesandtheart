package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import net.minecraft.util.StringRepresentable

/**
 * A composable description of world *shape*: for a column it answers which vertical intervals are
 * solid ([columnSpans]). Primitives (ellipsoid, cone, …) answer analytically; combinators (union,
 * subtract, …) combine their children's spans. Material/ambient medium are separate concerns, held
 * by the generator — this interface is pure shape.
 *
 * The tree is *data*, not code: it serialises through [CODEC] (a dispatch over [FieldKind]), so a
 * field tree can be persisted as part of an Age recipe and replayed on load. See
 * `notes/terrain-architecture.md` for the design and the three evaluation tiers.
 */
sealed interface TerrainField {

    /** Which node this is — drives codec dispatch. */
    val kind: FieldKind

    /**
     * Max horizontal distance from this field's local origin `(0, 0)` at which [columnSpans] can be
     * non-empty. Bounds the instancing cell-scan (how far out to look for covering instances).
     * `POSITIVE_INFINITY` for globally-solid fields (e.g. a [Slab]) that make no sense as a template.
     */
    val horizontalReach: Double

    /**
     * Roughly what one [columnSpans] call costs, as a count of noise samples. Zero — the default — means
     * the field answers in closed form and costs essentially nothing; a sampled field reports how much of
     * the column it may have to walk.
     *
     * It exists so combinators can put their cheap children first. That is not a micro-optimisation:
     * [Intersect] stops as soon as the running result is empty, so a sampled child evaluated *after* an
     * analytic bound is skipped entirely on every column the bound misses — which is most of them. The
     * same tree written the other way round pays full price everywhere. A relative ordering is all this
     * needs to be right about, so an approximate count is enough.
     */
    val samplesPerColumn: Int get() = 0

    /** The solid vertical intervals of the column at ([worldX], [worldZ]). Tier-1: analytic, exact. */
    fun columnSpans(worldX: Int, worldZ: Int): Spans

    /**
     * The same shape with its *dimensions* multiplied by [factor], measured about the [pivotY] plane
     * (which stays put, so anything standing on it keeps its footing).
     *
     * This resizes the description, not the output: a resized [Pyramid] genuinely has more courses of
     * blocks, where resampling a built one at a fractional rate would stretch its staircase into
     * uneven two-block steps. Since a field is data, the resized copy is built once and reused — see
     * [Instanced], which pre-builds every size it will ever place.
     */
    fun resized(factor: Double, pivotY: Int): TerrainField

    companion object {
        /** Self-referential so combinators can hold child fields. */
        val CODEC: Codec<TerrainField> = Codec.recursive("TerrainField") { self ->
            FieldKind.CODEC.dispatch(
                "type",
                { field: TerrainField -> field.kind },
                { kind: FieldKind -> kind.codec(self) },
            )
        }
    }
}

/**
 * The closed set of field node types. Each knows how to build its own [MapCodec]; combinator codecs
 * need the recursive [TerrainField.CODEC] (passed as `self`) to encode their children.
 */
enum class FieldKind(private val makeCodec: (Codec<TerrainField>) -> MapCodec<out TerrainField>) : StringRepresentable {
    ELLIPSOID({ Ellipsoid.CODEC }),
    CONE({ Cone.CODEC }),
    PYRAMID({ Pyramid.CODEC }),
    SLAB({ Slab.CODEC }),
    HALF_SPACE({ HalfSpace.CODEC }),
    CYLINDER({ Cylinder.CODEC }),
    BOX({ Box.CODEC }),
    NOISE_HEIGHTMAP({ NoiseHeightmap.CODEC }),
    NOISE_3D({ Noise3D.CODEC }),
    UNION({ self -> Union.codec(self) }),
    INTERSECT({ self -> Intersect.codec(self) }),
    SUBTRACT({ self -> Subtract.codec(self) }),
    INSTANCED({ self -> Instanced.codec(self) });

    fun codec(self: Codec<TerrainField>): MapCodec<out TerrainField> = makeCodec(self)

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<FieldKind> = StringRepresentable.fromEnum(FieldKind::values)
    }
}
