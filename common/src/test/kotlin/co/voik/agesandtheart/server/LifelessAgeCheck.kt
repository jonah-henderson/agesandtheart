package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * An Age that says nothing lives here has nothing living in it — **including what its chunks were made with.**
 *
 * There are two spawn paths and `Spawns.LIVES` only ever reached one. The runtime spawner asks the
 * generator through `ChunkGenerator.getMobsAt`, which is our seam; chunk generation calls
 * `NaturalSpawner.spawnMobsForChunkGeneration`, which takes the biome holder, reads its own mob settings,
 * and never consults the generator at all. So an Age on vanilla's rock was born with vanilla's animals in
 * it whatever its sentence said, and `/age spawns` answered "nothing at all, in any pass" beside five
 * horses standing in the field.
 *
 * **A server check because only a generated chunk can show it.** `SpawnsCheck` exercises `Spawns.livingIn`,
 * which was right the whole time; what was wrong is that one caller never asked it.
 *
 * Two things make it honest, and the check was wrong without either. **A control**: the same seed saying
 * nothing about life has to show creatures before the lifeless one is believed, or an empty count means
 * only that nothing generated. And **waiting for the chunks**: a forceload is answered long before it is
 * done, so both Ages are polled with `if loaded` until the ground is really there. Read too early, this
 * passed with the fix taken out.
 */
@Tags(NEEDS_SERVER)
class LifelessAgeCheck : FunSpec({

    val server = DrivenServer.shared

    fun inThe(age: String, command: String) = server.run("execute in agesandtheart:$age run $command")

    /** Whether the corners of what was forced are really in memory, rather than merely asked for. */
    fun isLoaded(age: String): Boolean = listOf(FAR, FAR + REACH).all { x ->
        listOf(FAR, FAR + REACH).all { z ->
            inThe(age, "execute if loaded $x $PROBE_HEIGHT $z run time query gametime").any(Char::isDigit)
        }
    }

    /** Living things in [age], by the count `tag` reports back — a count that changes nothing. */
    fun creaturesIn(age: String): Int {
        val said = inThe(age, "tag @e[type=!minecraft:player,type=!minecraft:item] add counted")
        return COUNT.find(said)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * **The runtime spawner off, or it answers for everything.** It fills a loaded chunk in seconds, so a
     * count taken with it running measures `getMobsAt` — which was never the broken path — and says
     * nothing at all about what the chunk was made with.
     *
     * **That was said in a comment and done nowhere**, which is where this check's flakiness came from: it
     * forceloads and then polls for up to a minute, so on a loaded machine the chunks tick long enough for
     * the spawner to fill BOTH Ages and the difference this exists to measure is swamped. Two runs of four
     * failed, at 287 against 274 and 330 against 326 — two worlds equally full rather than one empty.
     *
     * Put back in `afterSpec` rather than at the end of the test, because the server is shared with every
     * other spec and a failing check must not leave the world without its mobs.
     */
    beforeSpec { server.run("gamerule doMobSpawning false") }
    afterSpec { server.run("gamerule doMobSpawning true") }

    test("an Age nothing lives in is generated with nothing living in it") {
        val written = mapOf(ALIVE to SAYS_NOTHING_OF_LIFE, LIFELESS to "deserted age")
        for ((name, sentence) in written) {
            val said = server.run("age write $name $SEED $sentence")
            check(said.contains("Created Age")) { "'$sentence' was not written: $said" }
            // **Forced rather than `/age gen`**, and that is the whole of what makes this reproduce: the
            // pass runs as a chunk climbs to `ChunkStatus.SPAWN`, which is the pipeline a ticket drives.
            // `gen` leaves it out, so a check built on it measured the runtime spawner and nothing else.
            inThe(name, "forceload add $FAR $FAR ${FAR + REACH} ${FAR + REACH}")
        }

        // **The two have to be shaped alike**, or this compares two different worlds. Neither sentence
        // claims the ground, so both draw it from the one seed — but a sentence that failed to parse is
        // *repaired* into a whole random book, which is how this first came to be comparing a vanilla
        // field against a pyramid. And it must be vanilla's rock either way: our own shapes disable
        // chunk-generation spawning in their settings, so the pass this guards would never run.
        val listed = server.run("age list")
        // **A book that does not parse is repaired into a whole random one**, which is what `/age write`
        // is meant to do and is ruinous here: the check spent two runs comparing a vanilla field against a
        // pyramid that had nothing to do with either sentence. The recipe has to say the word back.
        for ((name, sentence) in written) {
            val recipe = listed.lines().firstOrNull { name in it } ?: listed
            val said = SENTENCE.find(recipe)?.groupValues?.get(1).orEmpty()
            check(said.split(" ").size <= sentence.split(" ").size) {
                "'$sentence' did not parse and was repaired into '$said', so this compares nothing"
            }
        }
        val ground = written.keys.associateWith { name ->
            val recipe = listed.lines().firstOrNull { name in it } ?: listed
            GROUND.findAll(recipe).joinToString(" ") { it.value }
        }
        check(ground.values.distinct().size == 1) {
            "the two Ages were not shaped alike, so this compares two worlds: $ground"
        }
        check(ground.values.all { "landmass=vanilla" in it }) {
            "not on vanilla's rock, so the pass this guards never runs: $ground"
        }

        var waited = 0
        while (waited < TRIES && !written.keys.all(::isLoaded)) {
            Thread.sleep(BETWEEN_TRIES)
            waited++
        }
        check(waited < TRIES) { "the forced chunks never loaded, so this check cannot see what it guards" }

        val living = creaturesIn(ALIVE)
        val lifeless = creaturesIn(LIFELESS)
        for (name in written.keys) inThe(name, "forceload remove all")
        check(living > 0) {
            "'$SAYS_NOTHING_OF_LIFE' grew no creatures either, so this check cannot see what it guards"
        }
        check(lifeless == 0) {
            "'deserted' left $lifeless creatures where its chunks were made, beside $living in the same " +
                "seed that said nothing of life"
        }
    }
})

/** One seed for both, so the two Ages differ by one word and in nothing else. */
private const val SEED = 4786177076396069349L

/** A sentence that says something, claims no landmass, and says nothing whatever about what lives there. */
private const val SAYS_NOTHING_OF_LIFE = "age clear sky"

private const val ALIVE = "stillalive"
private const val LIFELESS = "lifeless"

/** How wide a square of chunks to force — the pass tries about a tenth of them, so a few is not enough. */
private const val REACH = 200

/** Far from the origin, where nothing has been generated by anything else. */
private const val FAR = 5000

/** Above any sea floor and below any ceiling, so `if loaded` is asked somewhere a column exists. */
private const val PROBE_HEIGHT = 64

private const val TRIES = 120
private const val BETWEEN_TRIES = 500L

private val COUNT = Regex("to (\\d+) entit")

/** What `/age list` recorded as the book, which is a repaired one where the book did not parse. */
private val SENTENCE = Regex("\u2014 \"([^\"]*)\"")

/** What a recipe says about the ground, which the two Ages must agree on to be comparable. */
private val GROUND = Regex("(landmass|rock|underground)=\\S+")
