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

    /** The solid vertical intervals of the column at ([worldX], [worldZ]). Tier-1: analytic, exact. */
    fun columnSpans(worldX: Int, worldZ: Int): Spans

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
    UNION({ self -> Union.codec(self) }),
    SUBTRACT({ self -> Subtract.codec(self) }),
    INSTANCED({ self -> Instanced.codec(self) });

    fun codec(self: Codec<TerrainField>): MapCodec<out TerrainField> = makeCodec(self)

    override fun getSerializedName(): String = name.lowercase()

    companion object {
        val CODEC: Codec<FieldKind> = StringRepresentable.fromEnum(FieldKind::values)
    }
}
