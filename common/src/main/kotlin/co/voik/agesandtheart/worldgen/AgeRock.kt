package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.TerrainField
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import java.util.Optional

/**
 * **Who answers for an Age's rock** — the field tree, or vanilla's own router (`the-world-model.md` §4).
 *
 * The two are either/or, and that is the whole of what this type exists to say. Vanilla's router answers
 * for the shape, the aquifers and the preliminary surface together; the field tree answers for all three.
 * An Age takes one set of answers or the other, and naming a landform is how a writer leaves a template's
 * rock behind.
 *
 * **Only the rock divides.** Everything above it — the biomes, the structures, the features, the spawns,
 * the sky and the air — is ours either way, which is why this is a field on
 * [co.voik.agesandtheart.generation.AgeChunkGenerator] rather than a second generator beside it.
 */
sealed interface AgeRock {
    val kind: Kind

    /**
     * An Age shaped by the field tree, and the underground taken out from beneath it.
     *
     * [hollows] is the rock a cave system was cut out of, handed to the generator rather than to the sea so
     * that the space meets a [co.voik.agesandtheart.worldgen.field.WaterTable] on its way out rather than
     * being flooded to the roof by a flat waterline. Null for an Age with no such caves.
     */
    data class Ours(
        val field: TerrainField,
        val hollows: TerrainField? = null,
        /**
         * The same rock **without whatever shuts it overhead** — null where nothing does, which is almost
         * every Age.
         *
         * A lid is rock and belongs in [field], so it is filled and closes the sky over anything asking
         * whether a spawn is out in the open. It is emphatically **not ground**: a heightmap, a climate
         * grounded in the Age's shape, the surface rule's gate and the search for somewhere to put a
         * player all want the height of the *land*, and a ceiling read as land makes every column a
         * mountain to the top of the world. So the readers that mean ground ask this instead.
         *
         * The lid is dressed all the same, by that same gate: it is a floor rather than a ceiling —
         * everything above the land counts as near its surface — so vanilla's own `UNDER_CEILING` arms
         * and its bedrock roof find the underside of a lid without being told it is there.
         */
        val ground: TerrainField? = null,
    ) : AgeRock {
        override val kind = Kind.OURS

        /** What the *land* of this Age is, which is [field] itself unless something shuts it overhead. */
        // Qualified: bare `field` inside an accessor is Kotlin's backing-field keyword, not our property.
        val landform: TerrainField get() = ground ?: this.field
    }

    /**
     * Vanilla's own terrain, whole — the overworld's, the nether's, the end's.
     *
     * The settings arrive built, because which of an Age's claims override vanilla's block, its fluid and
     * its surface rule is the composition's business rather than the generator's.
     */
    data class Vanillas(val settings: Holder<NoiseGeneratorSettings>) : AgeRock {
        override val kind = Kind.VANILLAS
    }

    enum class Kind(private val key: String) : StringRepresentable {
        OURS("ours"),
        VANILLAS("vanillas"),
        ;

        override fun getSerializedName(): String = key
    }

    companion object {
        private val KIND_CODEC: Codec<Kind> = StringRepresentable.fromEnum(Kind::values)

        /**
         * Dispatched on a `rock` field rather than on which other fields happen to be present, like
         * [co.voik.agesandtheart.age.AgeWorld]'s. The casts are safe because the dispatch already decided
         * the arm; DFU cannot say so in the type.
         *
         * **This is the field that selects the mode**, so a generator that lost it would rebuild as a
         * different world rather than a lossier one — which is what the nullable it replaced did, having
         * never been written at all.
         */
        val MAP_CODEC: MapCodec<AgeRock> = KIND_CODEC.dispatchMap("rock", AgeRock::kind) { kind ->
            when (kind) {
                Kind.OURS -> RecordCodecBuilder.mapCodec { instance ->
                    instance.group(
                        TerrainField.CODEC.fieldOf("field").forGetter { (it as Ours).field },
                        // Absent for every Age without shape-cut caves, which is almost all of them.
                        TerrainField.CODEC.optionalFieldOf("hollows")
                            .forGetter { Optional.ofNullable((it as Ours).hollows) },
                        // And absent for every Age that is not shut overhead, which is almost all of them.
                        TerrainField.CODEC.optionalFieldOf("ground")
                            .forGetter { Optional.ofNullable((it as Ours).ground) },
                    ).apply(instance) { field, hollows, ground ->
                        Ours(field, hollows.orElse(null), ground.orElse(null))
                    }
                }

                Kind.VANILLAS -> NoiseGeneratorSettings.CODEC.fieldOf("settings")
                    .xmap(::Vanillas) { (it as Vanillas).settings }
            }
        }
    }
}
