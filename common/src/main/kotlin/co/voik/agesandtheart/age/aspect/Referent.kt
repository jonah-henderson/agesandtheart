package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier

/**
 * An aspect preset whose value **is** a registry object — a block for the sea (design §3.1). An open
 * aspect holds the id directly, so §8's derived vocabulary reaches it with no adapting layer.
 *
 * **A referent is not automatically a candidate.** The pool a vague word draws from is what
 * `preset_tags/<aspect>.json` names and nothing else (§8.2) — an exact word points at one referent and
 * arrives with its answer in hand, which keeps the resolver's work proportional to our curation rather
 * than to the size of the modpack.
 */
interface Referent : AspectPreset {
    /** The registry entry this names. Its `namespace:path` spelling is also the key a recipe records. */
    val id: Identifier

    override val key: String get() = id.toString()

    override fun getSerializedName(): String = key
}

/**
 * Whether [key] spells a registry object rather than an authored preset. Unambiguous by construction: a
 * referent's key is always `namespace:path`, and an authored key is lower_snake_case with no colon.
 */
fun namesReferent(key: String): Boolean = ':' in key
