package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Flaw
import net.minecraft.core.registries.BuiltInRegistries
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Pool
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.SkyBodies
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.word.grammar.Phrase
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.age.word.grammar.Repair
import co.voik.agesandtheart.age.word.grammar.Sentence
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.resources.Identifier
import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble

/**
 * What a sentence turned into: the world it describes, what it cost to say, and where it argued with
 * itself. The words are kept alongside as provenance only (§4.6).
 */
data class Resolution(
    val composition: AgeComposition,
    val instability: Instability,
    /** Fine inks, summed over the sentence — flat for a vague word, by versatility for a precise one (§4.4). */
    val cost: Int,
    /**
     * The world this book started from (`the-world-model.md` §4) — provenance only, like [words].
     *
     * What the template supplied is already merged into [composition], which is what persists: an Age is
     * rebuilt from the answer rather than from the question, so retuning a template can never reach one
     * already written.
     */
    val template: AgeTemplate = AgeTemplate.ORDINARY,
    val words: List<Word>,
    /**
     * Pages the Art could not read. **Vagueness, never instability** (§4.3), and the only channel that
     * lands here: a page no sentence had room for is charged, so it is a [Flaw] and carries its own words.
     */
    val dropped: List<String> = emptyList(),
)

/**
 * Where one member of a part of the world stands after one word has spoken — how strongly it is claimed,
 * and whether the word leaves it in at all.
 *
 * A catalogue's [strength] is what decides which single preset is seated; a population's is the share of
 * the world that member keeps. The two scales are not comparable across aspects and never need to be:
 * every reader of this is ranking one aspect's members against each other.
 */
data class Standing(val strength: Double, val kept: Boolean)

/**
 * Words in, a world out. Every word scores every preset in the aspects it may fill; precise words
 * *narrow* the candidates, vague words *tilt* the draw between whatever survived, and the seed picks.
 * There is no per-kind code path and no geometry anywhere.
 *
 * Four rules the rest of this file implements:
 *
 * 1. **A word is confined to the aspects it is about** ([Word.aspects], §4.4) — unscoped, `stormy` pinned
 *    the *terrain* to caverns and threw `floating` away in silence.
 * 2. **Unsatisfiability comes from the tag data, never the antonym table** (§3.3). The table explains and
 *    prices tension; what breaks an Age is two words with no preset between them.
 * 3. **A spatial aspect divides rather than arbitrating** (§3.4), and *only* to absorb a real
 *    disagreement — so seeing two terrains in an Age means something.
 * 4. **Word order never decides anything** (§3.5). Where precision cannot separate two words the seed
 *    does, so `verdant arid` and `arid verdant` are the same sentence.
 *
 * A pure function of (vocabulary, words, seed): an Age is rebuilt from its recipe on every open.
 */
object Resolver {
    /**
     * How many ways one aspect may divide. A limit on legibility rather than machinery — region maps
     * handle any number. Words that do not fit are displaced and charged, never dropped.
     */
    private const val MOST_TERRITORIES = 3

    // A floor under every candidate, so a lean tilts the draw rather than deciding it.
    private const val BASE_WEIGHT = 0.35

    // No candidate's chance reaches zero, or a word pushing hard against something could eliminate it.
    private const val FAINTEST_CHANCE = 0.02

    // How well liked a preset must be, against the best already seated, to join for harmony's sake.
    private const val COMPANY_SHARE_OF_BEST = 0.75

    // A preset that can do everything the sentence asked keeps its full claim.
    private const val FULLY_CAPABLE = 1.0

    // What one that can do none of it keeps. A *factor* rather than an added bonus, which was measured as
    // the wrong shape: a flat bonus only has to beat one rival at a time and there are three. Small but
    // never zero, since a word that merely sets a parameter must not eliminate a preset (§3.2).
    private const val INCAPABLE_FACTOR = 0.04

    /** Precedence between words claiming one part, firmest first — see [Word.PRECEDENCE]. */
    private val FIRMEST_FIRST: Comparator<Constraint> = compareByDescending(Word.PRECEDENCE) { it.word }

    // How much more of the world naming a member asks for, on top of the ordinary share it already had.
    private const val A_MENTION_IS_WORTH = 1.0

    /** What naming a member the world would not otherwise have adds: nothing, the naming being the ask. */
    private const val NOTHING_MORE = 0.0

    // As much of the world as any one member of a population may be talked into taking, so that a
    // sentence full of words agreeing about one biome cannot quietly make an Age of nothing else. Room
    // for the loudest thing a writer can say about one member and no more: `teeming <member>`, which is
    // a mention at the top rung.
    private const val MOST_OF_A_WORLD = 8.0

    // Where a population lets a member be pushed all the way down, the claim that says so.
    private const val NONE_OF_IT = 0.0

    // Arbitrary large odds, only ever needed to decorrelate one draw from another.
    private const val ASPECT_STRIDE = 0x1F3B_5D79L
    private const val TERRITORY_STRIDE = 0x4C9E_1A2BL
    private const val COMPANY_SALT = 0x600D_C0A1L
    private const val MATERIAL_SALT = 0x3A7E_41A1L
    private const val STRICTNESS_SALT = 0x5721_C7L
    private const val WORD_MIXER = -0x61c8_8646_80b5_83ebL

    /**
     * [this] with a broad word's pool settled for this Age — the one place a draw becomes an answer.
     *
     * **Substituted once rather than threaded through every reader.** Ten places consume a word's chosen
     * parameters and all of them want what it chose *here*; three more ask what it could ever choose, and
     * those must not move with a draw. Copying the constraint's word with its drawn parameters in `sets`
     * answers the first ten unchanged, and **keeping `pool`** answers the other three: `canSet` is
     * `sets + pool`, and a drawn subset unioned with the whole pool is the whole pool either way.
     *
     * The sentence itself is untouched, which matters — [Readout] renders what a writer wrote, and what a
     * word might have done is part of what they wrote.
     *
     * Alternatives inside a value settle here too, for the same reason: by the time the resolver compares
     * one word's choice against another's, every value is a single concrete thing and none of the ten
     * readers has to know that `embers|ash` was ever a possibility.
     */
    private fun Constraint.drawnAt(draw: Long): Constraint =
        if (!word.varies) this else copy(
            word = word.copy(
                sets = word.setsDrawnAt(draw),
                requests = word.requests.copy(sets = word.requestsDrawnAt(draw)),
            ),
        )

    /** [drawnAt], and then every material asked for by tag settled to a block — see [materialsDrawn]. */
    private fun Constraint.materialisedAt(vocabulary: Vocabulary, draw: Long): Constraint {
        val drawn = drawnAt(draw)
        val built = drawn.word.copy(
            sets = materialsDrawn(vocabulary, drawn.word, drawn.word.sets, draw),
            requests = drawn.word.requests.copy(
                sets = materialsDrawn(vocabulary, drawn.word, drawn.word.requests.sets, draw),
            ),
        )
        return if (built == drawn.word) drawn else drawn.copy(word = built)
    }

    /**
     * [parameters] with every material asked for by tag — `"stone": "#frozen"` — settled to one block for
     * this Age ([MaterialTable.drawFor]), after the alternatives have been, so `#frozen|#pale` first picks
     * which tag. A query nothing may answer asks for nothing, the same as an unreadable value, and the
     * checks keep one from shipping.
     */
    private fun materialsDrawn(
        vocabulary: Vocabulary,
        word: Word,
        parameters: Map<String, String>,
        draw: Long,
    ): Map<String, String> {
        if (parameters.none { (parameter, value) -> MaterialTable.asksByTag(parameter, value) }) return parameters
        return parameters.mapNotNull { (parameter, value) ->
            if (!MaterialTable.asksByTag(parameter, value)) return@mapNotNull parameter to value
            val seed = draw xor word.id.hashCode().toLong() xor parameter.hashCode().toLong() xor MATERIAL_SALT
            vocabulary.materials.drawFor(value, seed)?.let { parameter to it }
        }.toMap()
    }

    /**
     * The sentence with every word's surviving **requests** folded into what it demands — the whole of how
     * an offer differs from a claim (`the-world-model.md` §5).
     *
     * A request is laid *under* the sentence: it applies to a parameter nothing demanded, and vanishes without
     * a word wherever something did. `a blue sun. an inferno Age.` is the case — the writer's blue is a
     * demand, so the inferno's offered red is simply not there to argue with, and neither of them is
     * charged for a contradiction nobody made.
     *
     * **Folded rather than threaded through**, which is what keeps this to one function: by the time
     * anything downstream looks, a surviving request is an ordinary claim on an ordinary parameter, so
     * fracturing, contention, siting and charging all work on it unchanged and none of them had to learn a
     * second kind of claim.
     *
     * **At most one request survives per parameter**, taken in the order two demands would be taken —
     * precedence first, then the seed. Two offers on one parameter are not a quarrel the writer can be charged for, and
     * collapsing them here is what stops the fold from manufacturing one: `scorching inferno` both offer a
     * sun a colour, and the Age gets one of them rather than an instability.
     *
     * Written **qualified** — `sun.colour` — because a word may reach several aspects and be outbid in one
     * of them: a flat key would carry the survivor into the aspect where it lost.
     */
    private fun offered(
        vocabulary: Vocabulary,
        said: List<Constraint>,
        draw: Long,
    ): List<Constraint> {
        val requesting = said.filter { !it.word.requests.isEmpty }
        if (requesting.isEmpty()) return said
        val kept = mutableMapOf<Constraint, MutableMap<String, String>>()
        for (aspect in Aspect.entries) {
            val here = said.filter { aspect in reachOf(it) }
            val demanded = here.flatMap { it.word.setsIn(aspect).keys }.toSet()
            val asked = here.filter { it in requesting }
            for (parameter in asked.flatMap { it.word.requestsIn(aspect).keys }.distinct()) {
                if (parameter in demanded) continue
                val winner = asked.filter { parameter in it.word.requestsIn(aspect) }
                    .sortedWith(
                        FIRMEST_FIRST
                            .thenBy { tieBreak(draw, aspect, it.word) },
                    )
                    .first()
                kept.getOrPut(winner) { mutableMapOf() }["${aspect.page}$QUALIFIED$parameter"] =
                    winner.word.requestsIn(aspect).getValue(parameter)
            }
        }
        return said.map { constraint ->
            val granted = kept[constraint] ?: return@map constraint
            constraint.copy(word = constraint.word.copy(sets = constraint.word.sets + granted))
        }
    }

    /** How a parameter names the aspect it is meant for — `sun.colour`. Spelled on [Word] and matched here. */
    private const val QUALIFIED = "."

