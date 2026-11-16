package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.Spending
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.Optional

/** What an end is judged against: how far the Age is at odds with itself, what that bought, and its seed. */
data class Unravelling(val instability: Instability, val spending: Spending, val seed: Long)

/**
 * When an Age's own instability calls down a [CataclysmKind] (design §5.3 "The cataclysm"), as data in
 * `art/cataclysm/<kind>.json`'s `inflicted_when`, so the trigger can be tried several ways without code.
 *
 * Each is a pure test of an [Unravelling], so whether an Age has an end coming stays a function of its recipe.
 */
sealed interface EndCondition {

    fun holdsFor(age: Unravelling, kind: CataclysmKind): Boolean

    val type: String

    /** At least [atLeast] of the way into [manifestation]'s [dial], or into all its dials on average where none is named. */
    data class Reach(val manifestation: Manifestation, val dial: String?, val atLeast: Double) : EndCondition {
        override val type get() = REACH

        override fun holdsFor(age: Unravelling, kind: CataclysmKind): Boolean {
            val reached = dial?.let { age.spending.reach(manifestation, it) } ?: age.spending.reach(manifestation)
            return reached >= atLeast - ROUNDING
        }
    }

    /** At least [atLeast] distinct flaws, of [register] alone where one is named. */
    data class Flaws(val atLeast: Int, val register: Register?) : EndCondition {
        override val type get() = FLAWS

        override fun holdsFor(age: Unravelling, kind: CataclysmKind): Boolean {
            val counted = register?.let { wanted ->
                age.instability.flaws.filter { it.register == wanted }.distinctBy { it.words.toSet() }.size
            } ?: age.instability.index
            return counted >= atLeast
        }
    }

    /** The book's flaws reaching at least tier [atLeast]: one strange, two harmful, three failing. */
    data class Tier(val atLeast: Int) : EndCondition {
        override val type get() = TIER

        override fun holdsFor(age: Unravelling, kind: CataclysmKind): Boolean {
            val flaws = age.instability.index
            return flaws > 0 && Spending.topTier(flaws, age.instability.pages) >= atLeast
        }
    }

    /** One Age in [share], drawn from its seed and the kind, so the same Age always draws the same. */
    data class Chance(val share: Double) : EndCondition {
        override val type get() = CHANCE

        override fun holdsFor(age: Unravelling, kind: CataclysmKind): Boolean =
            XoroshiroRandomSource(age.seed xor CHANCE_SALT xor kind.key.hashCode().toLong()).nextDouble() < share
    }

    data class AllOf(val conditions: List<EndCondition>) : EndCondition {
        override val type get() = ALL_OF

        override fun holdsFor(age: Unravelling, kind: CataclysmKind) = conditions.all { it.holdsFor(age, kind) }
    }

    data class AnyOf(val conditions: List<EndCondition>) : EndCondition {
        override val type get() = ANY_OF

        override fun holdsFor(age: Unravelling, kind: CataclysmKind) = conditions.any { it.holdsFor(age, kind) }
    }

    data class Not(val condition: EndCondition) : EndCondition {
        override val type get() = NOT

        override fun holdsFor(age: Unravelling, kind: CataclysmKind) = !condition.holdsFor(age, kind)
    }

    companion object {
        private const val REACH = "reach"
        private const val FLAWS = "flaws"
        private const val TIER = "tier"
        private const val CHANCE = "chance"
        private const val ALL_OF = "all_of"
        private const val ANY_OF = "any_of"
        private const val NOT = "not"

        /** So a dial bought in full still reads as all of it after its share is divided out. */
        private const val ROUNDING = 1e-9
        private const val CHANCE_SALT = 0x6C1D_93A0_4E7B_25F1L

        private val TIERS = mapOf("strange" to Spending.STRANGE, "harmful" to Spending.HARMFUL, "failing" to Spending.FAILING)

        private val TIER_CODEC: Codec<Int> = Codec.STRING.comapFlatMap(
            { name -> TIERS[name]?.let { DataResult.success(it) } ?: DataResult.error { "No tier '$name' — one of ${TIERS.keys}" } },
            { tier -> TIERS.entries.first { it.value == tier }.key },
        )

        private val REGISTER_CODEC: Codec<Register> = Codec.STRING.comapFlatMap(Register::byKey, Register::key)

        private val REACH_CODEC: MapCodec<Reach> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Manifestation.CODEC.fieldOf("manifestation").forGetter(Reach::manifestation),
                Codec.STRING.optionalFieldOf("dial").forGetter { Optional.ofNullable(it.dial) },
                Codec.DOUBLE.fieldOf("at_least").forGetter(Reach::atLeast),
            ).apply(instance) { manifestation, dial, atLeast -> Reach(manifestation, dial.orElse(null), atLeast) }
        }

        private val FLAWS_CODEC: MapCodec<Flaws> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("at_least").forGetter(Flaws::atLeast),
                REGISTER_CODEC.optionalFieldOf("register").forGetter { Optional.ofNullable(it.register) },
            ).apply(instance) { atLeast, register -> Flaws(atLeast, register.orElse(null)) }
        }

        private val TIER_MAP_CODEC: MapCodec<Tier> =
            TIER_CODEC.fieldOf("at_least").xmap(::Tier, Tier::atLeast)

        private val CHANCE_CODEC: MapCodec<Chance> =
            Codec.doubleRange(0.0, 1.0).fieldOf("share").xmap(::Chance, Chance::share)

        val CODEC: Codec<EndCondition> = Codec.lazyInitialized {
            Codec.STRING.partialDispatch("type", { DataResult.success(it.type) }, ::codecFor)
        }

        private fun codecFor(type: String): DataResult<MapCodec<out EndCondition>> = when (type) {
            REACH -> DataResult.success(REACH_CODEC)
            FLAWS -> DataResult.success(FLAWS_CODEC)
            TIER -> DataResult.success(TIER_MAP_CODEC)
            CHANCE -> DataResult.success(CHANCE_CODEC)
            ALL_OF -> DataResult.success(CODEC.listOf().fieldOf("of").xmap(::AllOf, AllOf::conditions))
            ANY_OF -> DataResult.success(CODEC.listOf().fieldOf("of").xmap(::AnyOf, AnyOf::conditions))
            NOT -> DataResult.success(CODEC.fieldOf("of").xmap(::Not, Not::condition))
            else -> DataResult.error { "No end condition of type '$type' — one of ${listOf(REACH, FLAWS, TIER, CHANCE, ALL_OF, ANY_OF, NOT)}" }
        }
    }
}
