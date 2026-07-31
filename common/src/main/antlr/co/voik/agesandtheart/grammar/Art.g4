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
tokens { EVOCATIVE, SUBJECT, PRESET, SETTER, QUANTIFIER, AND, ONLY, EXCEPT }

/** A book is sections, and nothing else. An empty one is legal: the pen never refuses (design §2). */
sentence  : section* EOF ;

/**
 * A subject and the modifiers that follow it — the whole of the structure.
 *
 * Sections are *discovered* rather than declared: a page naming a part of the world opens one, and
 * everything after it belongs to that part until the next such page. So the aspect list stays out of the
 * grammar, sections may appear in any order and any number, and adding an aspect later costs nothing here.
 *
 * **A section is opened by an aiming page, never by a word that fills something.** Players do not write
 * presets — presets are ours, an internal tool a word is mapped onto at our leisure — so the pages that
 * carve a book into sections are the *targets* a writer aims at: `landmass`, `climate`, `sky`.
 *
 * The other two alternatives are sections with **no subject at all**, and both are the beginner's book,
 * which is the commonest thing anyone writes:
 *
 * - `descriptor+ modifier*` — evocative words with nothing to aim them, which say what the whole Age is like.
 * - `modifier+` — "a world of blackstone", naming what the rock is made of and no shape at all. Without it a
 *   writer holding only material pages could say nothing.
 *
 * Each alternative demands at least one page, so none of them matches the empty string — `section*` over a
 * rule that could match nothing would never terminate.
 */
section   : descriptor* subject modifier*
          | descriptor+ modifier*
          | modifier+
          ;

/** Evocative words, which precede their subject and are scoped to it — gently (§4.3.1's tier rule). */
descriptor : EVOCATIVE ;

/**
 * What the section is about: a page naming a part of the world and supplying no value of its own, whose
 * entire job is to aim what follows it (design §4.3.1).
 *
 * These are found pages and cost ink, which is what keeps aiming a *precision lever* rather than free: a
 * beginner holding none of them writes one unaimed section, and everything they say is about the Age.
 */
subject   : SUBJECT ;

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

/**
 * One thing said about the section, optionally with how much of it there should be.
 *
 * The quantifier **precedes what it counts** — `teeming villages` — which is the third rung of §4.5's skill
 * tree and the only production so far that binds one page to one other page rather than joining peers. It
 * is deliberately not a word of its own class in the world model: what it modifies is a *claim*, so the
 * rung travels with the value into the recipe (`minecraft:villages@teeming`).
 */
term      : QUANTIFIER? (SETTER | PRESET) ;