    /**
     * The world [sentence] describes at [seed]. Aspects resolve independently and in ordinal order, so an
     * aspect's filling cannot depend on what another happened to draw.
     */
    fun resolve(vocabulary: Vocabulary, sentence: Sentence, seed: Long): Resolution {
        // §4.6: the unconstrained should still vary with what was written, or two different sentences at
        // one seed draw identical filler wherever neither constrains anything.
        val draw = seed xor saltOf(sentence.words)
        // **A size a minting clause spent is spent**, and never reaches the aspect it was written in.
        // `colossal gold_block obelisks` says how big the obelisks are; read as a word about the features
        // aspect it also enlarged every tree in the Age, which is the same surprise as `lava springs`
        // paving the world — and that one was already thought worth preventing.
        //
        // By identity rather than by equality, and taken before the draw copies each constraint: two
        // `colossal` pages in one book are equal, and only the one inside the minting is spent.
        //
        // **Only where the clause really mints**, which is `materialsOf` and not merely a subject that
        // could: `springs`, `lakes` and `deposits` all declare `mints` with no `unstated`, so `tiny springs`
        // mints nothing — and charging it for the size anyway took the word away and gave nothing back.
        //
        // **And only the size**, never the whole claim. `colossal` also restricts the landmass to
        // `monumental`; `rich` admits two ores and biases three tags. Dropping the constraint spent all of
        // that to pay for one parameter.
        //
        // **A height is spent the same way**: `shallow veins` puts the veins near the surface and leaves
        // every other ore where the Age put it.
        val minting = sentence.phrases.filter { materialsOf(it, draw) != null }.flatMap { it.modifiers }
        fun isSpentIn(constraint: Constraint) = minting.any { it === constraint }
        val kept = sentence.constraints.map { constraint ->
            if (!isSpentIn(constraint)) constraint
            else constraint.copy(word = constraint.word.withoutItsSize().withoutItsHeight())
        }.map { rolledBare(it, draw) }
        val said = offered(vocabulary, kept.map { it.materialisedAt(vocabulary, draw) }, draw)
        val flaws = mutableListOf<Flaw>()
        flaws += rehomings(vocabulary, sentence)
        flaws += impossibilities(vocabulary, sentence)
        val filled = Aspect.entries.associateWith { aspect -> fill(vocabulary, aspect, said, draw, flaws) }
        flaws += tensions(vocabulary, said, filled.mapValues { (_, filling) -> filling.map { it.preset } })

        val resolved = describedMembers(
            weighed(vocabulary, steer(vocabulary, cast(compose(filled), said), said, draw, flaws), said, draw, flaws),
            said,
        )
        // **The template underneath, what the sentence said on top.** Which aspects the sentence spoke to
        // is what decides where the seam falls, so it is asked of the claims rather than of the answer —
        // an aspect a word reached and left at its default still belongs to the writer.
        val template = templateOf(said)
        val spokenTo = said.flatMap { reachOf(it) }.toSet()
        val composition = mintedFeatures(resolved, sentence, draw).laidOver(template.world(), spokenTo)
        flaws += mintingsThatCannotHold(vocabulary, sentence, draw)
        flaws += materialsDisplacedInMintings(vocabulary, sentence, draw)
        flaws += tidesWithNoMoon(vocabulary, said, composition)
        // **Last**, so it can see everything the mechanisms above already charged and never price one
        // disagreement twice. Steering adds flaws of its own, so this cannot be hoisted.
        flaws += oppositions(vocabulary, said, flaws.toList())

        return Resolution(
            composition = composition,
            instability = Instability(flaws.toList()),
            // Structure is priced too: every page a writer lays costs ink, and a page that made no
            // claim still came out of the pot. A latent page came out of nobody's pot.
            cost = sentence.written.sumOf { it.word.price } + sentence.structural.sumOf { it.cost },
            words = sentence.words,
            template = template,
            dropped = sentence.unreadable,
        )
    }

    /**
     * **A pattern asked to be made of something it cannot hold** — `gold_block springs`, where the word
     * spells a spring and the material named beside it is a solid.
     *
     * A spring runs with a fluid, so the substance is dropped and the writer gets ordinary water. That is
     * the right thing for generation to do — a spring rebuilt around `Fluids.EMPTY` places nothing at all,
     * which is worse — but it left a page paid for and nothing said about it (Jonah, 2026-08-25, walked).
     *
     * **Charged rather than refused**, because the sentence is one a writer can mean: asking a spring to
     * run with gold is incoherent in the way §2 says instability is *for*, not malformed in the way the
     * grammar rejects.
     *
     * Read off the clauses like [mintedFeatures], and for the same reason — the pairing of a pattern with
     * its substance is a fact about a clause, and the flat claims have thrown it away by here.
     */
    private fun mintingsThatCannotHold(vocabulary: Vocabulary, sentence: Sentence, draw: Long): List<Flaw> =
        sentence.phrases.mapNotNull { phrase ->
            val minting = phrase.subject?.takeIf { it.word.mintsSomethingThatFlows } ?: return@mapNotNull null
            // The material that won the clause, drawn exactly as the minting draws it.
            val substance = materialsOf(phrase, draw)?.leading ?: return@mapNotNull null
            if (flows(substance.word.material)) return@mapNotNull null
            // The material first: it is the word that lost, and `describe` names the first as displaced
            // and the second as what displaced it.
            flaw(vocabulary, Register.DISPLACED, listOf(substance, minting), Aspect.FEATURES, emptyList(), substance.word.firmness)
        }

    /**
     * A **pull asked of a moon in an Age with no moon** — `tidal moon` beside `moonless moon`. The tide cannot
     * happen and the book asked for one, which is a mild contradiction (design §7.1.2), charged to the word
     * that pulled and naming whatever took the moons away.
     */
    private fun tidesWithNoMoon(
        vocabulary: Vocabulary,
        said: List<Constraint>,
        composition: AgeComposition,
    ): List<Flaw> {
        if (!composition.optionsFor(Aspect.MOON).isTrue(SkyBodies.ABSENT)) return emptyList()
        fun setsOnTheMoon(constraint: Constraint, parameter: Parameter) =
            Aspect.MOON in reachOf(constraint) && parameter.name in constraint.word.setsIn(Aspect.MOON)
        val removing = said.filter { setsOnTheMoon(it, SkyBodies.ABSENT) }
        return said.filter { setsOnTheMoon(it, SkyBodies.PULL) }.map { pulling ->
            flaw(vocabulary, Register.TENSION, listOf(pulling) + removing, Aspect.MOON, emptyList(), pulling.word.firmness)
        }
    }

    /**
     * Whether a block a sentence named has a fluid in it, which is the whole of what a spring asks of its
     * substance. A name this pack does not have does not flow, and is somebody else's flaw to report.
     */
    private fun flows(block: String?): Boolean = block?.let(Identifier::tryParse)
        ?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        ?.defaultBlockState()?.fluidState?.isEmpty == false

    /**
     * The world this book starts from — **the first template named, or the ordinary one**.
     *
     * First rather than drawn or contended, and it is one of the two places written order decides anything
     * (§3.5) — the other being a ramp's colours, in [ordered]. Two templates in a book is a rare thing to be
     * holding and a plain thing to say back, where a draw would be neither.
     */
    private fun templateOf(said: List<Constraint>): AgeTemplate =
        said.firstNotNullOfOrNull { it.word.template?.let(AgeTemplate::named) } ?: AgeTemplate.ORDINARY

    /**
     * What [phrase] would mint out of, or null where it mints nothing.
     *
     * **One answer, asked in two places**, because they disagreed and that was the bug: `resolve` charged a
     * clause for spending its size wherever the *subject* could mint, where this decides whether anything
     * is actually minted. A pattern with no material and no [Word.unstated] — `springs`, `lakes`, `deposits`
     * — mints nothing, so `tiny springs` lost `tiny` and gained no spring.
     *
     * Drawn, like every other reader of a word's claims: a material carrying a pool chooses here too.
     *
     * **A pattern named alone is still made of something.** `obelisks` used to mint nothing at all and put
     * nothing in the ground, which reads as the word not working; [Word.unstated] is what the pattern is
     * made of when nobody says, and a tag there is a small pool the seed draws from where a bare id is one
     * answer.
     *
     * **Several materials are read as a landmass reads them** (Jonah, 2026-09-22): joined with `and` they
     * mingle into one thing, `mud and sand pits`; laid side by side they contend, the most precise wins,
     * the seed breaks a tie, and the rest are [Materials.displaced]. Two kinds of pit apart are two clauses,
     * `mud pits sand pits`.
     */
    private fun materialsOf(phrase: Phrase, draw: Long): Materials? {
        val subject = phrase.subject ?: return null
        if (subject.word.mints == null) return null
        val named = phrase.modifiers.map { it.drawnAt(draw) }.filter { it.word.material != null }
        if (named.isEmpty()) {
            return subject.word.unstated?.let { Materials(listOf(it), leading = null, displaced = emptyList()) }
        }
        val leading = named.sortedWith(
            FIRMEST_FIRST.thenBy { tieBreak(draw, Aspect.FEATURES, it.word) },
        ).first()
        fun minglesWithTheLeader(said: Constraint): Boolean {
            val isTheLeader = said === leading
            val asksForTheSameMaterial = said.word.material == leading.word.material
            return isTheLeader || wereJoined(said, leading) || asksForTheSameMaterial
        }
        val (mingled, displaced) = named.partition(::minglesWithTheLeader)
        return Materials(mingled.mapNotNull { it.word.material }.distinct(), leading, displaced)
    }

    /**
     * What one minting clause is made of: [kept] in written order, the [leading] material the rest were
     * measured against (null where the pattern's own unstated substance stands in), and what lost to it.
     */
    private class Materials(val kept: List<String>, val leading: Constraint?, val displaced: List<Constraint>)

    /**
     * A [Register.DISPLACED] for each material a minting clause laid beside the winner without joining it —
     * `mud sand pits` is one pit asked to be two things, where `mud and sand pits` is one pit of both.
     */
    private fun materialsDisplacedInMintings(vocabulary: Vocabulary, sentence: Sentence, draw: Long): List<Flaw> =
        sentence.phrases.flatMap { phrase ->
            val materials = materialsOf(phrase, draw) ?: return@flatMap emptyList()
            val leading = materials.leading ?: return@flatMap emptyList()
            materials.displaced.map { loser ->
                flaw(vocabulary, Register.DISPLACED, listOf(loser, leading), Aspect.FEATURES, emptyList(), loser.word.firmness)
            }
        }

    /**
     * [composition] with every feature the sentence **minted** added to what the Age places — `ink springs`,
     * `gold block deposits` (world model §2).
     *
     * **Read off the clauses rather than off the flat claims**, and that is the one place in the resolver
     * that is: minting is a fact about a clause, being a pattern and a substance said together, and the
     * flattening that every other rule works from has thrown the pairing away by the time it gets here.
     *
     * The claim names the pattern and carries the substance, so nothing downstream has to know there were
     * ever two pages — `Features` looks the pattern up and swaps what it is made of.
     */
    private fun mintedFeatures(composition: AgeComposition, sentence: Sentence, draw: Long): AgeComposition {
        val minted = sentence.phrases.mapNotNull { phrase ->
            val subject = phrase.subject ?: return@mapNotNull null
            val pattern = subject.word.mints ?: return@mapNotNull null
            val materials = materialsOf(phrase, draw) ?: return@mapNotNull null
            // **Every quantifier in the clause counts the thing it mints.** One qualifies the page after
            // it, so `teeming gold_ore veins` hung its amount on the material and the veins never saw it.
            val amount = phrase.modifiers.fold(subject.density) { standing, said -> standing * said.density }
            Claim(
                pattern,
                subject.polarity,
                Rung.legible(amount),
                subject.confinedTo,
                madeOf = materials.kept.joinToString(Claim.MINGLED.toString()),
                size = phrase.modifiers.firstNotNullOfOrNull { it.word.sizeAsked },
                height = phrase.modifiers.firstNotNullOfOrNull { it.word.heightAsked },
            )
        }
        if (minted.isEmpty()) return composition
        val already = composition.optionsFor(Aspect.FEATURES, 0).allSpelled(Features.PLACES.name)
        return composition.withOptions(
            Aspect.FEATURES,
            Features.PLACES.name,
            (already + minted.map { it.spelled() }).distinct().toList(),
        )
    }

