package co.voik.agesandtheart.age.word

import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/** What a word is called in the player's own language. */
object WordNames {
    /** `word.<namespace>.<path>`, so a pack names its own words the way it names anything else. */
    fun key(word: Identifier): String = "word.${word.namespace}.${word.path}"

    /**
     * The translated name, falling back to the id itself — which is what a derived word gets, there being
     * some hundreds of them and no reason to translate `minecraft:blackstone` twice.
     */
    fun readable(word: Identifier): Component =
        Component.translatableWithFallback(key(word), word.path.replace('_', ' '))
}
