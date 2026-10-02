package co.voik.agesandtheart.age.word

import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/** What a word is called in the player's own language. */
object WordNames {
    /** `word.<namespace>.<path>`, so a pack names its own words the way it names anything else. */
    fun key(word: Identifier): String = "word.${word.namespace}.${word.path}"

    /**
     * The translated name, falling back to the id itself — which is what a derived word gets, there being
     * some hundreds of them and no reason to translate `minecraft:blackstone` twice.
     *
     * Title-cased, because a word of the Art is a name. The authored half is capitalised in the language
     * file so translators keep control of it; only the derived fallback is cased here.
     */
    fun readable(word: Identifier): Component =
        Component.translatableWithFallback(key(word), titleCase(word.path.replace('_', ' ')))

    /**
     * What to call a level: an Age's id path is the name its writer gave it, and anywhere else reads by its
     * dimension's own, so the Overworld reads sensibly too.
     */
    fun placeName(dimension: ResourceKey<Level>): String =
        titleCase(dimension.identifier().path.replace('_', ' '))

    /** Title-cased for display. A place or a word of the Art is a name, and names take capitals. */
    fun titleCase(text: String): String = text
        .split(' ')
        .joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) }
}