    /**
     * A population's roll grown to what a **word** asked for, where the book described nobody.
     *
     * The one way a word brings a body into being, and the reason `cast` is a parameter at all: a writer mints
     * a sun by describing one, and there is no clause in `a scorching Age` to mint anything. An inferno's
     * sky wants more than one thing burning in it and no page said so.
     *
     * **Only into silence, and that is the same rule the template already lives by** — `AgeComposition
     * .laidOver` grows a cast from underneath only where the book minted nothing, because §4's rule is
     * that describing any member clears what was there. A word that could add a sun to the writer's own
     * would be overruling them; one that fills an empty sky is answering a question nobody asked.
     *
     * **Before the steering, deliberately.** `withOptions` writes a parameter to every member there is, so a
     * roll grown afterwards would leave the second and third suns blank — and they would then be filled
     * from the template, which is how an inferno ends up with one red sun and two ordinary ones.
     *
     * The largest asked wins rather than the sum: `scorching inferno` is one hot sky, not five suns.
     */
    private fun cast(composition: AgeComposition, said: List<Constraint>): AgeComposition =
        Aspect.entries.filter { it.holds == Holds.POPULATION }.fold(composition) { held, aspect ->
            val theBookMintedOne = said.any { it.describes != null && aspect in it.aimedAt }
            if (theBookMintedOne) return@fold held
            val asked = said.mapNotNull { it.word.setsIn(aspect)[Parameter.CAST]?.toIntOrNull() }.maxOrNull()
            if (asked == null) held else held.withCastOf(aspect, asked)
        }

    private fun describedMembers(composition: AgeComposition, said: List<Constraint>): AgeComposition =
        said.mapNotNull { claim -> claim.describes?.let { it to claim } }
            .flatMap { (member, claim) -> claim.aimedAt.map { aspect -> aspect to member } }
            .groupBy({ it.first }, { it.second })
            .entries.fold(composition) { held, (aspect, members) ->
                held.withCastOf(aspect, members.max() + 1)
            }

    /**
     * Pages the writer laid where they could not be read, which [Repair] moved somewhere they could
     * (§4.3.1). The word still means what it means — what is charged is the aiming.
     *
     * Found here rather than inside [fill] because the mistake is about *the book* rather than about any
     * one part of the world, and because the aspect it names is where the page ended up.
     */
    private fun rehomings(vocabulary: Vocabulary, sentence: Sentence): List<Flaw> =
        sentence.written.filter { it.rehomed }.map { said ->
            val landedIn = reachOf(said).firstOrNull()
            flaw(vocabulary, Register.REHOMED, listOf(said), landedIn, tags = emptyList(), firmness = said.word.firmness)
        }

    /**
     * A word laid bare, with the parts of the world its [Word.unaimed] roll missed in this Age taken off it
     * — out of its effects, and out of its scope where it narrows. Rolled off [draw], the word and the part,
     * so the same book at the same seed always misses the same parts.
     */
    private fun rolledBare(constraint: Constraint, draw: Long): Constraint {
        val word = constraint.word
        if (!constraint.laidBare || word.unaimed.isEmpty()) return constraint
        val missed = word.unaimed.filter { (aspect, chance) ->
            val key = draw xor word.id.hashCode().toLong() xor (aspect.ordinal.toLong() shl ROLL_SHIFT)
            XoroshiroRandomSource(key).nextDouble() >= chance
        }.keys
        if (missed.isEmpty()) return constraint
        return constraint.copy(word = word.withoutAspects(missed), aimedAt = constraint.aimedAt - missed)
    }

    /**
     * Pages there was nowhere for in any sentence at all. The dearest register and the only one that costs
     * a page outright — repair fits a page in wherever it can, so reaching this means nowhere would do.
     *
     * Charged at the word's own precision where it carried one, and flat where it did not: a structural
     * page has no precision to scale by.
     */
    private fun impossibilities(vocabulary: Vocabulary, sentence: Sentence): List<Flaw> =
        sentence.impossible.map { page ->
            val firmness = vocabulary.word(page)?.firmness
            Flaw(
                Register.IMPOSSIBLE,
                listOf(page),
                aspect = null,
                tags = emptyList(),
                Register.IMPOSSIBLE.charge(firmness, vocabulary.earnedBy(Register.IMPOSSIBLE)),
            )
        }

    /**
     * Which aspects a constraint speaks to — the grammar's answer, not a search (§4.3.1): where its clause
     * aimed, or the word's own reach where nothing did.
     */
    private fun reachOf(constraint: Constraint): List<Aspect> =
        constraint.aimedAt.ifEmpty { constraint.word.aspects }.sortedBy { it.ordinal }

    /**
     * What fills one aspect: one preset, or several where the sentence left it no way to be one thing.
     *
     * Three steps. Ask each narrowing word which presets it would keep; gather those into **territories**,
     * groups of words satisfiable together; then draw one preset per territory, tilted by every lean.
     */
    private fun fill(
        vocabulary: Vocabulary,
        aspect: Aspect,
        sentence: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): List<Filling> {
        // **Only a preset aspect is drawn between.** A population has everything already and is weighed by
        // the parameter pass; a set of parameters has nothing to choose at all. Seating one of either here would
        // invent an answer neither kind has.
        if (aspect.holds != Holds.CATALOGUE) return emptyList()

        val speaking = sentence.filter { aspect in reachOf(it) }
        // **What the sentence put into the pool**, which is the one step that can widen it. Per sentence
        // rather than per corpus: a member one word admits is in *this* Age's draw and nobody else's.
        val pool = vocabulary.availableToBroadWordsIn(aspect) +
            speaking.flatMap { it.word.admitsIn(aspect) }.distinct().mapNotNull(aspect::presetFor)
        // Most precise first; where precision ties the seed decides, never word order. A word that only
        // sets a parameter narrows nothing, having no opinion about *which* preset fills the aspect.
        val narrowing = speaking.filter { it.word.narrows && it.word.constrainsPresetsIn(aspect) }
            .sortedWith(FIRMEST_FIRST.thenBy { tieBreak(draw, aspect, it.word) })

        val territories = mutableListOf<Territory>()
        // **A word that also steers this aspect settles last, and never fractures it** (Jonah,
        // 2026-09-01). A reusable word carries several senses and only has to land one of them: where
        // `colossal archipelagic landmass` cannot have both the monumental landform its query asks for and the
        // islands the writer named, the answer is the size it sets and not two territories and a charge
        // for a contradiction nobody wrote. Settling after the words that *only* choose is what makes that
        // independent of the order they were laid in.
        val (steering, choosing) = narrowing.partition { steersInstead(vocabulary, aspect, it) }
        for (said in choosing + steering) {
            val carriers = vocabulary.carriersOf(said.word, aspect)
            if (carriers.isEmpty()) {
                // Unless the word is here to turn a parameter rather than choose a preset: a word may narrow in
                // one aspect and merely steer in another, and charging that as unbacked told a writer their
                // perfectly good sentence had failed.
                if (steersInstead(vocabulary, aspect, said)) continue
                // Word against world: nothing in the aspect can be this, so no arrangement of the others is
                // to blame. A content bug per §3.3, reported rather than dropped.
                flaws += flaw(vocabulary, Register.UNBACKED, listOf(said), aspect, emptyList(), said.word.firmness)
                continue
            }
            val home = territories.indexOfFirst { it.candidates.any { candidate -> candidate in carriers } }
            if (home < 0) {
                // **It still chooses where nothing else did**, which is the half a blanket rule would lose:
                // `colossal landmass` with no landform named is a monumental one, and only a word already
                // holding the ground can make this one give its choosing up.
                if (territories.isNotEmpty() && steersInstead(vocabulary, aspect, said)) continue
                territories += Territory(listOf(said), carriers)
            } else {
                territories[home] += Territory(listOf(said), carriers)
            }
        }

        // **A preset that can only be the whole aspect takes it alone**, and whatever else was asked for is
        // displaced by it: vanilla's rock beside a landform of ours keeps the rock.
        fun takesTheWhole(territory: Territory) = territory.candidates.all { it.takesTheWholeAspect }
        val whole = territories.firstOrNull(::takesTheWhole)
        val ordered = if (whole == null) territories else listOf(whole) + (territories - whole)
        // A spatial aspect can honour several answers by giving each its own ground; a singular one has
        // nowhere to put a second, which is where the harsher register earns its place (§3.4).
        val room = if (aspect.spatial && whole == null) MOST_TERRITORIES else 1
        val kept = ordered.take(room)
        chargeForContention(vocabulary, aspect, kept, ordered.drop(room), flaws)

        val chosen = mutableListOf<Taggable>()
        for ((index, territory) in kept.withIndex()) {
            chosen += pick(vocabulary, territory.candidates, speaking, draw, aspect, seat = index)
        }
        if (chosen.isEmpty()) {
            // **An aspect with nothing to choose between draws nothing.** Its answer is where its parameters
            // were left, which the parameter pass writes; there is no seat here to fill and no company to
            // keep, so this returns before either.
            if (pool.isEmpty()) return emptyList()
            chosen += pick(vocabulary, pool, speaking, draw, aspect, seat = 0)
        }

        chosen += company(vocabulary, aspect, kept, speaking, chosen, room, draw)
        return sharedOut(vocabulary, aspect, chosen, speaking)
    }

    /**
     * Whether this word has a *steering* sense here — something it sets that this aspect turns.
     *
     * The question behind the ruling that a word with a sense that works should use it rather than be
     * charged for one that does not: a reusable word is worth more than a precise one, and a writer who
     * laid `colossal` beside `islands` meant the islands to be large rather than to be somewhere else.
     */
    private fun steersInstead(vocabulary: Vocabulary, aspect: Aspect, said: Constraint): Boolean =
        said.word.canSet.keys.any { vocabulary.turnsAParameter(aspect, it) }

    /**
     * What the sentence is charged for asking one aspect to be several things it cannot reconcile. The
     * extra presets [company] adds cost nothing: charging generosity would make vagueness dangerous.
     */
    private fun chargeForContention(
        vocabulary: Vocabulary,
        aspect: Aspect,
        kept: List<Territory>,
        lost: List<Territory>,
        flaws: MutableList<Flaw>,
    ) {
        val leading = kept.firstOrNull()?.words?.firstOrNull() ?: return
        flaws += fracturesAgainst(vocabulary, aspect, leading, kept.drop(1).map { it.words.first() })
        for (said in lost.flatMap { it.words }) {
            flaws += flaw(
                vocabulary,
                Register.DISPLACED,
                listOf(said, leading),
                aspect,
                opposedTags(vocabulary, said, leading),
                said.word.firmness,
            )
        }
    }

    /**
     * Whether a writer joined these two with `and`. Both being ungrouped is **not** a join: if standing
     * side by side already meant "and", then "and" would mean nothing.
     */
    private fun wereJoined(one: Constraint, other: Constraint): Boolean =
        one.group != null && one.group == other.group

    /**
     * A [Register.FRACTURE] for each of [contenders] dividing [aspect] against [leading]. A division the
     * writer asked for costs nothing: `and` means "keep both, and keep them apart".
     */
    private fun fracturesAgainst(
        vocabulary: Vocabulary,
        aspect: Aspect,
        leading: Constraint,
        contenders: List<Constraint>,
    ): List<Flaw> = contenders.filterNot { wereJoined(it, leading) }.map { contender ->
        flaw(
            vocabulary,
            Register.FRACTURE,
            listOf(contender, leading),
            aspect,
            opposedTags(vocabulary, contender, leading),
            maxOf(contender.word.firmness, leading.word.firmness),
        )
    }

