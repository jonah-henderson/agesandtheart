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
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Sentence
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.resources.Identifier

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
) {
    val sentence: List<String> get() = words.map { it.name }
}

/**
 * Words in, a world out. Every word scores every preset in the aspects it may fill; precise words
 * *narrow* the candidates, vague words *tilt* the draw between whatever survived, and the seed picks.
 * There is no per-tier code path and no geometry anywhere.
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
/**
 * Where one member of a part of the world stands after one word has spoken — how strongly it is claimed,
 * and whether the word leaves it in at all.
 *
 * A catalogue's [strength] is what decides which single preset is seated; a population's is the share of
 * the world that member keeps. The two scales are not comparable across aspects and never need to be:
 * every reader of this is ranking one aspect's members against each other.
 */
data class Standing(val strength: Double, val kept: Boolean)

object Resolver {
    /**
     * How many ways one aspect may divide. A limit on legibility rather than machinery — region maps
     * handle any number. Words that do not fit are displaced and charged, never dropped.
     */
    private const val MOST_TERRITORIES = 3

    // A floor under every candidate, so an evocative word tilts the draw rather than deciding it.
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

    // How much more of the world naming a member asks for, on top of the ordinary share it already had.
    private const val A_MENTION_IS_WORTH = 1.0

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
     * **At most one request survives per parameter**, taken in the order two demands would be taken — tier
     * first, then the seed. Two offers on one parameter are not a quarrel the writer can be charged for, and
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
            val here = said.filter { aspect in reachOf(vocabulary, it) }
            val demanded = here.flatMap { it.word.setsIn(aspect).keys }.toSet()
            val asked = here.filter { it in requesting }
            for (parameter in asked.flatMap { it.word.requestsIn(aspect).keys }.distinct()) {
                if (parameter in demanded) continue
                val winner = asked.filter { parameter in it.word.requestsIn(aspect) }
                    .sortedWith(
                        compareByDescending<Constraint> { it.word.tier }
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
        val spent = sentence.phrases
            .filter { it.subject?.word?.mints != null }
            .flatMap { phrase -> phrase.modifiers.filter { it.word.sizeAsked != null } }
        val kept = sentence.constraints.filterNot { constraint -> spent.any { it === constraint } }
        val said = offered(vocabulary, kept.map { it.drawnAt(draw) }, draw)
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
        val spokenTo = said.flatMap { reachOf(vocabulary, it) }.toSet()
        val composition = mintedFeatures(resolved, sentence, draw).laidOver(template.world(), spokenTo)
        flaws += mintingsThatCannotHold(vocabulary, sentence, draw)
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
            // Drawn before it is read, exactly as the minting itself draws it: a material carrying a pool
            // chooses here too, and charging the undrawn word would price a page nobody was given.
            val substance = phrase.modifiers.map { it.drawnAt(draw) }
                .firstOrNull { it.word.material != null } ?: return@mapNotNull null
            if (flows(substance.word.material)) return@mapNotNull null
            // The material first: it is the word that lost, and `describe` names the first as displaced
            // and the second as what displaced it.
            flaw(vocabulary, Register.DISPLACED, listOf(substance, minting), Aspect.FEATURES, emptyList(), substance.word.tier)
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
     * [composition] with every feature the sentence **minted** added to what the Age places — `ink springs`,
     * `gold block veins` (world model §2).
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
            // Drawn, like every other reader of a word's claims: a material carrying a pool chooses here
            // too, and this is the one place that read the undrawn sentence instead.
            //
            // **And a pattern named alone is still made of something.** `obelisks` used to mint nothing at
            // all and put nothing in the ground, which reads as the word not working; `Word.unstated` is
            // what the pattern is made of when nobody says, and a tag there is a small pool the seed
            // draws from where a bare id is one answer.
            val substance = phrase.modifiers.firstNotNullOfOrNull { it.drawnAt(draw).word.material }
                ?: subject.word.unstated
                ?: return@mapNotNull null
            Claim(
                pattern,
                subject.polarity,
                Rung.legible(subject.density),
                subject.confinedTo,
                madeOf = substance,
                size = phrase.modifiers.firstNotNullOfOrNull { it.word.sizeAsked },
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
        // **A moved evocative page is free** (§4.3.1). `rehomed` charges for an aiming a writer could not
        // see was wrong; an evocative word has exactly one place it can go, so there was no choice to get
        // wrong and nothing to diagnose.
        sentence.written.filter { it.rehomed && it.word.tier.narrows }.map { said ->
            val landedIn = reachOf(vocabulary, said).firstOrNull()
            flaw(vocabulary, Register.REHOMED, listOf(said), landedIn, tags = emptyList(), tier = said.word.tier)
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
            val tier = vocabulary.word(page)?.tier
            Flaw(
                Register.IMPOSSIBLE,
                listOf(page),
                aspect = null,
                tags = emptyList(),
                Register.IMPOSSIBLE.charge(tier, vocabulary.earnedBy(Register.IMPOSSIBLE)),
            )
        }

    /**
     * Which aspects a constraint speaks to — the grammar's answer, not a search (§4.3.1). "Anywhere"
     * resolves against [purchaseFor], which is where a word finds purchase.
     */
    private fun reachOf(vocabulary: Vocabulary, constraint: Constraint): List<Aspect> =
        if (constraint.word.tier.narrows) constraint.aimedAt.sortedBy { it.ordinal }
        else purchaseFor(vocabulary, constraint.word)

    /**
     * What fills one aspect: one preset, or several where the sentence left it no way to be one thing.
     *
     * Three steps. Ask each narrowing word which presets it would keep; gather those into **territories**,
     * groups of words satisfiable together; then draw one preset per territory, tilted by evocative words.
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

        val speaking = sentence.filter { aspect in reachOf(vocabulary, it) }
        // **What the sentence put into the pool**, which is the one step that can widen it. Per sentence
        // rather than per corpus: a member one word admits is in *this* Age's draw and nobody else's.
        val pool = vocabulary.askableIn(aspect) +
            speaking.flatMap { it.word.admitsIn(aspect) }.distinct().mapNotNull(aspect::presetFor)
        // Most precise first; where precision ties the seed decides, never word order. A word that only
        // sets a parameter narrows nothing, having no opinion about *which* preset fills the aspect.
        val narrowing = speaking.filter { it.word.tier.narrows && it.word.constrainsPresetsIn(aspect) }
            .sortedWith(compareByDescending<Constraint> { it.word.tier }.thenBy { tieBreak(draw, aspect, it.word) })

        val territories = mutableListOf<Territory>()
        // **A word that also steers this aspect settles last, and never fractures it** (Jonah,
        // 2026-09-01). A reusable word carries several senses and only has to land one of them: where
        // `colossal islands landmass` cannot have both the monumental landform its query asks for and the
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
                flaws += flaw(vocabulary, Register.UNBACKED, listOf(said), aspect, emptyList(), said.word.tier)
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

        // A spatial aspect can honour several answers by giving each its own ground; a singular one has
        // nowhere to put a second, which is where the harsher register earns its place (§3.4).
        val room = if (aspect.spatial) MOST_TERRITORIES else 1
        val kept = territories.take(room)
        chargeForContention(vocabulary, aspect, kept, territories.drop(room), flaws)

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
        for (territory in kept.drop(1)) {
            val contender = territory.words.first()
            // A division the writer asked for costs nothing: `and` means "keep both, and keep them apart".
            if (wereJoined(contender, leading)) continue
            flaws += flaw(
                vocabulary,
                Register.FRACTURE,
                listOf(contender, leading),
                aspect,
                opposedTags(vocabulary, contender, leading),
                maxOf(contender.word.tier, leading.word.tier),
            )
        }
        for (said in lost.flatMap { it.words }) {
            flaws += flaw(
                vocabulary,
                Register.DISPLACED,
                listOf(said, leading),
                aspect,
                opposedTags(vocabulary, said, leading),
                said.word.tier,
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
        // A word this exact about *the preset* forbids company; one that merely sets a parameter does not,
        // or naming a material would quietly suppress harmony everywhere. Asked of the tier's own
        // strictness rather than of its name, so a word stating those numbers behaves as one.
        val pinned = speaking.any { it.word.tier.leavesNoRoomForCompany && it.word.constrainsPresetsIn(aspect) }
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
        val shares = Share.findable(claims.map { Share.legible(it / strongest) })
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
        val claimed = speaking.filter { it.word.tier.narrows }
            .maxOfOrNull { it.word.claimOn(preset, tags) * it.word.tier.weight } ?: 0.0
        // **Every tier leans**, which is the whole of the last step: a lean is not a filter, so nothing
        // about it depends on whether the word that made it also narrowed.
        val leaned = speaking.sumOf { it.word.biasOn(preset, tags) }
        return (claimed + leaned).coerceAtLeast(0.0)
    }

    /**
     * How strong a claim the sentence makes on one preset — the number that decides which preset is drawn.
     *
     * Three terms: a **base** scaled by readiness, so an unasked-for draw prefers the ordinary (§3.3); the
     * strongest **pull** of any narrowing word, weighted by precision; and the summed **affinity** of the
     * evocative words, which may be negative, so "beautiful" pushes lava away as surely as it pulls
     * flowers in.
     */
    private fun strengthOf(
        vocabulary: Vocabulary,
        preset: Taggable,
        speaking: List<Constraint>,
        aspect: Aspect,
    ): Double {
        val tags = vocabulary.tagsOf(preset)
        val claimed = speaking.filter { it.word.tier.narrows }
            .maxOfOrNull { it.word.claimOn(preset, tags) * it.word.tier.weight } ?: 0.0
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
     */
    private fun capabilityFactor(preset: Taggable, speaking: List<Constraint>): Double {
        val parametersAsked = speaking.flatMap { it.word.setsIn(preset.aspect).keys }.distinct()
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
     * Where [word] finds purchase — the aspects it is about, or every one it likes something in.
     *
     * A narrowing word never asks this: the section its page was laid in already decided, and
     * [Constraint.aimedAt] carries the answer. So this is the evocative half alone.
     */
    fun purchaseFor(vocabulary: Vocabulary, word: Word): List<Aspect> {
        // **The tier decides, not an empty reach.** This used to read "declares no aspect" as "means
        // everywhere", which held only while a word could declare one at all: now that the reach is
        // derived, `beautiful` reaches the climate it bends and the biomes it weighs, and reading that as
        // its whole purchase stopped it being beautiful anywhere else — one Age over fifty seeds.
        if (word.tier.narrows) return word.aspects.sortedBy { it.ordinal }
        // Spanning aspects is what makes a word evocative.
        return Aspect.entries.filter { aspect ->
            val likesSomethingThere = vocabulary.askableIn(aspect)
                .any { word.biasOn(it, vocabulary.tagsOf(it)) > 0.0 }
            // **And it reaches an aspect whose parameters it bends**, which is the only way into one with no
            // candidates to like. The declaration is both the mechanism and the evidence, so §4.4's charge
            // per aspect constrained stays honest with no tag table propping it up.
            val bendsADialThere = aspect.parameters.any { it.name in word.canSet }
            likesSomethingThere || bendsADialThere
        }
    }

    /**
     * Which aspects [word] is **charged for** (§4.4) — a different question from where it reaches, and the
     * one that was being answered by the same function.
     *
     * A narrowing word has its say in one part of the world at a time, so it is priced in one however many
     * it is at home in; an evocative word is priced across everything it found purchase in.
     */
    fun pricedIn(vocabulary: Vocabulary, word: Word): List<Aspect> =
        if (word.tier.narrows) listOfNotNull(word.aspects.minByOrNull { it.ordinal })
        else purchaseFor(vocabulary, word)

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
            val shared = reachOf(vocabulary, first).intersect(reachOf(vocabulary, second).toSet())
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

    /** What they disagreed over — for the explanation, not the detection. */
    private fun opposedTags(vocabulary: Vocabulary, first: Constraint, second: Constraint): List<String> =
        vocabulary.disagreement(first.word, second.word)?.over ?: emptyList()

    private fun flaw(
        vocabulary: Vocabulary,
        register: Register,
        said: List<Constraint>,
        aspect: Aspect?,
        tags: List<String>,
        tier: Tier,
    ) = Flaw(register, said.map { it.word.name }, aspect, tags, register.charge(tier, vocabulary.earnedBy(register)))

    /**
     * The composition these fillings describe. The stand-in terrain is overwritten immediately — every
     * aspect that *has* presets holds at least one — and exists only because a composition needs one.
     */
    private fun compose(filled: Map<Aspect, List<Filling>>): AgeComposition {
        var composition = AgeComposition(terrains = listOf(Terrain.SHAPES))
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
            val setting = sentence.filter { it.word.canSet.isNotEmpty() && aspect in reachOf(vocabulary, it) }
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
                    .sortedWith(compareByDescending<Constraint> { it.word.tier }.thenBy { tieBreak(draw, aspect, it.word) })
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
        val speaking = setting.filter { said -> axes.any { it in said.word.setsIn(aspect) } }
            .sortedWith(compareByDescending<Constraint> { it.word.tier }.thenBy { tieBreak(draw, aspect, it.word) })
        if (speaking.isEmpty()) return this

        /** What a word *demands* of each axis — the only form that can put two words at odds. */
        fun boundsIn(said: Constraint): Map<String, Span> = axes.mapNotNull { axis ->
            (said.word.setsIn(aspect)[axis]?.let(Setting::read) as? Setting.Fixed)?.let { axis to it.span }
        }.toMap()

        /**
         * Everything a word asks that is *not* a demand — its limits, nudges and spreads.
         *
         * Kept out of the grouping above on purpose. Only a demand can refuse another word, so only demands
         * decide who agrees with whom and who fractures a world; a floor yields to any band already inside
         * it and a nudge cannot fail at all. These settle onto whatever the demands left (see [Setting]).
         */
        fun askingIn(said: Constraint): List<Pair<String, Setting>> = axes.mapNotNull { axis ->
            val asked = said.word.setsIn(aspect)[axis]?.let(Setting::read) ?: return@mapNotNull null
            if (asked is Setting.Fixed) null else axis to asked
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

        // **Narrowing words bound; evocative words bend** (§4.4). Only the first kind may divide a world:
        // an evocative word removes no freedom, so it can never fail, and a fracture is a failure.
        val bounding = speaking.filter { it.word.tier.narrows }
        val bending = speaking.filter { !it.word.tier.narrows }

        /** Where the evocative words would like [axis] to sit, in the axis's own terms. */
        fun preferred(axis: String): Double? {
            val wants = bending.mapNotNull { boundsIn(it)[axis] }
            if (wants.isEmpty()) return null
            return wants.map { (it.least + it.most) / 2.0 }.average()
        }

        /**
         * [bounds] with each axis's middle pulled toward what the evocative words asked for — including an
         * axis nobody bounded, which is then the whole natural range with its weight moved rather than a
         * stretch of it. That is what lets `beautiful` lean an Age temperate without narrowing it at all.
         */
        fun bentTo(bounds: Map<String, Span>): Map<String, Span> {
            val touched = bounds.keys + bending.flatMap { boundsIn(it).keys }
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
        // ordered by tier, which is nobody's intent; a clause's ranged axes have to land on the same member
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
         * After the bend, so an evocative word's pull on the middle survives a nudge to the ends, and
         * after the grouping, so a word that only leans never divided anything. An axis nobody demanded
         * but somebody nudged starts from the whole natural range, which is what lets a word lean an Age
         * warm without narrowing it at all.
         *
         * A limit that cannot be met at all loses rather than failing the Age: it is the weaker claim, and
         * the demand it argues with was already priced when the groups were formed.
         */
        fun settledWith(bounds: Map<String, Span>): Map<String, Span> {
            val asking = speaking.flatMap(::askingIn)
            if (asking.isEmpty()) return bounds
            return (bounds.keys + asking.map { it.first }).associateWith { axis ->
                val band = bounds[axis] ?: Span.NATURAL
                val onThisAxis = asking.filter { it.first == axis }.map { it.second }
                Setting.settle(listOf(Setting.Fixed(band)) + onThisAxis) ?: band
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
                    flaws += flaw(vocabulary, Register.DISPLACED, listOf(group.first(), leading), aspect, emptyList(), group.first().word.tier)
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
        for (group in groups.drop(1)) {
            val contender = group.first()
            // A division the writer asked for costs nothing — `and` means "keep both, and keep them
            // apart", and a clause per member says it another way and returned above.
            if (wereJoined(contender, leading)) continue
            flaws += flaw(
                vocabulary,
                Register.FRACTURE,
                listOf(contender, leading),
                aspect,
                opposedTags(vocabulary, contender, leading),
                maxOf(contender.word.tier, leading.word.tier),
            )
        }
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
            val speaking = sentence.filter { aspect in reachOf(vocabulary, it) }
            if (speaking.isEmpty()) continue
            // **What the sentence put in**, the same widening a catalogue's draw gets. A member named — by
            // being chosen or by being admitted — was reaching nothing here, since this drew from curation
            // alone, and curation is exactly what a writer naming a biome outright is reaching past.
            fun namedBy(said: Constraint) =
                said.word.admitsIn(aspect) + listOfNotNull(said.word.choiceIn(aspect)?.key)
            val curated = vocabulary.askableIn(aspect)
            val drawnFrom = (
                curated + speaking.flatMap(::namedBy).distinct().mapNotNull(aspect::presetFor)
                ).distinct()
            val reached = drawnFrom.mapNotNull { member ->
                claimForMember(vocabulary, member, pool, speaking, aspect, member in curated, draw)
            }
            if (reached.isEmpty()) continue
            flaws += crowdedOutOfAnOnly(vocabulary, speaking, aspect)
            val settled = weighed.optionsFor(aspect, 0).allOf(pool)
            weighed = weighed.withOptions(aspect, pool.name, (settled + spelled(reached, pool, drawnFrom)).distinct())
        }
        return weighed
    }

    /**
     * How much of the world one member of a population should have, against what it would have had anyway
     * — or null where the sentence said nothing that reaches it.
     *
     * Three tiers, three readings, and the difference between them is the design's own (§3.3): an
     * **evocative** word tilts by how well the member answers it, signed, so "beautiful" thins the ash
     * flats as surely as it thickens the flower meadows; a **restrictive** word bears down on the members
     * that qualify at its threshold; and a member is never *removed* by either, since a word that merely
     * likes something is not an instruction to delete anything ([BiomePreference.LEAST_KEPT]).
     *
     * `only` and `except` are the exception, and deliberately so: those are the writer saying outright
     * what to keep and what to strike, rather than what to prefer.
     */
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
        val claim = claimForMember(vocabulary, member, pool, said, aspect, member in vocabulary.askableIn(aspect), draw = null)
            ?: return Standing(Rung.ORDINARY, kept = true)
        return Standing(claim.density, kept = claim.polarity != Polarity.EXCEPT)
    }

    private fun claimForMember(
        vocabulary: Vocabulary,
        member: Taggable,
        pool: Pool,
        speaking: List<Constraint>,
        aspect: Aspect,
        alreadyInThePool: Boolean,
        /** The Age being written, or null to ask what a word reaches in *every* Age — see [strictnessOf]. */
        draw: Long?,
    ): Claim? {
        val tags = vocabulary.tagsOf(member)
        // **A word that takes a member out takes it out.** Excluding is the pipeline's own removal, so it
        // does not have to argue the weight down to nothing — which is the only way `untouched` can mean
        // anything, there being no tag for the absence of a thing to put on the members that are present.
        if (speaking.any { it.word.tier.narrows && it.word.excludes(member, tags) }) {
            return Claim(member.key, Polarity.EXCEPT)
        }
        // **Claiming something here is the price of insisting.** A word that only leans restricts nothing,
        // and `acceptsOn` keeps every member where nothing was restricted — read as insistence that would
        // be a word demanding the whole population it merely had a preference within.
        // **How strict this Age is being, which only a plain mention is subject to.** `only` and `except`
        // are the writer saying outright what to keep and what to strike, so they are read at the tier's
        // own threshold however generous the Age: a preference may be lucky, an instruction may not.
        fun strictnessFor(said: Constraint): Double =
            if (draw == null || said.polarity != Polarity.ASSERTED) {
                said.word.tier.threshold
            } else {
                strictnessOf(draw, aspect, said.word)
            }
        val insisting = speaking.filter { said ->
            val narrowsHere = said.word.tier.narrows && said.word.constrainsPresetsIn(aspect)
            narrowsHere && said.word.acceptsOn(member, tags, strictnessFor(said))
        }
        // **What a tier weighs is a tag query.** Naming a member is step one and stands on its own, so
        // scoring it here too made every named member arrive at the ceiling — a share no rung could move
        // and no second word could add to.
        val insisted = insisting.sumOf { it.word.pullIn(aspect, tags) * it.word.tier.weight }
        // **Naming a member asks for more of it**, which is the whole of what naming one does to a
        // population: every member of a curated pool is present anyway, so a mention that claimed only
        // the ordinary share would be a page read, charged, and worth nothing.
        val mentions = speaking.count { it.word.choiceIn(aspect)?.key == member.key }
        val mentioned = mentions * A_MENTION_IS_WORTH
        // **Every tier leans**, as it does for a catalogue. This counted an evocative word's lean and a
        // narrowing word's *dislike*, and dropped a narrowing word's liking on the floor — so `rich`
        // leaning the ores toward diamond did nothing at all while its dislike of barren bit.
        val leaned = speaking.sumOf { said ->
            val by = said.word.biasOn(member, tags)
            if (said.word.tier.narrows) by * said.word.tier.weight else by
        }
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
        if (weight <= NONE_OF_IT) return Claim(member.key, Polarity.EXCEPT)
        // **Ordinary is only silence for a member the pool already had.** One a word put there by name
        // arrives at ordinary standing and dropping the claim would drop the admission with it — the
        // member would be reached, weighed, and then quietly left out of the world it was named into.
        val nothingToSay = polarity == null && Rung.isOrdinary(weight) && alreadyInThePool
        if (nothingToSay) return null
        // **Described rather than named**, which decides whether this asks for the thing or for more of
        // it where it already is (world model §3). A member reached only by a query is a description; one
        // a writer mentioned is a naming. `only` and `except` are neither — they are instructions about
        // what the Age holds — so they are left to mean what they always did.
        val described = mentions == 0 && polarity == null
        return Claim(
            member.key,
            polarity ?: Polarity.ASSERTED,
            weight,
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
    private fun spelled(claims: List<Claim>, pool: Pool, drawnFrom: List<Taggable>): List<String> {
        val emptied = pool.emptiedBy ?: return claims.map { it.spelled() }
        val everythingStruck = claims.size == drawnFrom.size && claims.all { it.polarity == Polarity.EXCEPT }
        return if (everythingStruck) listOf(emptied) else claims.map { it.spelled() }
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
        for (group in groups.drop(1)) {
            val contender = group.first()
            // The same exemption [chargeForContention] makes: a writer who joined them asked for both.
            if (wereJoined(contender, leading)) continue
            flaws += flaw(
                vocabulary,
                Register.FRACTURE,
                listOf(contender, leading),
                aspect,
                opposedTags(vocabulary, contender, leading),
                maxOf(contender.word.tier, leading.word.tier),
            )
        }
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
        val crowdedOut = contenders.filter { it.polarity == Polarity.ASSERTED && !joinedToAnythingSingledOut(it) }
        return crowdedOut.map { said ->
            flaw(vocabulary, Register.DISPLACED, listOf(said, singledOut.first()), aspect, emptyList(), said.word.tier)
        }
    }

    /**
     * A predicative parameter with more than one claimant — where `and` earns its place (§3.2).
     *
     * A parameter holds one answer, so unjoined claims contend: the most precise wins, the seed breaks a
     * tie, and every loser is charged as displaced. Joining them changes what was asked rather than who
     * wins — "blackstone **and** tuff" is one rock made of both. Only the winner's own group joins it.
     *
     * **Two words asking for the same thing are not contending**, which is the rule [agree] already
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
                flaws += flaw(vocabulary, Register.DISPLACED, listOf(loser, winner), aspect, emptyList(), loser.word.tier)
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
     * first, and ranking them by tier and then by a seeded tie-break is right: it spreads two Ages written
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

    /**
     * Whether [parameter] accumulates rather than contends, asked of the presets seated in [aspect] (§3.2).
     * Unknown to all of them counts as predicative: accumulating values for a parameter that does not exist
     * would make a typo look deliberate.
     */
    private fun isPopulative(composition: AgeComposition, aspect: Aspect, parameter: String): Boolean =
        parameterNamed(composition, aspect, parameter)?.holds == Holds.WEIGHTED_SET

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
        return wentUnheeded.map { said -> flaw(vocabulary, Register.UNBACKED, listOf(said), aspect, emptyList(), said.word.tier) }
    }

    /**
     * A seed-sized number standing for what was written. Order-insensitive: §3.5 rejected letting page
     * order decide anything, so it must not leak into the seed either.
     */
    private fun saltOf(sentence: List<Word>): Long =
        sentence.fold(0L) { salt, word -> salt xor (word.id.hashCode().toLong() * WORD_MIXER) }

    /**
     * **How strict this Age is being about one word in one aspect** — a cut drawn once, somewhere between
     * nothing and the tier's own threshold, and applied to every member the word is weighed against.
     *
     * What it buys is that the same word written twice gives two different worlds. A tag query was a pure
     * function of the word and the corpus, so `settled` reached the same eighteen structure sets at the
     * same amounts in every Age that ever said it; the seed decided the ground under them and nothing
     * about what stood on it.
     *
     * **Only the uncertain carriers move.** A member answering at or above the tier's threshold is
     * admitted whatever is drawn — a world of built things still gets the villages — while one answering
     * at half of it arrives in half of Ages. So the rule reads plainly: the threshold is where a member
     * *always* turns up, and below it a member turns up in proportion to how close it came.
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
        return XoroshiroRandomSource(key).nextDouble() * word.tier.threshold
    }

    /** A stable, unpredictable ordering key — how the seed arbitrates between equally precise words. */
    private fun tieBreak(draw: Long, aspect: Aspect, word: Word): Long =
        XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor (word.id.hashCode().toLong() * WORD_MIXER))
            .nextLong()

    private fun <T> List<T>.pairs(): List<Pair<T, T>> =
        indices.flatMap { left -> (left + 1..<size).map { right -> this[left] to this[right] } }
}
