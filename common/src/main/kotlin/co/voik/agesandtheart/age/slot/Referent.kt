package co.voik.agesandtheart.age.slot

import net.minecraft.resources.ResourceLocation

/**
 * A slot preset whose value **is** a registry object — a block for the medium, a biome for the dressing
 * (design §3.1, "open and closed slots").
 *
 * The alternative was a hand-written preset wrapping each one: a `lava` preset that means
 * `minecraft:lava`, a `cherry_grove` preset that means `minecraft:cherry_grove`. That layer adds a name
 * to maintain, a translation to write and a chance for the two to diverge, and buys nothing the registry
 * entry did not already have. So an open slot holds the id directly, and §8's derived vocabulary reaches
 * it with no adapting layer at all.
 *
 * **A referent is not automatically a candidate.** The pool a vague word draws from is what
 * `preset_tags/<slot>.json` names and nothing else (§8.2), so the registry being reachable does not make
 * it *searchable*: an exact word points at one referent and arrives with its answer already in hand. That
 * is what keeps the resolver's work proportional to our curation rather than to the size of the modpack.
 */
interface Referent : SlotPreset {
    /** The registry entry this names. Its `namespace:path` spelling is also the key a recipe records. */
    val id: ResourceLocation

    override val key: String get() = id.toString()

    override fun getSerializedName(): String = key
}

/**
 * Whether [key] spells a registry object rather than an authored preset.
 *
 * The two can never be confused, and by construction rather than by care: a referent's key is always
 * `namespace:path` because that is what [ResourceLocation.toString] produces, and no authored preset key
 * has ever contained a colon — they are lower-snake-case words like `bare_rock`. So a recipe written
 * before open slots existed reads back unambiguously, which is what makes that migration free.
 */
fun namesReferent(key: String): Boolean = ':' in key
