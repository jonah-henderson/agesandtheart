package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.Constants
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * The choices a writer made about one preset — `arrangement=rings`, `suns=2`, `wear=0.45..1.0`.
 *
 * Stored by name rather than by position, so a preset can gain a parameter without invalidating recipes
 * already written. Unrecognised names are *kept*, not dropped: they are ignored when the world is built
 * and reported by `/age list`, so a typo is visible rather than merely ineffective.
 */
@JvmInline
value class Options(val chosen: Map<String, List<String>> = emptyMap()) {

    /**
     * The option chosen for [parameter] **where [biome] is the ground**, or its default.
     *
     * A dial is one value where a population is many, so confining one is not a filter over a list — it is
     * a second value that wins in one place. `daylight=-1..-0.7,0.5..1.0[in=minecraft:bamboo_jungle]` is a
     * dark Age with one bright corner: the bracketed value where it says, the bare one everywhere else.
     *
     * The brackets are [Claim]'s, read by [Claim.read], because a dial and a claim should not need two
     * spellings for the same idea — and the aspects a dial may be confined on are the same four that may confine
     * a claim, since it is the same fact about vanilla that makes either meaningful.
     */
    fun of(parameter: Parameter, biome: Identifier? = null, member: String? = null): String {
        val said = chosen[parameter.name].orEmpty().map(Claim::read).filter { parameter.accepts(it.value) }
        val ofThisMember = member?.let { asked -> said.firstOrNull { asked in it.members } }
        val ofEveryMember = said.filter { it.members.isEmpty() }
        val here = ofEveryMember.firstOrNull { it.confinedTo != null && it.confinedTo == biome }
        return (ofThisMember ?: here ?: ofEveryMember.firstOrNull { it.confinedTo == null })?.value ?: parameter.default
    }

    /**
     * Who holds [value] for [parameter]: everyone, where it was said of the whole aspect, or the members it was
     * said of — what a consumer asking member by member needs to be handed in one piece.
     */
    fun holders(parameter: Parameter, value: String = Parameter.TRUE): Holders = holdersWhere(parameter) { it == value }

    /** Who holds any value at all of [parameter] — every member, or the members a value was said of. */
    fun holdersOfAny(parameter: Parameter): Holders = holdersWhere(parameter) { true }

    private fun holdersWhere(parameter: Parameter, wanted: (String) -> Boolean): Holders {
        val said = chosen[parameter.name].orEmpty().map(Claim::read).filter { wanted(it.value) }
        return Holders(everyone = said.any { it.members.isEmpty() }, only = said.flatMap { it.members }.toSet())
    }

    /**
     * Whether a flag was set — the only reading a [Parameter.flag] has, and the reason it is here rather
     * than at each call site comparing against a constant.
     */
    fun isTrue(parameter: Parameter, biome: Identifier? = null, member: String? = null): Boolean =
        of(parameter, biome, member) == Parameter.TRUE

    /** Everything written for [parameter], brackets and all — what a second ground is appended to. */
    fun allSpelled(parameter: String): Set<String> = chosen[parameter].orEmpty().toSet()

    /**
     * Every biome anything written under [name] was confined to, in the order they were written — a
     * parameter's values and a pool's members alike, since both are stored under a name.
     */
    fun confinedIn(name: String): List<Identifier> =
        chosen[name].orEmpty().mapNotNull { Claim.read(it).confinedTo }.distinct()

    /**
     * Every option chosen for [parameter], which for a material means mingled rather than divided (§3.2).
     *
     * Empty rather than the default where nothing valid was named, so a consumer can tell "they said
     * nothing" from "they said the default".
     */
    fun allOf(parameter: Parameter): List<String> =
        chosen[parameter.name].orEmpty().map { Claim.read(it).value }.filter(parameter::accepts)

    // -- asked of a pool -------------------------------------------------------------------------------
    //
    // A pool holds members where a parameter holds a value, and both are stored under a name, so what
    // differs is only what counts as a legal entry. `accepts` is the whole of the difference.

    fun allOf(pool: Pool): List<String> =
        chosen[pool.name].orEmpty().map { Claim.read(it).value }.filter(pool::accepts)

    /**
     * Every member written for [pool] with what the writer asked of it ([Claim]). The mark is
     * stripped before the value is validated, or a struck-out value would fail `Identifier.tryParse` and
     * the exclusion would not happen.
     */
    fun claimsOn(pool: Pool): List<Claim> =
        chosen[pool.name].orEmpty().map(Claim::read).filter { pool.accepts(it.value) }

    /** How these options skew [pool] where [biome] is the ground — [claimsOn] read through [Pool.skewOf]. */
    fun skewOn(pool: Pool, biome: Identifier? = null): Skew = pool.skewOf(claimsOn(pool), biome)

    /**
     * The blocks chosen for a **material** [parameter] — the ids read back as real blocks.
     *
     * A block a mod has since removed is dropped with a complaint rather than failing the Age: it must
     * still open, and the rest of a mingling still reads. `unchanged` is not a block and never was, so it
     * leaves before anything tries to look it up.
     */
    fun materialsOf(parameter: Parameter): List<BlockState> = allOf(parameter)
        .filter { it != Parameter.UNCHANGED }
        .mapNotNull { named ->
            val id = Identifier.tryParse(named)?.let(Materials::laidAs) ?: return@mapNotNull null
            // `orElseGet { null }` no longer compiles: Minecraft ships nullness annotations now, so Kotlin
            // holds `Optional`'s supplier to returning something. Reads better as a guard in any case.
            val block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null)
            if (block == null) {
                Constants.LOG.warn("An Age names a block this pack does not have: {}", named)
                return@mapNotNull null
            }
            block.defaultBlockState()
        }

    /**
     * Where [parameter]'s axis was left, in the terms every span shares, or null where nothing bound it
     * and the answer is whatever that landform calls ordinary.
     *
     * [salt] decides where inside the span the value lands, so an Age rebuilds identically while two
     * axes bounded alike do not move together; a word's lean ([Span.bend]) decides where half of them fall.
     */
    fun steer(parameter: Parameter, salt: Long, biome: Identifier? = null, member: String? = null): Double? {
        val span = Span.read(of(parameter, biome, member)) ?: return null
        if (span == Span.NATURAL) return null
        return span.drawnAt(XoroshiroRandomSource(salt xor parameter.name.hashCode().toLong()).nextDouble())
    }

    /**
     * Names neither this preset nor its aspect understands — a typo, or a parameter some later version removed.
     *
     * **The aspect's parameters count too.** A preset aspect may hold parameters of its own that no preset declares —
     * `Sky.SKYLIGHT` belongs to every sky rather than to one — and asking the preset alone reported them as
     * unrecognised in `/age list` while they were working perfectly.
     */
    fun unknownTo(preset: Taggable): Set<String> = unknownAmong(preset.ownParameters + preset.aspect.parameters)

    /** The same, of an aspect that seats no preset: its parameters are the whole of what it understands. */
    fun unknownAmong(understood: Collection<Parameter>): Set<String> =
        chosen.keys - understood.map(Parameter::name).toSet()

    /**
     * Values a parameter it *does* understand cannot take — `sun.size=huge` where the axis wants a span,
     * `sealed=sometimes` where it takes two words.
     *
     * **Told apart from [unknownAmong], because the mistake and the remedy are different**: there the parameter
     * does not exist, here the parameter is right and the value means nothing. Both were silent, and this one
     * cost a walk — `[Options.of]` filters a value it cannot read and falls back to the default, so an Age
     * written with one is *identical* to an Age written without it and says so nowhere.
     */
    fun unreadableAmong(understood: Collection<Parameter>): List<Pair<String, String>> {
        val byName = understood.associateBy(Parameter::name)
        return chosen.entries.flatMap { (name, values) ->
            val parameter = byName[name] ?: return@flatMap emptyList()
            values.map(Claim::read).filterNot { parameter.accepts(it.value) }.map { name to it.value }
        }
    }

    /** How a writer would have said it: `arrangement=rings stone=blackstone,tuff`, or nothing at all. */
    override fun toString(): String = chosen.entries.sortedBy { it.key }
        .joinToString(" ") { (parameter, options) -> "$parameter=${options.joinToString(",")}" }

    companion object {
        val NONE = Options()

        /**
         * A value is a list, and a single one still reads and writes as a bare string — the same either-or
         * trick `AgeComposition` uses for a set-valued aspect, so the common case keeps one spelling.
         */
        val CODEC: Codec<Options> = Codec.unboundedMap(
            Codec.STRING,
            Codec.either(Codec.STRING.listOf(), Codec.STRING).xmap(
                { either -> either.map({ many -> many }, ::listOf) },
                { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
            ),
        ).xmap(::Options, Options::chosen)
    }
}

/** Who holds a value: every member of an aspect, or only the members named — see [Options.holders]. */
data class Holders(val everyone: Boolean = false, val only: Set<String> = emptySet()) {
    val isNobody: Boolean get() = !everyone && only.isEmpty()

    fun includes(member: String?): Boolean = everyone || (member != null && member in only)

    /** Both at once: everyone where either is, and every member either names. */
    infix fun and(other: Holders): Holders = Holders(everyone || other.everyone, only + other.only)

    companion object {
        val NOBODY = Holders()
        val EVERYONE = Holders(everyone = true)
    }
}
