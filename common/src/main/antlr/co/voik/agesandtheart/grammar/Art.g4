/*
 * The grammar of the Art (design §4.3.1).
 *
 * Deliberately small. Its whole job is **attachment and grouping** — which words modify which subject, and
 * which are joined. Everything that makes the language interesting (precision, contradiction, division, tag
 * satisfaction) happens beneath it in the resolver, which already existed before this did.
 *
 * Two properties are load-bearing and easy to lose:
 *
 * 1. **There are no lexer rules, and that is the point.** The input is never text: it is a list of pages,
 *    each already looked up in the `Vocabulary` and stamped with its class. So the token types are simply
 *    *declared* below and supplied by a `TokenSource` of ours.
 *
 *    Terminals are therefore word CLASSES, never spellings. There is no `EVOCATIVE : 'beautiful' | ...` here,
 *    because vocabulary is datapack content and §8 derives a word per fluid, biome and block in the
 *    modpack — tens of thousands of them, arriving at runtime. The tokeniser looks each page up in the
 *    `Vocabulary` and stamps its class, exactly as a language stamps IDENTIFIER rather than listing every
 *    variable name. So a new *word* needs no grammar change; a new *class* is a real change to the
 *    language, and correctly costs one.
 * 2. **No ambiguity.** §4.3 rejects parse ambiguity as a mechanism: it is a randomness no word can ever take
 *    away, and it is invisible, so it fails the promise that a flawed Age is diagnosable. Vagueness comes
 *    from word choice. Keep this grammar deterministic — if a construct ever admits two readings, that is a
 *    bug in the grammar rather than a feature of the language.
 */
grammar Art;
// Declared, not lexed. `ArtGrammar` supplies these from the vocabulary; see the note at the top.
tokens { EVOCATIVE, PRESET, SETTER, AND, ONLY, EXCEPT }

/** A book is sections, and nothing else. An empty one is legal: the pen never refuses (design §2). */
sentence  : section* EOF ;

/**
 * A subject and the modifiers that follow it — the whole of the structure.
 *
 * Sections are *discovered* rather than declared: whichever word opens one decides which part of the world
 * it is about. So the slot list stays out of the grammar, sections may appear in any order and any number,
 * and adding a slot later costs nothing here.
 */
section   : descriptor* subject modifier* ;

/** Evocative words, which precede their subject and are scoped to it — gently (§4.3.1's tier rule). */
descriptor : EVOCATIVE ;

/**
 * What the section is about: a word that chooses which preset fills a slot.
 *
 * Subject *pages* — `sky`, `land`, a word naming a part of the world rather than a filling of it — belong
 * here too (§4.3.1) and are deliberately absent until the vocabulary has any, on the project's standing
 * rule that a thing the grammar can name and nothing can fill is worse than one that does not exist yet.
 */
subject   : PRESET ;

/**
 * Anything that steers the subject. `only` and `except` bind tighter than juxtaposition and looser than
 * `and`, which is what makes the reading predictable left to right without backtracking.
 */
modifier  : ONLY conjunction
          | EXCEPT conjunction
          | conjunction
          ;

/**
 * Words joined by `and` — "keep both, and keep them apart" (§3.2).
 *
 * Left-recursive so that `a and b and c` is one group of three rather than nested pairs: `and` is
 * associative here, because a parameter holding three values means the same thing however it was written.
 */
conjunction : term (AND term)* ;

term      : SETTER | PRESET ;
