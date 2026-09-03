package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import net.minecraft.util.StringRepresentable

/**
 * A composable description of world *shape*: for a column, which vertical intervals are solid.
 * Primitives answer analytically, combinators combine their children's spans. Material and sea fill are
 * the generator's concern — this interface is pure shape.
 *
 * The tree is **data**, serialising through [CODEC] (a dispatch over [FieldKind]), so it persists in an
 * Age recipe and replays on load. See `notes/terrain-architecture.md`.
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
     * Roughly what one [columnSpans] call costs, as a count of noise samples. Zero means closed form.
     *
     * Exists so combinators put their cheap children first, which is not a micro-optimisation: [Intersect]
     * stops as soon as the running result is empty, so a sampled child after an analytic bound is skipped
     * on every column the bound misses. Only the relative ordering has to be right.
     */
    val samplesPerColumn: Int get() = 0

    /** The solid vertical intervals of the column at ([worldX], [worldZ]). Tier-1: analytic, exact. */
    fun columnSpans(worldX: Int, worldZ: Int): Spans

    /**
     * The same shape with its *dimensions* multiplied by [factor], about the [pivotY] plane — which stays
     * put, so anything standing on it keeps its footing.
     *
     * **This resizes the description, not the output**: a resized [Pyramid] genuinely has more courses of
     * blocks, where resampling a built one stretches its staircase into uneven steps. Any factor is
     * therefore exact, fractional included. What a caller budgets for is that each distinct size is a
     * whole rebuilt tree, so sizes cost construction and memory rather than per-chunk time.
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
    TORUS({ Torus.CODEC }),
    ELLIPSOID({ Ellipsoid.CODEC }),
    CONE({ Cone.CODEC }),
    PYRAMID({ Pyramid.CODEC }),
    SLAB({ Slab.CODEC }),
    HALF_SPACE({ HalfSpace.CODEC }),
    CYLINDER({ Cylinder.CODEC }),
    BOX({ Box.CODEC }),
    NOISE_HEIGHTMAP({ NoiseHeightmap.CODEC }),
    NOISE_3D({ Noise3D.CODEC }),
    WEATHERED({ self -> Weathered.codec(self) }),
    RAISED({ self -> Raised.codec(self) }),
    UNDULATED({ self -> Undulated.codec(self) }),
    FAULT({ self -> Fault.codec(self) }),
    RIFT({ Rift.CODEC }),
    RIDGE({ Ridge.CODEC }),
    CANYON({ Canyon.CODEC }),
    CELL_CANYON({ CellCanyon.CODEC }),
    DRAINAGE({ Drainage.CODEC }),
    MOUNTAIN_RANGE({ MountainRange.CODEC }),
    CAVED({ self -> Caved.codec(self) }),
    ISLE({ Isle.CODEC }),
    ESCARPMENT({ Escarpment.CODEC }),
    CHANCE({ self -> Chance.codec(self) }),
    CHOOSE({ self -> Choose.codec(self) }),
    UNION({ self -> Union.codec(self) }),
    INTERSECT({ self -> Intersect.codec(self) }),
    SUBTRACT({ self -> Subtract.codec(self) }),
    INSTANCED({ self -> Instanced.codec(self) }),
    REGIONS({ self -> Regions.codec(self) });

    fun codec(self: Codec<TerrainField>): MapCodec<out TerrainField> = makeCodec(self)

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<FieldKind> = StringRepresentable.fromEnum(FieldKind::values)
    }
}
