package co.voik.agesandtheart.age.word

import com.mojang.serialization.Codec
import net.minecraft.util.StringRepresentable

/**
 * **What part of an English sentence a word is**, which is all a book's prose needs to know about it and
 * nothing the Art itself reads.
 *
 * The grammar's classes say where a page may stand; they cannot say how it is spoken. `beautiful` and
 * `trees` are both terms, and one is said *of* the land while the other is something the land holds — so a
 * reading that put them in one sentence the same way would say "its land is trees".
 *
 * Read off what a word *is* wherever that settles it — a block is a substance, a creature or a biome is a
 * thing there are several of — and authored for the rest, where it is one line in a word file.
 */
enum class Speech(private val key: String) : StringRepresentable {
    /** Said of something: `floating`, `beautiful`, `red`. The default, since most authored words are. */
    ADJECTIVE("adjective"),

    /** One of many, counted, and so made plural when a sentence names them: `zombie`, `dark_forest`. */
    NOUN("noun"),

    /** Already plural as written, and said as it stands: `trees`, `ruins`, `villages`. */
    PLURAL("plural"),

    /** A substance, never counted: `basalt`, `lava`, `sandfall`. */
    MASS("mass"),
    ;

    /** Whether a sentence names this as a thing there is, rather than saying it of something. */
    val names: Boolean get() = this == NOUN || this == PLURAL

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Speech> = StringRepresentable.fromEnum(Speech::values)
    }
}
