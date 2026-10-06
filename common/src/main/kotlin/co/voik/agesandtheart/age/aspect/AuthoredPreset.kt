package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.word.Speech

/**
 * Something an aspect can hold that **this pack wrote** — a landform, a carve pattern, a sky, a
 * phenomenon.
 *
 * The counterpart of [RegistryReference], and the difference is what each is made of rather than what it
 * does: one is a design with parameters of its own, enumerated in code and reachable by a bare
 * lower_snake_case key; the other is a pointer into a registry, of which there are thousands and which
 * this pack did not write.
 *
 * Both are [Taggable], because being describable by tag is exactly what they have in common — and it is
 * the only thing they have in common, which is why the interface is named for it.
 */
interface AuthoredPreset : Taggable {
    /**
     * Parameters this design carries itself, as against [Aspect.parameters], which belong to the aspect —
     * a landform's `wear` and `relief`, a phenomenon's own dials.
     *
     * **Only a design has any.** A [RegistryReference] is a pointer into a registry and carries nothing;
     * anything a writer can steer about the part of the world it fills belongs to the aspect, which is
     * where the sea's `depth` ended up once it stopped pretending to be a capability.
     */
    val parameters: List<Parameter> get() = emptyList()

    /**
     * The word a writer says for this, where it is a **thing with a name** rather than a quality of one.
     *
     * A landform is a proper noun: `craterlands` is one place and no description reaches it, so the corpus needs a
     * page that means it outright and `DerivedWords` mints one from here. A carve pattern is not —
     * `solid`, `porous` and `caves` are qualities of the rock, already reached by `unbroken`, `riddled`
     * and `flooded`, and an exact page beside each of those would only compete with the right word.
     *
     * Spelled here rather than taken from [key] because the two differ where the key is a noun and the
     * page is not: `spire_islands` is said `spires`, and `inverse_caves` is said `inverted`.
     *
     * Null where an authored word file says it instead — a preset whose page carries a claim of its own
     * (`tempest` also sets `happens`) is written by hand, and `VocabularyCheck` refuses both at once.
     */
    val writtenWordFor: String? get() = null

    /** How [writtenWordFor] is said in a book, where the page's own claims would derive it wrongly. */
    val writtenSpeech: Speech? get() = null
}

/**
 * The parameters a taggable carries itself, for the callers that have one in hand and do not care which
 * kind it is — empty for everything but an [AuthoredPreset].
 */
val Taggable.ownParameters: List<Parameter> get() = (this as? AuthoredPreset)?.parameters.orEmpty()
