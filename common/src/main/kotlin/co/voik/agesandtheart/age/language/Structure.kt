package co.voik.agesandtheart.age.language

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier

/**
 * The pages that are structure rather than meaning: they say how other pages go together. Named in a pack's
 * `art/grammar/` by the production each is.
 */
enum class Structure(val production: String, val price: Int) {
    /** The Age itself, which takes everything laid before it bare. Every book has one. */
    AGE("nucleus", price = 1),

    /** Binds the terms either side of it: `pillars and hills landmass`, `trees and villages`. */
    AND("conjunction", price = 2),

    /** Sites the last word that took pages, and what it took, in a biome: `beautiful plants in jungle`. */
    IN("in", price = 2),

    /** Widens the last word that took pages to every place: `cats everywhere`. */
    EVERYWHERE("everywhere", price = 2),

    /** The member after it and nothing else of its kind: `only villages`. */
    ONLY("only", price = 2),

    /** Anything of its kind but the member after it: `no villages`. */
    EXCEPT("except", price = 2),

    /** Where a link into the Age arrives, read with the `in` after it: `link in jungle`, `link in sea`. */
    LINK("link", price = 2),

    /**
     * The word before it said of the word after: `herds of warden` are herds of wardens, `pyramids of gold_block`
     * pyramids made of it. Everything the first word took goes with it.
     */
    OF("of", price = 2),
    ;

    companion object {
        /** The structure a pack's `production` names, or null for any other. */
        fun named(production: String): Structure? = entries.firstOrNull { it.production == production }

        private val CODEC: Codec<Structure> = Codec.STRING.comapFlatMap(
            { production -> named(production)?.let { DataResult.success(it) } ?: DataResult.error { "'$production' is no structure" } },
            Structure::production,
        )
    }

    /** A structure page as a pack names it: `art/grammar/and.json` is the page `and`. */
    data class Page(val id: Identifier, val structure: Structure) {
        val name: String get() = id.path

        companion object {
            fun mapCodec(id: Identifier): MapCodec<Page> = RecordCodecBuilder.mapCodec { instance ->
                instance.group(CODEC.fieldOf("production").forGetter(Page::structure)).apply(instance) { Page(id, it) }
            }
        }
    }
}
