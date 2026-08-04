package co.voik.agesandtheart.age.aspect

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

    /** The option chosen for [parameter], or its default — the first, where several were named. */
    fun of(parameter: Parameter): String = allOf(parameter).firstOrNull() ?: parameter.default

    /**
     * Every option chosen for [parameter], which for a material means mingled rather than divided (§3.2).
     *
     * Empty rather than the default where nothing valid was named, so a consumer can tell "they said
     * nothing" from "they said the default".
     */
    fun allOf(parameter: Parameter): List<String> =
        chosen[parameter.name].orEmpty().filter(parameter::accepts)

    /**
     * Every value chosen for [parameter] with what the writer asked of it — what a populative parameter
     * reads instead of [allOf] (§4.3.1, [Claim]). The mark is stripped before the value is validated, or
     * a struck-out value would fail `Identifier.tryParse` and the exclusion would not happen.
     */
    fun claimsOn(parameter: Parameter): List<Claim> =
        chosen[parameter.name].orEmpty().map(Claim::read).filter { parameter.accepts(it.value) }

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
            val id = Identifier.tryParse(named) ?: return@mapNotNull null
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
     * How many the writer asked for, where [parameter] is a count — never null, since [of] falls back to
     * the ordinary number when nothing valid was named.
     */
    fun countOf(parameter: Parameter): Int = of(parameter).toIntOrNull() ?: NONE_AT_ALL

    /**
     * Where [parameter]'s axis was left, in the terms every span shares, or null where nothing bound it
     * and the answer is whatever that landform calls ordinary.
     *
     * [salt] decides where inside the span the value lands, so an Age rebuilds identically while two
     * axes bounded alike do not move together.
     */
    fun steer(parameter: Parameter, salt: Long): Double? {
        val span = Span.read(of(parameter)) ?: return null
        if (span == Span.NATURAL) return null
        return span.least + XoroshiroRandomSource(salt xor parameter.name.hashCode().toLong())
            .nextDouble() * span.width
    }

    /** Names this preset does not understand — a typo, or a knob some later version removed. */
    fun unknownTo(preset: AspectPreset): Set<String> =
        chosen.keys - preset.parameters.map(Parameter::name).toSet()

    /** How a writer would have said it: `arrangement=rings stone=blackstone,tuff`, or nothing at all. */
    override fun toString(): String = chosen.entries.sortedBy { it.key }
        .joinToString(" ") { (parameter, options) -> "$parameter=${options.joinToString(",")}" }

    companion object {
        val NONE = Options()

        /** What a count reads as when the parameter holding it is not one — unreachable through [countOf]. */
        private const val NONE_AT_ALL = 0

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
