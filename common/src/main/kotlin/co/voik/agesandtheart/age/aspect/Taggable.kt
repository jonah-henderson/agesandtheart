package co.voik.agesandtheart.age.aspect

import net.minecraft.util.StringRepresentable

/**
 * Something an aspect can hold — **and therefore something the tag layer can describe.**
 *
 * Two quite different things implement this. An [AuthoredPreset] is a design this pack wrote, enumerated
 * in code, with parameters of its own; a [RegistryReference] is a pointer into one of the game's
 * registries, of which there are thousands and which nobody here wrote. They share no shape and no
 * provenance.
 *
 * **What they share is that a tag can be hung on them**, which is what puts them in one pool a word can
 * reach, and it is the only thing they have in common — so the interface is named for it rather than for
 * some noun that has to cover both. `art/preset_tags/` describes exactly these.
 */
interface Taggable : StringRepresentable {
    /** How a recipe records it: a bare `lower_snake_case` name, or a `namespace:path` id. */
    val key: String

    val aspect: Aspect


    /**
     * Whether a sentence may ask for this, as opposed to only a pinned recipe naming it outright.
     *
     * Almost every preset is askable and `VocabularyCheck` insists on it, since one no word can reach is
     * content nobody can use. Declared here rather than inferred from a missing `preset_tags` entry,
     * because an omission and an intention look identical — the check separately insists that anything
     * answering `false` really is pinned somewhere.
     */
    val askableInASentence: Boolean get() = true

    /**
     * Whether this preset would actually *do* anything with [parameter], as opposed to recognising the
     * name.
     *
     * The resolver reads this to prefer a preset that can honour what the sentence asked for. Where
     * nothing in the aspect can, the word is charged rather than dropped (§3.3).
     */
    fun honours(parameter: Parameter): Boolean = ownParameters.any { it.name == parameter.name }

    /**
     * The same question asked by name, which is how the resolver has it. False for a name never declared,
     * so "does not offer it" and "offers it but ignores it" answer alike — telling those apart is
     * [Options.unknownTo]'s job.
     */
    fun honoursParameterNamed(name: String): Boolean = ownParameters.any { it.name == name }

    override fun getSerializedName(): String
}
