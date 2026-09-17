package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import java.util.Optional

/**
 * One rewrite. Rules are tried in file order at each position, so longer sequences must come first or
 * `sh` will never beat `s`.
 */
data class TransliterationRule(val from: String, val to: String) {
    companion object {
        val CODEC: Codec<TransliterationRule> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("from").forGetter(TransliterationRule::from),
                Codec.STRING.fieldOf("to").forGetter(TransliterationRule::to),
            ).apply(instance, ::TransliterationRule)
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, TransliterationRule> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            TransliterationRule::from,
            ByteBufCodecs.STRING_UTF8,
            TransliterationRule::to,
            ::TransliterationRule,
        )
    }
}

/**
 * The script the Art is written in: authored spellings, and rules approximating one for everything else.
 *
 * Output is a string in the **typeface's** encoding rather than anything a reader would recognise — the
 * font maps ordinary codepoints to its own glyphs. Which language this is says nothing to the code: it
 * is whatever `art/script` and `art/transliteration.json` describe, and the whole of it is droppable.
 *
 * Spellings accrete across packs. The rule table is replaced whole by the highest-priority pack that
 * ships one, because interleaving two packs' ordered rewrites produces a table neither author wrote.
 */
data class Script(
    private val spellings: Map<String, String>,
    val rules: List<TransliterationRule>,
    /**
     * The typeface to draw spellings in, or null to draw them in the ordinary one. Named by the data so
     * that swapping the script means swapping files, never code.
     */
    val font: Identifier?,
) {
    /** Where the font's definition lives, for a client to check it was actually shipped. */
    val fontAsset: Identifier?
        get() = font?.let { Identifier.fromNamespaceAndPath(it.namespace, "font/${it.path}.json") }

    /** How [name] is written — authored if anyone said so, approximated otherwise. */
    fun spell(name: String): String = spellings[name] ?: transliterate(name)

    /**
     * A whole line written out — **each word spelled on its own**, so an authored spelling still applies
     * inside a sentence rather than only to a word standing alone.
     *
     * Punctuation is left against the word it followed and rewritten by the rules like anything else, since
     * a script may well have its own comma.
     */
    fun spellEachWord(line: String): String =
        line.split(' ').joinToString(" ", transform = ::spellWithWhateverFollowsIt)

    private fun spellWithWhateverFollowsIt(token: String): String {
        val word = token.trimEnd(*NOT_PART_OF_A_WORD)
        return spell(word) + transliterate(token.drop(word.length))
    }

    /** Whether a human chose this spelling, as opposed to [transliterate] having guessed it. */
    fun isAuthored(name: String): Boolean = name in spellings

    /**
     * [source] rewritten by [rules]. Lower-cased first: a typeface's capitals are usually *distinct
     * glyphs* rather than shifted forms, so an incidental capital prints the wrong letter.
     */
    fun transliterate(source: String): String {
        val text = source.lowercase()
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            val rule = rules.firstOrNull { text.startsWith(it.from, at) }
            if (rule == null) {
                out.append(text[at])
                at++
            } else {
                out.append(rule.to)
                at += rule.from.length
            }
        }
        return out.toString()
    }

    companion object {
        /** What a reading puts against a word, which is no part of the word's own spelling. */
        private val NOT_PART_OF_A_WORD = charArrayOf(',', '.')

        /** Where a pack puts authored spellings, one page per file. */
        const val SPELLING_DIRECTORY = "art/script"

        /** The rule table, whole. Not a directory: see the class doc on why these do not merge. */
        const val RULES_FILE = "art/transliteration.json"

        /** Nothing authored and nothing rewritten — every word comes out in plain letters. */
        val NONE = Script(emptyMap(), emptyList(), font = null)

        val CODEC: Codec<Script> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("spellings", emptyMap())
                    .forGetter { it.spellings },
                TransliterationRule.CODEC.listOf().optionalFieldOf("rules", emptyList())
                    .forGetter(Script::rules),
                Identifier.CODEC.optionalFieldOf("font").forGetter { Optional.ofNullable(it.font) },
            ).apply(instance) { spellings, rules, font -> Script(spellings, rules, font.orElse(null)) }
        }

        val STREAM_CODEC: StreamCodec<io.netty.buffer.ByteBuf, Script> = StreamCodec.composite(
            ByteBufCodecs.map(::LinkedHashMap, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8),
            { it.spellings },
            TransliterationRule.STREAM_CODEC.apply(ByteBufCodecs.list()),
            Script::rules,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC),
            { Optional.ofNullable(it.font) },
            { spellings, rules, font -> Script(spellings, rules, font.orElse(null)) },
        )

        private data class SpellingPage(val spellings: Map<String, String>) {
            companion object {
                val CODEC: Codec<SpellingPage> = RecordCodecBuilder.create { instance ->
                    instance.group(
                        Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("spellings")
                            .forGetter(SpellingPage::spellings),
                    ).apply(instance, ::SpellingPage)
                }
            }
        }

        private data class RulePage(val rules: List<TransliterationRule>, val font: Identifier?) {
            companion object {
                val CODEC: Codec<RulePage> = RecordCodecBuilder.create { instance ->
                    instance.group(
                        TransliterationRule.CODEC.listOf().fieldOf("rules").forGetter(RulePage::rules),
                        Identifier.CODEC.optionalFieldOf("font").forGetter { Optional.ofNullable(it.font) },
                    ).apply(instance) { rules, font -> RulePage(rules, font.orElse(null)) }
                }
            }
        }

        fun load(resources: ResourceManager, problems: MutableList<String>): Script {
            val spellings = mutableMapOf<String, String>()
            val stacks = resources.listResourceStacks(SPELLING_DIRECTORY, ResourceParsing::isJson)
            for ((file, layers) in stacks.entries.sortedBy { it.key.toString() }) {
                for (layer in layers) {
                    val page = ResourceParsing.parse(layer, file, SpellingPage.CODEC, problems) ?: continue
                    spellings += page.spellings
                }
            }
            // `getResource`, not the stack: the top pack's table wins whole.
            val page = resources.getResource(RULES_FILE.location()).orElse(null)?.let { resource ->
                ResourceParsing.parse(resource, RULES_FILE.location(), RulePage.CODEC, problems)
            }
            return Script(spellings.toMap(), page?.rules.orEmpty(), page?.font)
        }
    }
}
