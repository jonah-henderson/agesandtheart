package co.voik.agesandtheart.age.aspect

import net.minecraft.core.Registry
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey

/**
 * Something an aspect can hold whose value **is a registry entry** — a block for the sea (design §3.1).
 * An open aspect holds the id directly, so §8's derived vocabulary reaches it with no adapting layer.
 *
 * **One of these is not automatically a candidate.** The pool a vague word draws from is what
 * `preset_tags/<aspect>.json` names and nothing else (§8.2) — an exact word points at one and arrives with
 * its answer in hand, which keeps the resolver's work proportional to our curation rather than to the
 * size of the modpack.
 */
interface RegistryReference : Taggable {
    /** The registry entry this names. Its `namespace:path` spelling is also the key a recipe records. */
    val id: Identifier

    /**
     * Which registry [id] is an entry of.
     *
     * **An id cannot say this for itself**, and that is the whole of why it is written down:
     * `minecraft:diamond_block` and `minecraft:village_plains` are the same shape, so nothing about the
     * string alone stops a block being taken for a structure set.
     */
    val registry: ResourceKey<out Registry<*>>

    override val key: String get() = id.toString()

    override fun getSerializedName(): String = key
}

/**
 * Whether [key] spells a registry entry rather than something this pack wrote. Unambiguous by
 * construction: a [RegistryReference]'s key is always `namespace:path`, and an [AuthoredPreset]'s is
 * lower_snake_case with no colon.
 */
fun namesARegistryEntry(key: String): Boolean = ':' in key