    /**
     * The presets an aspect takes on because the sentence liked several of them, rather than because it
     * contradicted itself — the harmonious division, and free (design §3.4).
     *
     * Four rules keep it from turning every Age into a patchwork: **something must have chosen here at
     * all**; an exact word forbids it (§4.4 pins one value); company must be nearly as well liked as what
     * is seated; and each aspect has its own [Aspect.appetiteForCompany].
     *
     * **The first is what an offer must not be able to buy.** Harmony is the sentence liking several
     * answers, and an offer is not the sentence liking anything — nobody said a word. Where an offered
     * query leant two seas to comparable strength the appetite happily divided them, so an inferno that
     * asked for fire or nothing sometimes got a lake of lava beside open air: a seam invented out of
     * silence, and free, because a harmonious division is never charged. A tilt may choose between
     * answers; it may not multiply them.
     */
    private fun company(
        vocabulary: Vocabulary,
        aspect: Aspect,
        territories: List<Territory>,
        speaking: List<Constraint>,
        seated: List<Taggable>,
        room: Int,
        draw: Long,
    ): List<Taggable> {
        if (!aspect.spatial || seated.size >= room) return emptyList()
        // Nothing narrowed this aspect, so nothing *chose* here and there is no harmony to find.
        if (territories.isEmpty()) return emptyList()
        // A word choosing *the preset* forbids company; one that bars or merely sets a parameter does not,
        // or naming a material would quietly suppress harmony everywhere.
        val pinned = speaking.any { it.word.choiceIn(aspect) != null }
        if (pinned) return emptyList()

        // Whatever the narrowing words left — company can only be something the sentence would have
        // accepted anyway, and where none spoke the guard above has already returned.
        val eligible = territories.flatMap { it.candidates }
        val bar = COMPANY_SHARE_OF_BEST * seated.maxOf { strengthOf(vocabulary, it, speaking, aspect) }
        val welcome = eligible.filter { it !in seated && strengthOf(vocabulary, it, speaking, aspect) >= bar }
        if (welcome.isEmpty()) return emptyList()

        val random = XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor COMPANY_SALT)
        val joining = mutableListOf<Taggable>()
        var appetite = aspect.appetiteForCompany
        while (seated.size + joining.size < room && random.nextDouble() < appetite) {
            val remaining = welcome.filter { it !in joining }
            if (remaining.isEmpty()) break
            joining += pick(vocabulary, remaining, speaking, draw, aspect, seat = seated.size + joining.size)
            // Each further guest is less likely than the last, so three-way harmony stays a rarity.
            appetite *= appetite
        }
        return joining
    }

    /**
     * How much ground each chosen preset covers: its claim on the aspect as a fraction of the strongest
     * claim there (§3.4), so the widest territory is [Share.EVEN] and the others measure against it.
     */
    private fun sharedOut(
        vocabulary: Vocabulary,
        aspect: Aspect,
        chosen: List<Taggable>,
        speaking: List<Constraint>,
    ): List<Filling> {
        val claims = chosen.map { claimOn(vocabulary, it, speaking, aspect) }
        val strongest = claims.max()
        // The sentence said nothing about this aspect, so nothing justifies favouring one answer.
        if (strongest <= FAINTEST_CHANCE) return chosen.map { Filling(it, Share.EVEN) }
        val shares = Share.findable(claims.map { Rung.legible(it / strongest) })
        // Widest first, so a recipe reads as the sentence would be spoken, and so the terrain whose sea
        // prevails is the one named first.
        return chosen.mapIndexed { index, preset -> Filling(preset, shares[index]) }
            .sortedByDescending { it.share }
    }

    /**
     * How hard the sentence claims this preset — the tag weights alone.
     *
     * Kept apart from [strengthOf], which needs a floor and a readiness term because *something* must be
     * picked. A floor here would compress every ratio towards one and snap every Age back to an even
     * division, so a share is the association strength and nothing else.
     */
    private fun claimOn(
        vocabulary: Vocabulary,
        preset: Taggable,
        speaking: List<Constraint>,
        aspect: Aspect,
    ): Double {
        val tags = vocabulary.tagsOf(preset)
        val claimed = speaking.filter { it.word.narrows }
            .maxOfOrNull { it.word.claimOn(preset, tags) } ?: 0.0
        // **Every word leans**, which is the whole of the last step: a lean is not a filter, so nothing
        // about it depends on whether the word that made it also narrowed.
        val leaned = speaking.sumOf { it.word.biasOn(preset, tags) }
        return (claimed + leaned).coerceAtLeast(0.0)
    }

    /**
     * How strong a claim the sentence makes on one preset — the number that decides which preset is drawn.
     *
     * Three terms: a **base** scaled by readiness, so an unasked-for draw prefers the ordinary (§3.3); the
     * strongest **pull** of any narrowing word; and the summed **leans** of every word, which may be
     * negative, so "beautiful" pushes lava away as surely as it pulls flowers in.
     */
    private fun strengthOf(
        vocabulary: Vocabulary,
        preset: Taggable,
        speaking: List<Constraint>,
        aspect: Aspect,
    ): Double {
        val tags = vocabulary.tagsOf(preset)
        val claimed = speaking.filter { it.word.narrows }
            .maxOfOrNull { it.word.claimOn(preset, tags) } ?: 0.0
        val leaned = speaking.sumOf { it.word.biasOn(preset, tags) }
        val wanted = BASE_WEIGHT * vocabulary.readinessOf(preset) + claimed + leaned
        return (wanted * capabilityFactor(preset, speaking)).coerceAtLeast(FAINTEST_CHANCE)
    }

    /**
     * How much of what the sentence *set* this preset could actually honour — without which "a cherry
     * grove Age" lands on a preset with no biome table and the word evaporates (§3.3's silent drop).
     *
     * A tilt rather than a filter: parameter-setting words do not choose presets, so this leans the draw
     * without forbidding anything. "Cherry grove floating" still gets floating islands, and their having
     * no biomes is then a real contradiction for [wordsNothingHonours] to charge.
     *
     * **Only what this part of the world owns is asked.** A word reaching several parts sets things none of
     * this part's presets could hold — `tidal`'s moon pull, `polar`'s temperature — and counting those
     * floored every candidate alike, which flattened the word's own lean on this part to nothing.
     */
    private fun capabilityFactor(preset: Taggable, speaking: List<Constraint>): Double {
        val parametersAsked = speaking.flatMap { it.word.setsIn(preset.aspect).keys }.distinct()
            .filter(preset.aspect::ownsParameterNamed)
        if (parametersAsked.isEmpty()) return FULLY_CAPABLE
        val honoured = parametersAsked.count(preset::honoursParameterNamed)
        val share = honoured.toDouble() / parametersAsked.size
        return INCAPABLE_FACTOR + (FULLY_CAPABLE - INCAPABLE_FACTOR) * share
    }

    /** One preset an aspect ended up holding, and how much of the world it covers. */
    private data class Filling(val preset: Taggable, val share: Double)

    /**
     * A set of words that can all be satisfied at once, and what is left that satisfies them.
     *
     * Disjoint by construction: a new territory is only started by a word sharing no candidate with any
     * existing one, and joining an existing one only removes candidates. So two territories can never draw
     * the same preset.
     */
    private data class Territory(val words: List<Constraint>, val candidates: List<Taggable>) {
        operator fun plus(joining: Territory) =
            Territory(words + joining.words, candidates.filter { it in joining.candidates })
    }

    /**
     * One preset from [candidates], drawn in proportion to how strongly the sentence claims each — the
     * same [strengthOf] that decides shares, so a well-liked preset is both likelier and larger.
     */
    private fun pick(
        vocabulary: Vocabulary,
        candidates: List<Taggable>,
        speaking: List<Constraint>,
        draw: Long,
        aspect: Aspect,
        seat: Int,
    ): Taggable {
        candidates.singleOrNull()?.let { return it }
        val scores = candidates.map { preset -> strengthOf(vocabulary, preset, speaking, aspect) }
        val random = XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor (seat * TERRITORY_STRIDE))
        var remaining = random.nextDouble() * scores.sum()
        for ((index, score) in scores.withIndex()) {
            remaining -= score
            if (remaining <= 0.0) return candidates[index]
        }
        return candidates.last()
    }

    /**
     * Every tension the world *honoured*: two words meaning opposite things, both present in the same
     * aspect's answer.
     *
     * Asked of the outcome rather than the sentence, which keeps one disagreement from being charged
     * twice — a tension the world could not honour is already a [Register.FRACTURE] or [Register.DISPLACED].
     */
    private fun tensions(
        vocabulary: Vocabulary,
        sentence: List<Constraint>,
        filled: Map<Aspect, List<Taggable>>,
    ): List<Flaw> = buildList {
        for ((first, second) in sentence.pairs()) {
            // Joined words are not in tension: a writer who said "keep both" was not contradicting himself.
            if (wereJoined(first, second)) continue
            val shared = reachOf(first).intersect(reachOf(second).toSet())
            for (aspect in shared) {
                val chosen = filled[aspect].orEmpty()
                if (chosen.none { first.word.acceptsOn(it, vocabulary.tagsOf(it)) }) continue
                if (chosen.none { second.word.acceptsOn(it, vocabulary.tagsOf(it)) }) continue
                val opposition = vocabulary.disagreement(first.word, second.word) ?: continue
                add(
                    Flaw(
                        Register.TENSION,
                        listOf(first.word.name, second.word.name),
                        aspect,
                        opposition.over,
                        opposition.severity,
                    ),
                )
            }
        }
    }

    /**
     * Every opposition the **sentence** holds that nothing else has already charged for.
     *
     * The flat floor under the mechanical registers, and [Register.OPPOSED] says why it is needed: a
     * collision is what [tensions], `fractured` and `contended` charge, so two words that mean opposite
     * things escape entirely whenever no one preset, parameter or population had to hold both. Asked of
     * the sentence rather than the outcome, because *whether they met* is exactly what must stop mattering.
     *
     * **Once per pair.** A disagreement already priced somewhere is not priced again, which is what keeps
     * this a floor rather than a surcharge on every contradiction that did collide.
     */
    private fun oppositions(
        vocabulary: Vocabulary,
        sentence: List<Constraint>,
        charged: List<Flaw>,
    ): List<Flaw> = buildList {
        for ((first, second) in sentence.pairs()) {
            // "Keep both" is not a contradiction, here for the same reason it is not one in [tensions].
            if (wereJoined(first, second)) continue
            val opposition = vocabulary.disagreement(first.word, second.word) ?: continue
            // **Opposite meanings are opposed wherever they land; two bands on one name only where they
            // share it.** `lush` against `barren` is a contradiction however far apart the two were laid, which
            // is this register's whole point. A size is a name every sized aspect owns, and `small landmass`
            // beside `colossal rainbow` bounds two different ones (Jonah, 2026-09-29).
            val parameter = opposition.onParameter
            if (parameter != null && !boundTogether(vocabulary, first, second, parameter)) continue
            val bothNamed = listOf(first.word.name, second.word.name)
            val alreadyPaidFor = charged.any { it.words.containsAll(bothNamed) } ||
                any { it.words.containsAll(bothNamed) }
            if (alreadyPaidFor) continue
            add(
                Flaw(
                    Register.OPPOSED,
                    bothNamed,
                    // No aspect: the sentence owns this one, since landing nowhere together is the point.
                    aspect = null,
                    opposition.over,
                    opposition.severity,
                ),
            )
        }
    }

    /** Whether [first] and [second] both set [parameter] in some one aspect both of them reach. */
    private fun boundTogether(vocabulary: Vocabulary, first: Constraint, second: Constraint, parameter: String): Boolean {
        val shared = reachOf(first) intersect reachOf(second).toSet()
        fun setsItIn(said: Constraint, aspect: Aspect) = parameter in said.word.setsIn(aspect)
        return shared.any { aspect ->
            aspect.ownsParameterNamed(parameter) && setsItIn(first, aspect) && setsItIn(second, aspect)
        }
    }

    /** What they disagreed over — for the explanation, not the detection. */
    private fun opposedTags(vocabulary: Vocabulary, first: Constraint, second: Constraint): List<String> =
        vocabulary.disagreement(first.word, second.word)?.over ?: emptyList()

    private fun flaw(
        vocabulary: Vocabulary,
        register: Register,
        said: List<Constraint>,
        aspect: Aspect?,
        tags: List<String>,
        firmness: Firmness,
    ) = Flaw(register, said.map { it.word.name }, aspect, tags, register.charge(firmness, vocabulary.earnedBy(register)))

    /**
     * The composition these fillings describe. The stand-in terrain is overwritten immediately — every
     * aspect that *has* presets holds at least one — and exists only because a composition needs one.
     */
    private fun compose(filled: Map<Aspect, List<Filling>>): AgeComposition {
        var composition = AgeComposition(terrains = listOf(Terrain.OVERWORLD))
        for ((aspect, filling) in filled) {
            // An aspect with nothing to choose between fills nothing here and is not missing: its answer is
            // written by the parameter pass, which runs next. Only an aspect that *could* seat a preset and
            // did not is a fault, and that would be the resolver losing one.
            if (aspect.holds != Holds.CATALOGUE) continue
            check(filling.isNotEmpty()) { "the ${aspect.page} aspect resolved to nothing, which no sentence can do" }
            composition = composition.withPresets(aspect, filling.map { it.preset.key }, filling.map { it.share })
        }
        return composition
    }

    /**
     * The composition with every parameter the sentence chose applied (§3.2).
     *
     * After the presets are settled, because a parameter steers whatever filled the aspect. That also
     * means a material never influences *which* preset was drawn — "a world of blackstone" says what the
     * rock is, not whether the world has hills.
     */
    private fun steer(
        vocabulary: Vocabulary,
        composition: AgeComposition,
        sentence: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        var steered = composition
        for (aspect in Aspect.entries) {
            val setting = sentence.filter { it.word.canSet.isNotEmpty() && aspect in reachOf(it) }
            if (setting.isEmpty()) continue
            // Every ranged axis at once, before the rest: a fragment is a whole climate, not a temperature.
            // One at a time, `tropical frozen` fractured on temperature and then displaced humidity,
            // leaving the frozen fragment carrying tropical's wetness. See [spanned].
            steered = steered.spanned(vocabulary, aspect, setting, draw, flaws)
            val settled = rangedNames(steered, aspect)
            for (parameter in setting.flatMap { it.word.setsIn(aspect).keys }.distinct()
                // **The cast is not steered.** Its value is the size of the roll, which the stored entries
                // already are, so writing it into the options would record the same fact in two places and
                // let them disagree. [cast] read it before any of this ran.
                .filter { it != Parameter.CAST && it !in settled && holds(steered, aspect, it) }) {
                val contenders = setting.filter { parameter in it.word.setsIn(aspect) }
                    .sortedWith(FIRMEST_FIRST.thenBy { tieBreak(draw, aspect, it.word) })
                // **A body is steered on its own.** Each clause that minted one carries its index, so what
                // was said about the second sun never reaches the first — the one place a claim is written
                // to a member rather than across the aspect.
                val bodies = contenders.filter { it.describes != null }
                steered = when {
                    // **A body is steered on its own, and what was said of all of them still reaches the
                    // rest.** A claim carrying no member is about the population rather than about one of
                    // it, so it is written to every member no clause described — dropping it lost a page
                    // the writer had already paid for, and said so nowhere.
                    bodies.isNotEmpty() -> {
                        val ofAllOfThem = contenders.firstOrNull { it.describes == null }
                        val many = maxOf(steered.membersIn(aspect), bodies.maxOf { (it.describes ?: 0) + 1 })
                        (0..<many).fold(steered) { held, member ->
                            val own = bodies.firstOrNull { it.describes == member } ?: ofAllOfThem
                            if (own == null) held
                            else held.withOptionsFor(
                                aspect,
                                member,
                                parameter,
                                listOf(own.word.setsIn(aspect).getValue(parameter)),
                            )
                        }
                    }
                    canFracture(steered, aspect, parameter, contenders) ->
                        steered.fractured(vocabulary, aspect, parameter, contenders, flaws)
                    // The sentence's own order is handed down beside the ranked one, for the parameters whose
                    // values are a sequence rather than a set — see [contended].
                    else -> steered.contended(vocabulary, aspect, parameter, contenders, setting, flaws)
                }
            }
            flaws += wordsNothingHonours(vocabulary, steered, aspect, setting)
        }
        return steered
    }

    /**
     * A **ranged** parameter's claimants, gathered and applied: those that do not disagree **broaden** into
     * one span, and those that do take ground of their own. The one combining rule whose outcome is wider
     * than either input — see [Holds.RANGE].
     *
     * **Overlap is the arbiter, not the antonym table.** Antonymy is a lossy proxy for "these cannot both
     * hold": two words with no antonym pair but disjoint spans would broaden into a hull covering ground
     * *neither* asked for. The table keeps its own job — [opposedTags] still names *why* they disagreed.
     */
    private fun AgeComposition.spanned(
        vocabulary: Vocabulary,
        aspect: Aspect,
        setting: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        // **Each ground is bounded on its own.** A span confined to a biome is a second value for the same
        // axis rather than a rival for the one value, so the words that share a confinement are resolved
        // together and written with it — which is what `Options.of(parameter, biome)` reads back.
        val grounds = setting.map { it.confinedTo }.distinct()
        if (grounds.size > 1) {
            return grounds.fold(this) { bounded, ground ->
                bounded.spannedIn(vocabulary, aspect, setting.filter { it.confinedTo == ground }, ground, draw, flaws)
            }
        }
        return spannedIn(vocabulary, aspect, setting, grounds.singleOrNull(), draw, flaws)
    }

    private fun AgeComposition.spannedIn(
        vocabulary: Vocabulary,
        aspect: Aspect,
        setting: List<Constraint>,
        ground: Identifier?,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        val axes = rangedNames(this, aspect)
        fun asksOfAnAxis(said: Constraint) = axes.any { it in said.word.setsIn(aspect) || it in said.word.bendsIn(aspect) }
        val speaking = setting.filter(::asksOfAnAxis)
            .sortedWith(FIRMEST_FIRST.thenBy { tieBreak(draw, aspect, it.word) })
        if (speaking.isEmpty()) return this

        fun spansIn(parameters: Map<String, String>): Map<String, Span> = axes.mapNotNull { axis ->
            (parameters[axis]?.let(Setting::read) as? Setting.Fixed)?.let { axis to it.span }
        }.toMap()

        /** What a word *demands* of each axis — the only form that can put two words at odds. */
        fun boundsIn(said: Constraint): Map<String, Span> = spansIn(said.word.setsIn(aspect))

        /** Where a word would like each axis to sit, without demanding it. */
        fun bendsIn(said: Constraint): Map<String, Span> = spansIn(said.word.bendsIn(aspect))

        /**
         * Everything a word asks that is *not* a demand — its limits, nudges and spreads.
         *
         * Kept out of the grouping above on purpose. Only a demand can refuse another word, so only demands
         * decide who agrees with whom and who fractures a world; a floor yields to any band already inside
         * it and a nudge cannot fail at all. These settle onto whatever the demands left (see [Setting]).
         */
        fun askingIn(said: Constraint): List<Pair<String, Setting>> = axes.flatMap { axis ->
            listOfNotNull(said.word.setsIn(aspect)[axis], said.word.bendsIn(aspect)[axis])
                .mapNotNull(Setting::read)
                .filterNot { it is Setting.Fixed }
                .map { axis to it }
        }

        fun agree(one: Constraint, other: Constraint): Boolean {
            // **Two clauses about different members are never in tension**, whatever they demand: each
            // describes its own individual, so they share no ground and cannot argue over it. Asked before
            // `and`, which joins words inside a clause and so cannot span two.
            val aboutDifferentMembers = one.describes != null && other.describes != null &&
                one.describes != other.describes
            if (aboutDifferentMembers) return false
            if (wereJoined(one, other)) return true
            val mine = boundsIn(one)
            val theirs = boundsIn(other)
            // Two words about different axes never disagree — "hot" and "wet" is an ordinary sentence.
            return (mine.keys intersect theirs.keys).all { axis ->
                mine.getValue(axis).overlaps(theirs.getValue(axis))
            }
        }

        // **`sets` bounds; `bends` leans** (§4.4). Only a bound may divide a world: a bend removes no
        // freedom, so it can never fail, and a fracture is a failure.
        val bounding = speaking.filter { axes.any { axis -> axis in it.word.setsIn(aspect) } }

        /** Where the bends would like [axis] to sit, in the axis's own terms. */
        fun preferred(axis: String): Double? {
            val wants = speaking.mapNotNull { bendsIn(it)[axis] }
            if (wants.isEmpty()) return null
            return wants.map { (it.least + it.most) / 2.0 }.average()
        }

        /**
         * [bounds] with each axis's middle pulled toward what the bends asked for — including an
         * axis nobody bounded, which is then the whole natural range with its weight moved rather than a
         * stretch of it. That is what lets `beautiful` lean an Age temperate without narrowing it at all.
         */
        fun bentTo(bounds: Map<String, Span>): Map<String, Span> {
            val touched = bounds.keys + speaking.flatMap { bendsIn(it).keys }
            return touched.associateWith { axis ->
                val stretch = bounds[axis] ?: Span.NATURAL
                val want = preferred(axis) ?: return@associateWith stretch
                stretch.bentToward(stretch.fractionOf(want))
            }
        }

        val gathered = gathered(bounding, ::agree)
        /** Which member each group describes, or null for a group of words aimed at nothing in particular. */
        fun memberOf(group: List<Constraint>): Int? = group.firstNotNullOfOrNull { it.describes }
        // **Written in the writer's order where every group is a member of its own.** Groups come out
        // ordered by precedence, which is nobody's intent; a clause's ranged axes have to land on the same member
        // its ordinary parameters do, and those are written against `Constraint.describes` by [steer].
        val described = gathered.map(::memberOf)
        // Vacuously true of no groups at all, which is an Age whose only ranged words *bend* rather than
        // demand — so the emptiness is asked first, this being a path that returns rather than a sort.
        val eachIsItsOwn = gathered.isNotEmpty() &&
            described.none { it == null } &&
            described.distinct().size == gathered.size
        val groups = if (eachIsItsOwn) gathered.sortedBy(::memberOf) else gathered
        /**
         * Each group settles to the climate its words *jointly* describe — **narrowing, unless `and` says
         * otherwise**.
         *
         * Two words that agree about an axis both have to be honoured, so what they leave is the stretch
         * they share. Broadening instead meant `temperate` beside anything warmer bought a band wider than
         * either word asked for, which is the one outcome neither writer wanted (Jonah, 2026-08-06).
         *
         * **`and` is how a writer asks for the wide band**, and it already means exactly that everywhere
         * else: "keep both, and keep them apart" (§3.2). So a joined run broadens among itself, and the
         * runs then narrow against each other.
         *
         * The fold cannot empty: a group is built by requiring every member to agree with every other, and
         * pairwise overlap on a line guarantees a common point — see [Span.narrowedTo].
         */
        val climates = groups.map { group ->
            // Keyed by the `and`-group, or by position for a word standing alone, so that two unjoined
            // words never share a run merely by both being unjoined.
            val runs = group.withIndex().groupBy { (at, said) -> said.group ?: at }.values
            axes.mapNotNull { axis ->
                runs.mapNotNull { run ->
                    run.mapNotNull { (_, said) -> boundsIn(said)[axis] }
                        .reduceOrNull { held, next -> held.broadenedTo(next) }
                }
                    .reduceOrNull { held, next -> held.narrowedTo(next) }
                    ?.let { axis to it }
            }.toMap()
        // Nothing narrowed anything, so there is one climate and the bend is the whole of what was said.
        }.ifEmpty { listOf(emptyMap()) }

        /**
         * [bounds] with every limit and nudge in the sentence settled onto it.
         *
         * After the bend, so a bend's pull on the middle survives a nudge to the ends, and
         * after the grouping, so a word that only leans never divided anything.
         *
         * **A demand is passed on as a demand and the natural range is not**, which is the whole of
         * whether a lone nudge does anything. `Setting.settle` slides a band somebody asked for and leans
         * one nobody did, and it tells the two apart by whether it was given a demand — so seeding the
         * list with the natural range as a `Fixed` made every axis look claimed, and every shift on an
         * otherwise-unsaid axis was silently discarded. `sultry` is two nudges and nothing else, and it
         * resolved to the full range on both of its axes (measured 2026-09-08).
         *
         * A limit that cannot be met at all loses rather than failing the Age: it is the weaker claim, and
         * the demand it argues with was already priced when the groups were formed.
         */
        fun settledWith(bounds: Map<String, Span>): Map<String, Span> {
            val asking = speaking.flatMap(::askingIn)
            if (asking.isEmpty()) return bounds
            return (bounds.keys + asking.map { it.first }).associateWith { axis ->
                val demanded = bounds[axis]
                val onThisAxis = asking.filter { it.first == axis }.map { it.second }
                val asked = listOfNotNull(demanded?.let(Setting::Fixed)) + onThisAxis
                Setting.settle(asked) ?: demanded ?: Span.NATURAL
            }
        }

        fun written(composition: AgeComposition, member: Int, bounds: Map<String, Span>): AgeComposition {
            var steered = composition
            for ((axis, span) in bounds) {
                val already = steered.optionsFor(aspect, member).allSpelled(axis)
                val bounded = Claim(span.spelled(), confinedTo = ground).spelled()
                steered = steered.withOptionsFor(aspect, member, axis, (already - bounded + bounded).toList())
            }
            return steered
        }

        // **Described apart, so each group is a member rather than a territory** — and this comes first,
        // because a population that is not spatial has no ground to divide and needs none. The bodies are
        // as many as the clauses that described them, so neither the fracture charge nor [MOST_TERRITORIES]
        // applies: a sky may hold more suns than a world may hold landforms.
        //
        // Written against the member each group *describes* rather than where it sits, since a clause that
        // demanded nothing of a ranged axis never reaches `bounding` at all — `a sun. a colossal sun.` is
        // one group, and it is the second sun it is about.
        if (eachIsItsOwn) {
            val bodies = (described.filterNotNull().maxOrNull() ?: 0) + 1
            return climates.withIndex().fold(withMembers(aspect, bodies)) { held, (at, bounds) ->
                written(held, memberOf(groups[at]) ?: 0, settledWith(bentTo(bounds)))
            }
        }

        val couldFracture = aspect.spatial && membersIn(aspect) == 1
        if (climates.size == 1 || !couldFracture || climates.size > MOST_TERRITORIES) {
            // One coherent climate, or nowhere to put a second — then the leading group wins and the rest
            // are displaced, the same fallback every other parameter has.
            if (climates.size > 1) {
                val leading = groups.first().first()
                for (group in groups.drop(1)) {
                    flaws += flaw(vocabulary, Register.DISPLACED, listOf(group.first(), leading), aspect, emptyList(), group.first().word.firmness)
                }
            }
            val agreed = settledWith(bentTo(climates.first()))
            // **A claim naming no member is about the population rather than about one of it**, so it is
            // written to every body there is — the same rule the catalogue path has always had, where
            // `withOptions` writes a parameter to each member in turn.
            //
            // Unreachable until a *word* could grow a cast: every other route to a second body is a clause
            // per body, and those take the branch above. It showed up the moment an inferno hung three
            // suns and sized the first one, leaving the other two to be filled from the template.
            if (!aspect.membersAreDescribed) return written(this, member = 0, bounds = agreed)
            return (0..<maxOf(membersIn(aspect), 1)).fold(this) { held, member ->
                written(held, member, agreed)
            }
        }

        val leading = groups.first().first()
        flaws += fracturesAgainst(vocabulary, aspect, leading, groups.drop(1).map { it.first() })
        var fractured = withMembers(aspect, groups.size)
        for ((member, bounds) in climates.withIndex()) fractured = written(fractured, member, settledWith(bentTo(bounds)))
        return fractured
    }

    /** Which of [aspect]'s parameters bound a continuous axis — asked of the seated presets, as they own them. */
    private fun rangedNames(composition: AgeComposition, aspect: Aspect): List<String> =
        (composition.presets.filter { it.aspect == aspect }.flatMap { it.ownParameters } + aspect.parameters)
            .filter { it.holds == Holds.RANGE }
            .map { it.name }
            .distinct()

    /**
     * What the sentence asked a **population** to hold more or less of (§3.2, §8.2) — the pass for the
     * aspects nothing is drawn for.
     *
     * [steer] has already done the half a word does by *naming* a member: that word sets the parameter,
     * and its claim carries the weight the naming was worth. This is the other half, and the only way a
     * word that names nothing can touch a population at all — every member of the curated pool is asked
     * how well it answers the sentence, and the ones it answers well or badly are claimed accordingly.
     *
     * An Age begins with everything the game has and this adjusts it, so a member nobody spoke about is
     * left out of the recipe entirely rather than written down at its ordinary weight.
     */
    private fun weighed(
        vocabulary: Vocabulary,
        composition: AgeComposition,
        sentence: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        var weighed = composition
        for (aspect in Aspect.entries.filter { it.holds == Holds.WEIGHTED_SET }) {
            val pool = aspect.pool ?: continue
            val speaking = sentence.filter { aspect in reachOf(it) }
            if (speaking.isEmpty()) continue
            // **Each ground is weighed on its own**, as a ranged axis already is ([spanned]). A claim
            // confined to a biome is a second claim about the same member rather than a rival for the one
            // claim, and `Features.placedIn` and `Spawns` both read a claim back per biome — so the whole
            // of honouring `in` (§4.3.1) is carrying the ground this far.
            for (ground in speaking.map { it.confinedTo }.distinct()) {
                weighed = weighed.weighedIn(
                    vocabulary,
                    aspect,
                    pool,
                    speaking.filter { it.confinedTo == ground },
                    ground,
                    draw,
                    flaws,
                )
            }
        }
        return weighed
    }

    /** What one ground's claimants ask of [aspect]'s population — [weighed]'s body, once per confinement. */
    private fun AgeComposition.weighedIn(
        vocabulary: Vocabulary,
        aspect: Aspect,
        pool: Pool,
        speaking: List<Constraint>,
        ground: Identifier?,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        // **What the sentence put in**, the same widening a catalogue's draw gets. A member named — by
        // being chosen or by being admitted — was reaching nothing here, since this drew from curation
        // alone, and curation is exactly what a writer naming a biome outright is reaching past.
        fun namedBy(said: Constraint) =
            said.word.admitsIn(aspect) + listOfNotNull(said.word.choiceIn(aspect)?.key)
        val curated = vocabulary.availableToBroadWordsIn(aspect)
        val drawnFrom = (
            curated + speaking.flatMap(::namedBy).distinct().mapNotNull(aspect::presetFor)
            ).distinct()
        val reached = drawnAmong(
            vocabulary,
            drawnFrom.mapNotNull { member ->
                claimForMember(vocabulary, member, pool, speaking, aspect, member in curated, draw, ground)
            },
            drawnFrom,
            speaking,
            aspect,
            draw,
        )
        if (reached.isEmpty()) return this
        flaws += crowdedOutOfAnOnly(vocabulary, speaking, aspect)
        val settled = optionsFor(aspect, 0).allOf(pool)
        return withOptions(aspect, pool.name, (settled + spelled(reached, pool, drawnFrom, ground)).distinct())
    }

    /**
     * **What a sentence's leans bring about, drawn rather than taken whole.**
     *
     * A lean on a tag reaches dozens of members, so lifting every one of them is
     * how a single `foreboding` page wrote forty-nine creatures and seven phenomena into one Age (Jonah,
     * 2026-09-17, the Age Tsi — hadalfish, ghasts, piglins and a wither in a basalt world, with a tempest,
     * a blizzard, an inferno and a deluge running at once). A word meaning dread should make an Age
     * *dreadful*, which is a few of the right things rather than the catalogue.
     *
     * So the lifts the leans made are drawn among, **weighted by how hard they leaned**, so the few that
     * survive are still the fitting few — and off the Age's own seed, so two Ages written from the same
     * word are unalike. That second property is the one worth having: `foreboding` now means something
     * different each time it is written.
     *
     * **Only where a lift introduces**, which is [INTRODUCES_WHAT_IT_LIFTS] — everywhere else a lift
     * reweighs what the biome already grows, and bending all fifty-nine of a desolate Age's features is
     * exactly what a lean is for. **Only upward**, since asking for less of something brings nothing about.
     * A word naming a handful — `polar`'s six creatures — lifts no more than [INTRODUCES_WHAT_IT_LIFTS]
     * allows, and so is left whole.
     */
    private fun drawnAmong(
        vocabulary: Vocabulary,
        reached: List<Claim>,
        drawnFrom: List<Taggable>,
        speaking: List<Constraint>,
        aspect: Aspect,
        draw: Long,
    ): List<Claim> {
        val allowed = INTRODUCES_WHAT_IT_LIFTS[aspect] ?: return reached
        val byKey = drawnFrom.associateBy(Taggable::key)

        fun leanedOn(claim: Claim): Double {
            val member = byKey[claim.value] ?: return NO_LEAN
            val tags = vocabulary.tagsOf(member)
            return speaking.sumOf { it.word.biasOn(member, tags) }
        }

        val lifted = reached
            .filter { it.onlyWhereItGrows && it.density > Rung.ORDINARY }
            .map { Lifted(it, leanedOn(it)) }
            .filter { it.leanedOn > NO_LEAN }
        if (lifted.size <= allowed) return reached
        val kept = drawnFew(lifted, allowed, mix64(draw xor aspect.ordinal.toLong()))
        val dropped = lifted.map { it.claim.value }.toSet() - kept
        return reached.filterNot { it.value in dropped }
    }

    /** One member an atmosphere lifted, and how hard it leaned to do it — see [drawnAmong]. */
    private class Lifted(val claim: Claim, val leanedOn: Double)

    /**
     * [howMany] of [among], drawn without replacement and weighted by how hard the atmosphere leaned, as a
     * pure function of [seed] so an Age resolves the same way every time it is opened.
     */
    private fun drawnFew(among: List<Lifted>, howMany: Int, seed: Long): Set<String> {
        val left = among.toMutableList()
        val chosen = mutableSetOf<String>()
        var roll = seed
        while (chosen.size < howMany && left.isNotEmpty()) {
            roll = mix64(roll)
            val landing = unitDouble(roll) * left.sumOf(Lifted::leanedOn)
            var walked = NO_LEAN
            var taken = left.lastIndex
            for (index in left.indices) {
                walked += left[index].leanedOn
                if (walked > landing) {
                    taken = index
                    break
                }
            }
            chosen += left[taken].claim.value
            left.removeAt(taken)
        }
        return chosen
    }

    private const val NO_LEAN = 0.0

    /** Where the part of the world goes in a bare roll's key, clear of the word's hash. */
    private const val ROLL_SHIFT = 40

    /**
     * Where a description above ordinary **introduces** its member rather than reweighing one already
     * there, and how many of them one atmosphere may bring about.
     *
     * These two are the aspects whose consumers read `Claim.bringsNothingAbout` — `Spawns.resolved` and
     * `Phenomena.claimsIn` — and they read it because there is nothing to bend: a biome offering no ghast
     * cannot offer more of one, and a phenomenon pool starts empty. An aspect absent from this map is one
     * where a lift only bends, so a draw would make an atmosphere patchy and protect against nothing.
     *
     * **Both counts are a first guess and want a walk.** Two phenomena is a dreadful Age rather than four
     * disasters at once; six creatures is a biome's worth of the wrong thing on top of whatever it
     * already offers.
     */
    private val INTRODUCES_WHAT_IT_LIFTS = mapOf(
        Aspect.SPAWNS to 6,
        Aspect.PHENOMENA to 2,
    )

    /**
     * **How one [word] alone stands towards one member of [aspect]** — a sentence of one page, resolved.
     *
     * Both kinds of draw answer here, because both are a draw from a set and a tool has no business
     * knowing which arithmetic each uses. What differs is only the scale the number is on, and the caller
     * does not need it: what a chart wants is one member against the others in the same list.
     */
    fun standingOf(vocabulary: Vocabulary, word: Word, aspect: Aspect, member: Taggable): Standing {
        val said = listOf(Constraint(word, setOf(aspect)))
        val pool = aspect.pool
            ?: return Standing(
                strength = strengthOf(vocabulary, member, said, aspect),
                kept = word.acceptsOn(member, vocabulary.tagsOf(member)),
            )
        val claim = claimForMember(vocabulary, member, pool, said, aspect, member in vocabulary.availableToBroadWordsIn(aspect), draw = null)
            ?: return Standing(Rung.ORDINARY, kept = true)
        return Standing(claim.density, kept = claim.polarity != Polarity.EXCEPT)
    }

    /**
     * How much of the world one member of a population should have, against what it would have had anyway
     * — or null where the sentence said nothing that reaches it.
     *
     * Two readings, and the difference between them is the design's own (§3.3): a **lean** tilts by how
     * well the member answers it, signed, so "beautiful" thins the ash flats as surely as it thickens the
     * flower meadows; a **bar** bears down on the members that clear it; and a member is never *removed*
     * by either, since a word that merely likes something is not an instruction to delete anything
     * ([co.voik.agesandtheart.worldgen.biome.BiomePreference.LEAST_KEPT]).
     *
     * `only` and `except` are the exception, and deliberately so: those are the writer saying outright
     * what to keep and what to strike, rather than what to prefer.
     */
    private fun claimForMember(
        vocabulary: Vocabulary,
        member: Taggable,
        pool: Pool,
        speaking: List<Constraint>,
        aspect: Aspect,
        alreadyInThePool: Boolean,
        /** The Age being written, or null to ask what a word reaches in *every* Age — see [strictnessOf]. */
        draw: Long?,
        /** The biome these claimants confined themselves to, or null where they speak for the Age. */
        ground: Identifier? = null,
    ): Claim? {
        val tags = vocabulary.tagsOf(member)
        // **A word that takes a member out takes it out.** Excluding is the pipeline's own removal, so it
        // does not have to argue the weight down to nothing — which is the only way `untouched` can mean
        // anything, there being no tag for the absence of a thing to put on the members that are present.
        // And an at-most bar is an exclusion with a level: a member carrying more than it allows is struck.
        fun strikes(said: Constraint) =
            said.word.excludes(member, tags) || !said.word.withinItsLimitsIn(aspect, tags)
        if (speaking.any { it.word.narrows && strikes(it) }) {
            return Claim(member.key, Polarity.EXCEPT, confinedTo = ground)
        }
        // **Claiming something here is the price of insisting.** A word that only leans restricts nothing,
        // and `acceptsOn` keeps every member where nothing was restricted — read as insistence that would
        // be a word demanding the whole population it merely had a preference within.
        // **How strict this Age is being, which only a plain mention is subject to.** `only` and `except`
        // are the writer saying outright what to keep and what to strike, so they are read at the full bar
        // however generous the Age: a preference may be lucky, an instruction may not.
        fun strictnessFor(said: Constraint): Double =
            if (draw == null || said.polarity != Polarity.ASSERTED) {
                Bars.STRICT
            } else {
                strictnessOf(draw, aspect, said.word)
            }
        val insisting = speaking.filter { said ->
            val narrowsHere = said.word.narrows && said.word.constrainsPresetsIn(aspect)
            narrowsHere && said.word.acceptsOn(member, tags, strictnessFor(said))
        }
        // **What a bar weighs is a tag query.** Naming a member is step one and stands on its own, so
        // scoring it here too made every named member arrive at the ceiling — a share no rung could move
        // and no second word could add to.
        val insisted = insisting.sumOf { it.word.pullIn(aspect, tags) }
        // **Naming a member asks for more of it — but only where it was going to be there anyway**, which
        // is the whole of what naming one does to a *population*: a biome is present unless something
        // strikes it, so a mention that claimed only the ordinary share would be a page read, charged for,
        // and worth nothing.
        //
        // **A member that only exists because a word asked for it takes no bump** (Jonah, 2026-09-10). A
        // volcano is not in the world until somebody writes one, so naming it is already the whole of the
        // request; doubling it on top would mean a writer could never ask for *one* mountain, and the
        // ordinary rung — the thing every quantifier is measured against — would be unreachable.
        //
        // **`alreadyInThePool` is deliberately not the test here.** It means listed in the tag table, which
        // coincides with being present-anyway for biomes and does not for features: tagging the volcanic
        // features `molten` so `volcanic` could find them would otherwise have handed the bump straight
        // back. See [PresetProfile.presentAnyway].
        val mentions = speaking.count { it.word.choiceIn(aspect)?.key == member.key }
        val mentioned = if (vocabulary.isPresentAnyway(member)) mentions * A_MENTION_IS_WORTH else NOTHING_MORE
        // **Every word leans**, as it does for a catalogue.
        val leaned = speaking.sumOf { it.word.biasOn(member, tags) }
        val wanting = insisting + speaking.filter {
            it !in insisting && it.word.biasOn(member, tags) > 0.0
        }
        val polarity = wanting.map { it.polarity }.firstOrNull { it != Polarity.ASSERTED }
        // **The rung reaches a member a tag chose, not only one a word named.** `frequent plants` was
        // losing its `frequent` in silence: a quantifier travels on the claim, and the claim a *named*
        // member makes is written elsewhere ([claimed]) where the amount was already read. Here the member
        // was chosen by a query, so nothing had ever looked.
        //
        // It scales what the words asked for rather than adding to it, which is what lets `scarce` mean
        // *less than the world would have had*: a quarter of an ordinary claim is a quarter, where a
        // quarter added to it would still be more. Ordinary is one, so an unquantified word changes
        // nothing, and two quantified words compound — `teeming` said twice is very teeming.
        val rung = wanting.fold(Rung.ORDINARY) { standing, said -> standing * said.density }
        val asked = (Rung.ORDINARY + mentioned + insisted + leaned) * rung
        val weight = Rung.legible(asked.coerceIn(pool.leastKept, MOST_OF_A_WORLD))
        // Struck out rather than kept at nothing: a claim of none of something is what `except` says, and
        // saying it that way keeps one mechanism for removal instead of two.
        if (weight <= NONE_OF_IT) return Claim(member.key, Polarity.EXCEPT, confinedTo = ground)
        // **Described rather than named**, which decides whether this asks for the thing or for more of
        // it where it already is (world model §3). A member reached only by a query is a description; one
        // a word named is a naming. `only` and `except` are neither — they are instructions about what the
        // Age holds — so they are left to mean what they always did.
        //
        // **Admitting is naming**, and counting only [mentions] missed it: `beautiful` admits the
        // sunflower plains outright because no tag in the table says a sunflower plain is lovely, and read
        // as a description that admission could never put one anywhere.
        fun namesItOutright(said: Constraint) =
            said.word.choiceIn(aspect)?.key == member.key || member.key in said.word.admitsIn(aspect)
        val namedOutright = speaking.any(::namesItOutright)
        val described = polarity == null && !namedOutright
        // **Ordinary is only silence for a member the world would have had anyway.** One a word put there
        // by name arrives at ordinary standing and dropping the claim would drop the admission with it —
        // the member would be reached, weighed, and then quietly left out of the world it was named into.
        //
        // **Both conditions, and each was learned by getting it wrong the same afternoon.**
        //
        // - Without `isPresentAnyway`: `alreadyInThePool` means *available to broad words*, which a volcano is, so the
        //   moment naming one stopped carrying [A_MENTION_IS_WORTH] it landed at exactly ordinary and was
        //   dropped as saying nothing — `age volcano features` resolved to an Age with no volcano in it.
        // - Without `namedOutright`: every member that is not there anyway stopped being silence *at all*,
        //   so a sentence about diamonds came back claiming ten features of ours it had never mentioned.
        //
        // Together they say the one thing meant: an ordinary weight is silence unless a word actually
        // named this member and the world would not have had it otherwise.
        val speaksForItself = namedOutright && !vocabulary.isPresentAnyway(member)
        val nothingToSay = polarity == null && Rung.isOrdinary(weight) && alreadyInThePool && !speaksForItself
        if (nothingToSay) return null
        return Claim(
            member.key,
            polarity ?: Polarity.ASSERTED,
            weight,
            confinedTo = ground,
            onlyWhereItGrows = described,
        )
    }

    /**
     * These claims as a recipe holds them — **collapsed to the population's own word for emptiness** where
     * the sentence struck out everything the Art can reach.
     *
     * A word meaning "nothing built here" would otherwise write an exclusion per structure set, which says
     * the same thing at ten times the length and stops saying it the moment a pack adds a set.
     */
    private fun spelled(
        claims: List<Claim>,
        pool: Pool,
        drawnFrom: List<Taggable>,
        ground: Identifier? = null,
    ): List<String> {
        val emptied = pool.emptiedBy ?: return claims.map { it.spelled() }
        val everythingStruck = claims.size == drawnFrom.size && claims.all { it.polarity == Polarity.EXCEPT }
        // **A confined group never collapses**: the word for emptiness carries no ground, so "nothing in
        // the jungle" written that way would empty the whole Age instead of one biome.
        return if (everythingStruck && ground == null) listOf(emptied) else claims.map { it.spelled() }
    }

    /**
     * Whether the groups can each be given ground — **parameters divide, not just presets** (§3.4).
     *
     * Three conditions: the aspect must be able to divide at all; it must hold exactly one preset, since
     * two divisions would multiply and *which* territory a word was about is a question the grammar cannot
     * yet answer (§4.3.1 aims at aspects, not members); and there must be room for the groups.
     */
    private fun canFracture(
        composition: AgeComposition,
        aspect: Aspect,
        parameter: String,
        contenders: List<Constraint>,
    ): Boolean {
        if (!aspect.spatial) return false
        if (composition.membersIn(aspect) != 1) return false
        return groupsOf(aspect, parameter, contenders).size in 2..MOST_TERRITORIES
    }

    /**
     * The claimants gathered into the fewest sets satisfiable at once — the parameter-level echo of
     * [Territory]. Two belong together when they asked for the same value, or the writer joined them with
     * `and`, which is what keeps the conjunction meaning "mingle" rather than "divide".
     */
    private fun groupsOf(aspect: Aspect, parameter: String, contenders: List<Constraint>): List<List<Constraint>> {
        fun agree(one: Constraint, other: Constraint) =
            one.word.setsIn(aspect)[parameter] == other.word.setsIn(aspect)[parameter] || wereJoined(one, other)
        return gathered(contenders, ::agree)
    }

    /**
     * [contenders] gathered into the fewest sets whose members **all** [agree] with one another: join the
     * first group you are compatible with, start a new one when compatible with none.
     *
     * **Every member, not merely one**, because agreement between spans is not transitive — `hot` overlaps
     * `warm` overlaps `temperate` overlaps `cool`, so joining on any single member walks the chain and
     * swallows a whole ladder into one group whose hull is the entire axis, silently handing back the
     * full-range world the writer was narrowing away from.
     */
    private fun gathered(
        contenders: List<Constraint>,
        agree: (Constraint, Constraint) -> Boolean,
    ): List<List<Constraint>> {
        val groups = mutableListOf<MutableList<Constraint>>()
        for (said in contenders) {
            val home = groups.firstOrNull { group -> group.all { seated -> agree(seated, said) } }
            if (home == null) groups += mutableListOf(said) else home += said
        }
        return groups
    }

    /**
     * The aspect seated once per group, each territory steered by its own group — "copper spires and
     * andesite hills" without either word being lost.
     *
     * Seating one preset several times is the point: §3.2 settled that a list on a parameter means mingled
     * rather than divided, because division already has a spelling — name the preset twice. Shares are left
     * unsaid, since nothing in a parameter conflict says which claim deserves more ground.
     */
    private fun AgeComposition.fractured(
        vocabulary: Vocabulary,
        aspect: Aspect,
        parameter: String,
        contenders: List<Constraint>,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        val groups = groupsOf(aspect, parameter, contenders)
        val seated = presets.first { it.aspect == aspect }
        val leading = groups.first().first()
        flaws += fracturesAgainst(vocabulary, aspect, leading, groups.drop(1).map { it.first() })
        var fractured = withPresets(aspect, List(groups.size) { seated.key })
        for ((member, group) in groups.withIndex()) {
            val asked = group.map { it.word.setsIn(aspect).getValue(parameter) }.distinct()
            fractured = fractured.withOptionsFor(aspect, member, parameter, asked)
        }
        return fractured
    }

    /**
     * **`only` said of one thing, beside a plain mention of another that was not joined to it** — a
     * contradiction, and the one a population can have (§3.2).
     *
     * `only slime and teeming cows` is a writer keeping both and saying nothing else may come; `only
     * slime, teeming cows` is a writer singling out the slimes and then asking for something else as well,
     * which the world cannot honour as written. It is charged rather than refused, and the union stands —
     * §2's pen never rejects, and §3.3 forbids the silent drop that ignoring one of them would be.
     *
     * This is what `and` is *for* here, and it is the reason a population needs the conjunction at all:
     * without it, unjoined claims already union, so `and` would say nothing.
     */
    private fun crowdedOutOfAnOnly(
        vocabulary: Vocabulary,
        contenders: List<Constraint>,
        aspect: Aspect,
    ): List<Flaw> {
        val singledOut = contenders.filter { it.polarity == Polarity.ONLY }
        if (singledOut.isEmpty()) return emptyList()
        fun joinedToAnythingSingledOut(said: Constraint) = singledOut.any { wereJoined(said, it) }
        // The aiming page closing the clause claims nothing, so there is nothing of it to crowd out.
        fun couldBeCrowdedOut(said: Constraint) =
            said.polarity == Polarity.ASSERTED && !said.word.aims && !joinedToAnythingSingledOut(said)
        val crowdedOut = contenders.filter(::couldBeCrowdedOut)
        return crowdedOut.map { said ->
            flaw(vocabulary, Register.DISPLACED, listOf(said, singledOut.first()), aspect, emptyList(), said.word.firmness)
        }
    }

    /**
     * A predicative parameter with more than one claimant — where `and` earns its place (§3.2).
     *
     * A parameter holds one answer, so unjoined claims contend: the most precise wins, the seed breaks a
     * tie, and every loser is charged as displaced. Joining them changes what was asked rather than who
     * wins — "blackstone **and** tuff" is one rock made of both. Only the winner's own group joins it.
     *
     * **Two words asking for the same thing are not contending**, which is the rule [groupsOf] already
     * carries for grouping and this path was missing: `intergalactic lost` set no suns twice and was
     * charged twelve instability for agreeing with itself.
     */
    private fun AgeComposition.contended(
        vocabulary: Vocabulary,
        aspect: Aspect,
        parameter: String,
        contenders: List<Constraint>,
        asWritten: List<Constraint>,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        fun keptAmong(rivals: List<Constraint>): List<Constraint> {
            val winner = rivals.first()
            fun asksWhatTheWinnerAsks(said: Constraint) =
                said.word.setsIn(aspect)[parameter] == winner.word.setsIn(aspect)[parameter]
            val mingled = rivals.filter { it == winner || wereJoined(it, winner) || asksWhatTheWinnerAsks(it) }
            for (loser in rivals - mingled.toSet()) {
                flaws += flaw(vocabulary, Register.DISPLACED, listOf(loser, winner), aspect, emptyList(), loser.word.firmness)
            }
            return ordered(mingled, parameter, aspect, asWritten)
        }
        // **Two claims that apply in different places are not rivals**, which is what this used to miss. A
        // dial confined to a biome is a second value winning in one corner rather than a second opinion
        // about the same thing ([Options.of]) — so `blue grass purple grass in swamp` is a blue world with
        // a purple swamp, and contending across the two displaced whichever was written second and charged
        // the sentence for a contradiction it had not made (Jonah, 2026-08-30).
        //
        // Grouped rather than filtered, so two clauses sited in the *same* biome still contend with each
        // other, which they should: they do both apply in one place.
        val kept = contenders.groupBy { it.confinedTo }.values.flatMap(::keptAmong)
        return withOptions(
            aspect,
            parameter,
            kept.map { Claim(it.word.setsIn(aspect).getValue(parameter), confinedTo = it.confinedTo).spelled() }
                .distinct(),
        )
    }

    /**
     * The mingled contenders in the order they should be *stored* — as ranked, or as the writer wrote them.
     *
     * **Every mingling parameter but one holds a set**, where two rocks in a wall are both in it and neither is
     * first, and ranking them by precedence and then by a seeded tie-break is right: it spreads two Ages written
     * alike. **One holds a sequence.** An aurora's colours run from its crown to its hem, and which is the
     * crown is the one thing the writer stated outright — so `red and green aurora` and `green and red
     * aurora` are two different skies and must stay so.
     *
     * The parameter says which it is ([Parameter.keepsWrittenOrder]) rather than this function knowing any parameter
     * by name, which is what keeps the resolver from growing a list of special cases. Written order decides
     * one other thing in the whole resolver — which template a book starts from — and design §3.5 names
     * both.
     */
    private fun AgeComposition.ordered(
        mingled: List<Constraint>,
        parameter: String,
        aspect: Aspect,
        asWritten: List<Constraint>,
    ): List<Constraint> {
        val keepsOrder = parameterNamed(this, aspect, parameter)?.keepsWrittenOrder == true
        if (!keepsOrder) return mingled
        return mingled.sortedBy(asWritten::indexOf)
    }

    /**
     * Whether this aspect has such a parameter at all. One word carries a single `sets` map across every aspect
     * it speaks to, so a derived block word setting a material reaches the sea as well — and a sea does not
     * *wear* a substance, it **is** one.
     *
     * Asked of what the seated presets **declare**, not what they honour: an aspect that never heard of the
     * parameter is not being addressed, where one that declares it and ignores it is a sentence the world could
     * not honour, which [wordsNothingHonours] charges.
     */
    private fun holds(composition: AgeComposition, aspect: Aspect, parameter: String): Boolean =
        parametersOf(composition, aspect).any { it.name == parameter }

    /** The parameter [aspect] calls [parameter], from wherever it is owned — see [parametersOf]. */
    private fun parameterNamed(composition: AgeComposition, aspect: Aspect, parameter: String): Parameter? =
        parametersOf(composition, aspect).firstOrNull { it.name == parameter }

    /**
     * Every parameter [aspect] holds — its seated presets' own, and the aspect's own where it seats nothing.
     *
     * Asked in one place because the two sources answer the same question: a preset owns its parameters where
     * there is a preset, and an aspect owns them where there is not. Reading only the first is how a
     * population's claims went nowhere the moment it stopped seating anything.
     */
    private fun parametersOf(composition: AgeComposition, aspect: Aspect): List<Parameter> =
        composition.presets.filter { it.aspect == aspect }.flatMap { it.ownParameters } + aspect.parameters

    /**
     * A parameter named at something that cannot honour it — charged as unbacked rather than ignored
     * (§3.3): the writer said something true of the language that this Age had no way to be.
     */
    private fun wordsNothingHonours(
        vocabulary: Vocabulary,
        composition: AgeComposition,
        aspect: Aspect,
        setting: List<Constraint>,
    ): List<Flaw> {
        val seated = composition.presets.filter { it.aspect == aspect }
        // Charged only where *every* seated preset ignores the word: one territory honouring it is enough.
        // An aspect that seats nothing honours its own parameters — a climate cannot ignore its temperature, and
        // a population cannot ignore what it was told to grow, there being nothing there to do the ignoring.
        fun anythingSeatedHonours(parameter: String) =
            seated.any { it.honoursParameterNamed(parameter) } || aspect.parameters.any { it.name == parameter }
        // And only where the word addressed this aspect's parameters at all — see [holds].
        val addressing = setting.filter { said -> said.word.canSet.keys.any { holds(composition, aspect, it) } }
        val wentUnheeded = addressing.filter { said -> said.word.canSet.keys.none(::anythingSeatedHonours) }
        return wentUnheeded.map { said -> flaw(vocabulary, Register.UNBACKED, listOf(said), aspect, emptyList(), said.word.firmness) }
    }

    /**
     * A seed-sized number standing for what was written. Order-insensitive: §3.5 rejected letting page
     * order decide anything, so it must not leak into the seed either.
     */
    private fun saltOf(sentence: List<Word>): Long =
        sentence.fold(0L) { salt, word -> salt xor (word.id.hashCode().toLong() * WORD_MIXER) }

    /**
     * **How strict this Age is being about one word in one aspect** — a cut drawn once, somewhere between
     * nothing and the word's own bars, and applied to every member the word is weighed against.
     *
     * What it buys is that the same word written twice gives two different worlds. A tag query was a pure
     * function of the word and the corpus, so `settled` reached the same eighteen structure sets at the
     * same amounts in every Age that ever said it; the seed decided the ground under them and nothing
     * about what stood on it.
     *
     * **Only the uncertain carriers move.** A member at or above its bar is admitted whatever is drawn — a
     * world of built things still gets the villages — while one carrying half of it arrives in half of
     * Ages. So the rule reads plainly: the bar is where a member *always* turns up, and below it a member
     * turns up in proportion to how close it came. What is drawn is the share of every bar this Age asks.
     *
     * **One cut for the whole aspect, not one per member**, which is what makes the variation read as
     * character rather than as confetti: this Age's `settled` was generous and took the odd ruin with it,
     * that one's was spare. Per-member rolls would give every Age the same bland middle.
     *
     * Keyed on the word's *id* and the aspect rather than on anything positional, so adding a word to the
     * corpus cannot reshuffle an Age already written.
     */
    private fun strictnessOf(draw: Long, aspect: Aspect, word: Word): Double {
        val key = draw xor (aspect.ordinal * ASPECT_STRIDE) xor
            (word.id.hashCode().toLong() * WORD_MIXER) xor STRICTNESS_SALT
        return XoroshiroRandomSource(key).nextDouble() * Bars.STRICT
    }

    /** A stable, unpredictable ordering key — how the seed arbitrates between equally precise words. */
    private fun tieBreak(draw: Long, aspect: Aspect, word: Word): Long =
        XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor (word.id.hashCode().toLong() * WORD_MIXER))
            .nextLong()

    private fun <T> List<T>.pairs(): List<Pair<T, T>> =
        indices.flatMap { left -> (left + 1..<size).map { right -> this[left] to this[right] } }
}
