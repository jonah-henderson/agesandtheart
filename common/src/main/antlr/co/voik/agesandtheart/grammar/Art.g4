/*
 * The grammar of the Art (design §4.3.1).
 *
 * Its job is **attachment and grouping** — which words modify which subject, and which are joined.
 * Everything that makes the language interesting (precision, contradiction, division, tag satisfaction)
 * happens beneath it in the resolver, which already existed before this did.
 *
 * Three properties are load-bearing and easy to lose:
 *
 * 1. **There are no lexer rules, and that is the point.** The input is never text: it is a list of pages,
 *    each already looked up in the `Vocabulary` and stamped with its class. So the token types are simply
 *    *declared* below and supplied by a `TokenSource` of ours.
 *
 *    Terminals are therefore word CLASSES, never spellings. There is no `SKY_TERM : 'overcast' | ...` here,
 *    because vocabulary is datapack content and §8 derives a word per fluid, biome and block in the
 *    modpack — tens of thousands of them, arriving at runtime. So a new *word* still needs no grammar
 *    change.
 * 2. **The aspect list is IN the grammar, deliberately.** A section admits only terms belonging to the part
 *    of the world it aims at, so `flat sky` is not a sentence that gets charged for being wrong — it is not
 *    a sentence. The price is that a new aspect costs a rule here, which is honest: a new part of the world
 *    genuinely is a new thing to be able to talk about.
 *
 *    What this buys is **predictability**. A reader should be able to take a book left to right and know
 *    what each page did, and a word that could reach out of the clause it was written in defeats that. So
 *    a narrowing word belongs to its aspect and says nothing anywhere else.
 * 3. **No ambiguity.** §4.3 rejects parse ambiguity as a mechanism: it is a randomness no word can ever take
 *    away, and it is invisible, so it fails the promise that a flawed Age is diagnosable. Vagueness comes
 *    from word choice. Keep this grammar deterministic — if a construct ever admits two readings, that is a
 *    bug in the grammar rather than a feature of the language.
 */
grammar Art;

/*
 * Declared, not lexed. `ArtGrammar` supplies these from the vocabulary; see the note at the top.
 *
 * One subject and one term terminal per aspect, plus two that deliberately span several:
 *
 * - `EVOCATIVE` is at home anywhere, because an evocative word tilts rather than narrows and confining it
 *   would demote it to a restrictive one (§4.3.1).
 * - `MATERIAL_TERM` is a **block**, and being made of something is a property several parts of the world
 *   share — `Sea` is an open aspect whose value *is* a block, a terrain wears one through its `stone`
 *   parameter, and a surface is laid in one. So "a sea of ice", "land of blackstone" and "surface of
 *   blackstone" are all sentences, and which of the three a page means is decided by the section it sits
 *   in rather than by the word. That is not a word reaching out of
 *   its clause; it is one word usable in more than one place, which is the distinction property 2 rests on.
 */
tokens {
    AGE,
    EVOCATIVE,
    MATERIAL_TERM,
    TERRAIN_SUBJECT, TERRAIN_TERM,
    SEA_SUBJECT, SEA_TERM,
    CARVERS_SUBJECT, CARVERS_TERM,
    BIOMES_SUBJECT, BIOMES_TERM,
    SURFACE_SUBJECT, SURFACE_TERM,
    FEATURES_SUBJECT, FEATURES_TERM,
    SPAWNS_SUBJECT, SPAWNS_TERM,
    ATMOSPHERE_SUBJECT, ATMOSPHERE_TERM,
    SKY_SUBJECT, SKY_TERM,
    STRUCTURES_SUBJECT, STRUCTURES_TERM,
    CLIMATE_SUBJECT, CLIMATE_TERM,
    QUANTIFIER, AND, ONLY, EXCEPT, IN
}

/**
 * A book is **an Age, and then what is true of it**.
 *
 * The nucleus is mandatory, and that is the point: `beautiful` is not a book, `beautiful Age` is. One
 * required page costs a beginner almost nothing and buys three things — a sentence always has a head to
 * hang a reading on, every descriptor has something to describe rather than floating, and a stray term has
 * nowhere to quietly start a section of its own. Without it `landmass starless` silently became
 * "unconstrained land, and separately a starless sky", which is a re-homing nobody asked for and nobody
 * was told about.
 *
 * An empty book is still legal — the pen never refuses (design §2) — it simply says nothing.
 */
sentence : (nucleus section*)? EOF ;

/**
 * The Age itself: what the whole book is about, with the words that colour it and anything said of the
 * world at large before any part of it is named.
 *
 * This is where the beginner's book lives, and it is the commonest thing anyone writes: `beautiful Age`,
 * or `Age of blackstone`. Its modifiers are loose because nothing has been aimed at yet — a word goes
 * where it declares it goes.
 */
nucleus : descriptor* AGE looseModifier* ;

/**
 * A subject and the modifiers that follow it, one alternative per part of the world.
 *
 * **A section is opened by an aiming page, never by a word that fills something.** Players do not write
 * presets — presets are ours, an internal tool a word is mapped onto at our leisure — so the pages that
 * carve a book into parts are the *targets* a writer aims at: `landmass`, `climate`, `sky`.
 *
 * **Every section is aimed.** The unaimed ones the grammar used to carry are the [nucleus] now, which is
 * what makes a stray term a parse failure rather than a section of its own — a page that cannot join the
 * part of the world it was laid in has nowhere else to go, and saying so is the repair layer's business.
 */
