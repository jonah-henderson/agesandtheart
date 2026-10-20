package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Span
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/** One minting, and what landed in its clause beside it. */
private class Minting(val mints: Effect.Mints, val landing: Landing, val clause: List<Landing>) {
    /** The substances laid in its clause, firmest first. */
    val substances: List<Landing> get() = clause.filter { landing -> landing.effects.any { it is Effect.MadeOf } }
}

private val Landing.substance: String? get() = effects.filterIsInstance<Effect.MadeOf>().firstOrNull()?.substance

/** Every minting that landed, with the clause it was laid in. */
private fun Resolving.mintings(): List<Minting> = said.mapNotNull { landing ->
    val mints = landing.effects.filterIsInstance<Effect.Mints>().firstOrNull() ?: return@mapNotNull null
    val clause = if (landing.clause == null) emptyList() else said.filter { other ->
        other !== landing && other.place == landing.place && other.clause == landing.clause
    }
    Minting(mints, landing, clause)
}

/**
 * [composition] with every feature what landed minted added to what the Age places — `ink springs`, `gold_block
 * obelisks` (world model §2). Each is made of the substances laid in its clause, joined ones mingling and the firmest
 * leading the rest, or of the pattern's own where none was; and takes its clause's size, height and abundance.
 */
internal fun Resolving.mintedFeatures(composition: AgeComposition): AgeComposition {
    val minted = mintings().map { minting ->
        Claim(
            minting.mints.pattern,
            minting.landing.polarity,
            Rung.legible(abundanceOf(minting.clause + minting.landing)),
            minting.landing.sitedIn,
            madeOf = madeOf(minting)?.joinToString(Claim.MINGLED.toString()),
            size = asked(minting.clause, SIZE),
            height = asked(minting.clause, HEIGHT),
        )
    }.filter { it.madeOf != null }
    if (minted.isEmpty()) return composition
    val already = composition.optionsFor(Aspect.FEATURES, 0).allSpelled(Features.PLACES.name)
    return composition.withOptions(Aspect.FEATURES, Features.PLACES.name, (already + minted.map { it.spelled() }).distinct().toList())
}

/**
 * What [minting] is made of: the leading substance of its clause and whatever is joined to it or the same, or the
 * pattern's own where none was laid, or null where it has neither and so mints nothing.
 */
private fun Resolving.madeOf(minting: Minting): List<String>? {
    val substances = ranked(Aspect.FEATURES, minting.substances)
    val leading = substances.firstOrNull() ?: return minting.mints.unstated?.let(::listOf)
    fun minglesWithTheLeader(said: Landing) = said === leading || said.isJoinedTo(leading) || said.substance == leading.substance
    return substances.filter(::minglesWithTheLeader).mapNotNull { it.substance }.distinct()
}

/** The middle of the span [parameter] was set to in [clause], or null where it was not. */
private fun asked(clause: List<Landing>, parameter: String): Double? =
    clause.firstNotNullOfOrNull { it.settings[parameter] }?.let(Span::read)?.let { (it.least + it.most) / 2.0 }

/**
 * A minting asked to be made of something it cannot hold — `gold_block springs`, a spring of a solid. Charged as the
 * substance and the minting disagreeing, and the spring keeps its own water.
 */
internal fun Resolving.mintingsThatCannotHold(): List<Flaw> = mintings().mapNotNull { minting ->
    if (!minting.mints.flows) return@mapNotNull null
    val substance = ranked(Aspect.FEATURES, minting.substances).firstOrNull() ?: return@mapNotNull null
    if (flows(substance.substance)) return@mapNotNull null
    flaw(Register.INCOHERENT, listOf(substance, minting.landing), Aspect.FEATURES)
}

/** Whether a block has a fluid in it, which is the whole of what a spring asks of its substance. */
private fun flows(block: String?): Boolean = block?.let(Identifier::tryParse)
    ?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
    ?.defaultBlockState()?.fluidState?.isEmpty == false

private const val SIZE = "size"
private const val HEIGHT = "height"
