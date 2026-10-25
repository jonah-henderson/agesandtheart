package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.entity.MobCategory

/**
 * What each hazard an Age can hold is worth to [Danger] — one `art/danger.json`.
 *
 * **Every value is on one scale**: how much more dangerous the hazard makes an Age than the vanilla overworld, which
 * is nought, where the Nether is about a half and deretheni begins at [Lines.paysAt]. A hazard is rated as a book
 * writes it by default, and the multipliers ([Hardiness], [PhenomenonDials], a claim's density) scale it from there.
 *
 * **Content rather than config** (`notes/config-research.md`): a pack where lava is cheap is a different mod rather
 * than the same one run differently. **Ids, never tags**, since this has to be readable offline.
 */
data class DangerTable(
    val lines: Lines,
    /** The exponent hazards are combined under — see [Danger.score]. One adds them; two is their root-sum-square. */
    val combining: Double,
    /** What a claim confined to one biome (`in <biome>`) keeps of the same claim made of the whole Age. */
    val confined: Double,
    val ground: Ground,
    private val creatures: Map<String, Double>,
    val darkness: Darkness,
    val hardiness: Hardiness,
    private val phenomena: Map<String, Double>,
    val phenomenonDials: PhenomenonDials,
    private val features: Map<String, Double>,
    private val conditions: Map<String, Double>,
    val gravity: GravityHazard,
    /** What a full reach of wounds is worth. */
    val wounds: Double,
) {
    /**
     * What a creature asked for is worth — its own entry, else what any monster is worth, else nothing.
     *
     * Vanilla already classifies every hostile mob as a monster, so only the ones unlike an ordinary monster need a
     * line.
     */
    fun creature(named: String): Double {
        creatures[named]?.let { return it }
        return if (isAMonster(named)) creatures[MONSTERS] ?: NOTHING else NOTHING
    }

    /** What a phenomenon is worth — the hazards score, the sights do not. */
    fun phenomenon(key: String): Double = phenomena[key] ?: NOTHING

    /** What a placed feature is worth. Nothing, for scenery. */
    fun feature(named: String): Double = features[named] ?: NOTHING

    /** What one of the Age's conditions is worth at its thickest. */
    fun condition(named: String): Double = conditions[named] ?: NOTHING

    val ratedCreatures: Set<String> get() = creatures.keys
    val ratedPhenomena: Set<String> get() = phenomena.keys

    /** Whether vanilla calls [named] a monster. */
    fun isAMonster(named: String): Boolean {
        val id = Identifier.tryParse(named) ?: return false
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return false
        return type.category == MobCategory.MONSTER
    }

    /** Where an Age starts to pay, how much it pays above that, and where it stops holding ruins. */
    data class Lines(
        val paysAt: Double,
        /** Below this an Age may hold a D'ni city. Its own line, so it can be placed apart from the deposits'. */
        val ruinsBelow: Double,
        val little: Double,
        val some: Double,
        val much: Double,
        val immense: Double,
    ) {
        /** The least score at which an Age's deposit reads as [yield]. */
        fun from(yield: Yield): Double = when (yield) {
            Yield.NONE -> NOTHING
            Yield.TRACE -> paysAt
            Yield.LITTLE -> little
            Yield.SOME -> some
            Yield.MUCH -> much
            Yield.IMMENSE -> immense
            Yield.MASSIVE -> Double.POSITIVE_INFINITY
        }

        companion object {
            val CODEC: Codec<Lines> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("pays_at").forGetter(Lines::paysAt),
                    Codec.DOUBLE.fieldOf("ruins_below").forGetter(Lines::ruinsBelow),
                    Codec.DOUBLE.fieldOf("little").forGetter(Lines::little),
                    Codec.DOUBLE.fieldOf("some").forGetter(Lines::some),
                    Codec.DOUBLE.fieldOf("much").forGetter(Lines::much),
                    Codec.DOUBLE.fieldOf("immense").forGetter(Lines::immense),
                ).apply(instance, ::Lines)
            }
        }
    }

    /** What the Age stands on, what its seas are, and where the ground falls away. */
    data class Ground(
        /** Rock and surface, by block id. */
        private val madeOf: Map<String, Double>,
        /** Seas, by fluid id. */
        private val seas: Map<String, Double>,
        /** A sea deep enough to hold an abyss, its pressure, its vents and what hunts in it. */
        val abyss: Double,
        /** Landforms hanging over the void, by landform key — counted only where no sea lies beneath. */
        private val void: Map<String, Double>,
        /** Landforms dangerous in themselves, sea or none: the Nether's, whose own crowd and dark come with it. */
        private val landforms: Map<String, Double>,
        /** Kinds of underground, by key. */
        private val underground: Map<String, Double>,
    ) {
        fun madeOf(named: String): Double = madeOf[named] ?: NOTHING
        fun sea(named: String): Double = seas[named] ?: NOTHING
        fun void(landform: String): Double = void[landform] ?: NOTHING
        fun landform(key: String): Double = landforms[key] ?: NOTHING
        fun underground(key: String): Double = underground[key] ?: NOTHING

        val ratedMaterials: Set<String> get() = madeOf.keys
        val ratedLandforms: Set<String> get() = void.keys + landforms.keys
        val ratedUndergrounds: Set<String> get() = underground.keys

        companion object {
            val CODEC: Codec<Ground> = RecordCodecBuilder.create { instance ->
                instance.group(
                    RATINGS.fieldOf("made_of").forGetter { it.madeOf },
                    RATINGS.fieldOf("seas").forGetter { it.seas },
                    Codec.DOUBLE.fieldOf("abyss").forGetter(Ground::abyss),
                    RATINGS.fieldOf("void").forGetter { it.void },
                    RATINGS.fieldOf("landforms").forGetter { it.landforms },
                    RATINGS.fieldOf("underground").forGetter { it.underground },
                ).apply(instance, ::Ground)
            }
        }
    }

    /** What lets monsters walk at any hour — worth what the monsters already here are, out at every hour. */
    data class Darkness(
        val sealed: Double,
        val sunless: Double,
        /** A world shut by its own rock, which is living in a cave rather than under a lid. */
        val roofedByRock: Double,
        /** Monsters loosed from their own rules, walking at noon. */
        val incongruous: Double,
    ) {
        companion object {
            val CODEC: Codec<Darkness> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("sealed").forGetter(Darkness::sealed),
                    Codec.DOUBLE.fieldOf("sunless").forGetter(Darkness::sunless),
                    Codec.DOUBLE.fieldOf("roofed_by_rock").forGetter(Darkness::roofedByRock),
                    Codec.DOUBLE.fieldOf("incongruous").forGetter(Darkness::incongruous),
                ).apply(instance, ::Darkness)
            }
        }
    }

    /**
     * How many times as dangerous a dial makes what it acts on: [raised] with the dial at its top, [lowered] at its
     * bottom, and in a straight line from one between.
     */
    data class Dial(val raised: Double, val lowered: Double) {
        fun at(steer: Double?): Double = when {
            steer == null -> AS_IT_IS
            steer >= 0.0 -> AS_IT_IS + (raised - AS_IT_IS) * steer
            else -> AS_IT_IS - (AS_IT_IS - lowered) * -steer
        }

        companion object {
            val CODEC: Codec<Dial> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("raised").forGetter(Dial::raised),
                    Codec.DOUBLE.fieldOf("lowered").forGetter(Dial::lowered),
                ).apply(instance, ::Dial)
            }
        }
    }

    /**
     * How much more or less dangerous a creature's strength, speed, size, charge, stillness and herding make it, and
     * its being loosed from its own rules.
     */
    data class Hardiness(
        val strength: Dial,
        val pace: Dial,
        val size: Dial,
        val charged: Double,
        val still: Double,
        val herds: Double,
        /** A creature that may turn up anywhere, past its own rules (`incongruous inhabitants`). */
        val loosed: Double = AS_IT_IS,
    ) {
        companion object {
            val CODEC: Codec<Hardiness> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Dial.CODEC.fieldOf("strength").forGetter(Hardiness::strength),
                    Dial.CODEC.fieldOf("pace").forGetter(Hardiness::pace),
                    Dial.CODEC.fieldOf("size").forGetter(Hardiness::size),
                    Codec.DOUBLE.fieldOf("charged").forGetter(Hardiness::charged),
                    Codec.DOUBLE.fieldOf("still").forGetter(Hardiness::still),
                    Codec.DOUBLE.fieldOf("herds").forGetter(Hardiness::herds),
                    Codec.DOUBLE.optionalFieldOf("loosed", AS_IT_IS).forGetter(Hardiness::loosed),
                ).apply(instance, ::Hardiness)
            }
        }
    }

    /** How much more or less dangerous a phenomenon is for coming more or less often, never letting up, or hitting harder. */
    data class PhenomenonDials(val frequency: Dial, val constant: Double, val strength: Dial) {
        companion object {
            val CODEC: Codec<PhenomenonDials> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Dial.CODEC.fieldOf("frequency").forGetter(PhenomenonDials::frequency),
                    Codec.DOUBLE.fieldOf("constant").forGetter(PhenomenonDials::constant),
                    Dial.CODEC.fieldOf("strength").forGetter(PhenomenonDials::strength),
                ).apply(instance, ::PhenomenonDials)
            }
        }
    }

    /**
     * What a heavy pull is worth: a little while a block can still be jumped, rising with it, and [unjumpable] once
     * gravity reaches [unjumpableFrom] times vanilla's and it cannot.
     */
    data class GravityHazard(val heavier: Double, val unjumpable: Double, val unjumpableFrom: Double) {
        fun at(factor: Double): Double = when {
            factor <= AS_IT_IS -> NOTHING
            factor >= unjumpableFrom -> unjumpable
            else -> heavier * (factor - AS_IT_IS) / (unjumpableFrom - AS_IT_IS)
        }

        companion object {
            val CODEC: Codec<GravityHazard> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("heavier").forGetter(GravityHazard::heavier),
                    Codec.DOUBLE.fieldOf("unjumpable").forGetter(GravityHazard::unjumpable),
                    Codec.DOUBLE.fieldOf("unjumpable_from").forGetter(GravityHazard::unjumpableFrom),
                ).apply(instance, ::GravityHazard)
            }
        }
    }

    companion object {
        /** Where a pack puts it. */
        val FILE: Identifier = "art/danger.json".location()

        private const val MONSTERS = "monsters"
        private const val NOTHING = 0.0
        private const val AS_IT_IS = 1.0

        /** Before [NONE], whose [Ground] reads it as its class is first loaded. */
        private val RATINGS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)

        private val UNCHANGED = Dial(raised = AS_IT_IS, lowered = AS_IT_IS)

        /**
         * What a pack shipping no table scores: nothing, anywhere, and an Age never pays — so a pack that removed the
         * file gets an economy that plainly does not pay rather than one paying by numbers nobody can see.
         */
        val NONE = DangerTable(
            lines = Lines(
                paysAt = Double.MAX_VALUE,
                ruinsBelow = Double.MAX_VALUE,
                little = Double.MAX_VALUE,
                some = Double.MAX_VALUE,
                much = Double.MAX_VALUE,
                immense = Double.MAX_VALUE,
            ),
            combining = 1.0,
            confined = NOTHING,
            ground = Ground(emptyMap(), emptyMap(), NOTHING, emptyMap(), emptyMap(), emptyMap()),
            creatures = emptyMap(),
            darkness = Darkness(NOTHING, NOTHING, NOTHING, NOTHING),
            hardiness = Hardiness(UNCHANGED, UNCHANGED, UNCHANGED, AS_IT_IS, AS_IT_IS, AS_IT_IS),
            phenomena = emptyMap(),
            phenomenonDials = PhenomenonDials(UNCHANGED, AS_IT_IS, UNCHANGED),
            features = emptyMap(),
            conditions = emptyMap(),
            gravity = GravityHazard(NOTHING, NOTHING, Double.MAX_VALUE),
            wounds = NOTHING,
        )

        val CODEC: Codec<DangerTable> = RecordCodecBuilder.create { instance ->
            instance.group(
                Lines.CODEC.fieldOf("lines").forGetter(DangerTable::lines),
                Codec.DOUBLE.fieldOf("combining").forGetter(DangerTable::combining),
                Codec.DOUBLE.fieldOf("confined").forGetter(DangerTable::confined),
                Ground.CODEC.fieldOf("ground").forGetter(DangerTable::ground),
                RATINGS.fieldOf("creatures").forGetter { it.creatures },
                Darkness.CODEC.fieldOf("darkness").forGetter(DangerTable::darkness),
                Hardiness.CODEC.fieldOf("hardiness").forGetter(DangerTable::hardiness),
                RATINGS.fieldOf("phenomena").forGetter { it.phenomena },
                PhenomenonDials.CODEC.fieldOf("phenomenon_dials").forGetter(DangerTable::phenomenonDials),
                RATINGS.fieldOf("features").forGetter { it.features },
                RATINGS.fieldOf("conditions").forGetter { it.conditions },
                GravityHazard.CODEC.fieldOf("gravity").forGetter(DangerTable::gravity),
                Codec.DOUBLE.fieldOf("wounds").forGetter(DangerTable::wounds),
            ).apply(instance, ::DangerTable)
        }

        /** The table this server is running, read once per datapack load. */
        fun of(server: MinecraftServer): DangerTable = current.of(server)

        private val current = PerReload { server -> load(server.resourceManager) }

        /**
         * The table in [resources] — the whole of the loading, and usable offline.
         *
         * **The last pack to ship the file wins outright**: a rating list is a balance rather than a set of entries,
         * and half of each of two tables would be a balance nobody chose.
         */
        fun load(resources: ResourceManager): DangerTable {
            val problems = mutableListOf<String>()
            val layers = resources.getResourceStack(FILE)
            val table = layers.asReversed().firstNotNullOfOrNull { ResourceParsing.parse(it, FILE, CODEC, problems) }
            for (problem in problems) Constants.LOG.error("Danger table: {}", problem)
            if (table == null && layers.isNotEmpty()) {
                Constants.LOG.error("No pack ships a readable {}, so no Age can pay out", FILE)
            }
            return table ?: NONE
        }
    }
}