section
    : descriptor* TERRAIN_SUBJECT    terrainModifier*     # TerrainSection
    | descriptor* SEA_SUBJECT        seaModifier*         # SeaSection
    | descriptor* CARVERS_SUBJECT    carversModifier*     # CarversSection
    | descriptor* BIOMES_SUBJECT     biomesModifier*      # BiomesSection
    | descriptor* SURFACE_SUBJECT    surfaceModifier*     # SurfaceSection
    | confinement? descriptor* FEATURES_SUBJECT featuresModifier*  # FeaturesSection
    | confinement? descriptor* SPAWNS_SUBJECT   spawnsModifier*    # SpawnsSection
    | confinement? descriptor* ATMOSPHERE_SUBJECT atmosphereModifier* # AtmosphereSection
    | descriptor* SKY_SUBJECT        skyModifier*         # SkySection
    | descriptor* STRUCTURES_SUBJECT structuresModifier*  # StructuresSection
    | descriptor* CLIMATE_SUBJECT    climateModifier*     # ClimateSection
    ;

/** Evocative words, which precede their subject and are scoped to it — gently (§4.3.1's tier rule). */
descriptor : EVOCATIVE ;

/*
 * Anything that steers the subject, one rule per aspect so a term can only sit under a subject it belongs
 * to. The three parts that are *made of* something also admit a material.
 *
 * `only` and `except` bind tighter than juxtaposition and looser than `and`, which is what makes the
 * reading predictable left to right without backtracking. Terms joined by `and` mean "keep both, and keep
 * them apart" (§3.2), in a flat list rather than nested pairs, because `and` is associative here — a
 * parameter holding three values means the same thing however it was written.
 */
terrainModifier    : (ONLY | EXCEPT)? terrainTerm    (AND terrainTerm)*    ;
seaModifier        : (ONLY | EXCEPT)? seaTerm        (AND seaTerm)*        ;
carversModifier    : (ONLY | EXCEPT)? carversTerm    (AND carversTerm)*    ;
biomesModifier     : (ONLY | EXCEPT)? biomesTerm     (AND biomesTerm)*     ;
surfaceModifier    : (ONLY | EXCEPT)? surfaceTerm    (AND surfaceTerm)*    ;
featuresModifier   : (ONLY | EXCEPT)? featuresTerm   (AND featuresTerm)*   ;
spawnsModifier     : (ONLY | EXCEPT)? spawnsTerm     (AND spawnsTerm)*     ;
atmosphereModifier : (ONLY | EXCEPT)? atmosphereTerm (AND atmosphereTerm)* ;
skyModifier        : (ONLY | EXCEPT)? skyTerm        (AND skyTerm)*        ;
structuresModifier : (ONLY | EXCEPT)? structuresTerm (AND structuresTerm)* ;
climateModifier    : (ONLY | EXCEPT)? climateTerm    (AND climateTerm)*    ;
looseModifier      : (ONLY | EXCEPT)? looseTerm      (AND looseTerm)*      ;

/*
 * One thing said about the section, optionally with how much of it there should be.
 *
 * The quantifier **precedes what it counts** — `teeming villages` — which is §4.5's third rung and the only
 * production that binds one page to one other page rather than joining peers. It is deliberately not a
 * class of its own in the world model: what it modifies is a *claim*, so the rung travels with the value
 * into the recipe (`minecraft:villages@teeming`).
 */
terrainTerm    : QUANTIFIER? ( TERRAIN_TERM    | MATERIAL_TERM ) ;
seaTerm        : QUANTIFIER? ( SEA_TERM        | MATERIAL_TERM ) ;
structuresTerm : QUANTIFIER? ( STRUCTURES_TERM | MATERIAL_TERM ) ;
surfaceTerm    : QUANTIFIER? ( SURFACE_TERM    | MATERIAL_TERM ) ;
featuresTerm   : QUANTIFIER? FEATURES_TERM ;
spawnsTerm     : QUANTIFIER? SPAWNS_TERM ;
atmosphereTerm : QUANTIFIER? ATMOSPHERE_TERM ;

/**
 * **Where a whole clause applies** — `in mushroom_fields, spawns only slime and teeming cows`.
 *
 * At the head rather than after a term, and that is the whole of what it means: a reader learns the ground
 * they are standing on *before* the claims made about it, and everything in the clause is governed by it.
 * A term-level form would put the scope after the claim it changes and leave `only` ambiguous about how
 * far it reaches, which is exactly the left-to-right predictability §4.3's third property is for.
 *
 * The two aspects that admit one are the two of §3.1's four that exist — vanilla resolves both through the
 * biome. It reuses the biome term page rather than minting a page per biome.
 */
confinement    : IN BIOMES_TERM ;
carversTerm    : QUANTIFIER? CARVERS_TERM ;
biomesTerm     : QUANTIFIER? BIOMES_TERM  ;
skyTerm        : QUANTIFIER? SKY_TERM     ;
climateTerm    : QUANTIFIER? CLIMATE_TERM ;

/** A term in a section that aims at nothing, and so may belong to any part of the world. */
looseTerm
    : QUANTIFIER? ( MATERIAL_TERM | TERRAIN_TERM | SEA_TERM | CARVERS_TERM | BIOMES_TERM
                  | SURFACE_TERM | FEATURES_TERM | SPAWNS_TERM | ATMOSPHERE_TERM | SKY_TERM
                  | STRUCTURES_TERM | CLIMATE_TERM )
    ;
