package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * A creature an Age wrote something of vanilla's own about — a creeper charged, a sheep's wool — comes into it so,
 * and one in an Age that said nothing of it does not. A server check because what was written is laid on as the
 * creature enters the level, which only a level can show.
 */
@Tags(NEEDS_SERVER)
class WrittenCreaturesOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    fun inThe(age: String, command: String) = server.run("execute in agesandtheart:$age run $command")

    /** Whether a [creature] summoned into a fresh Age written as [sentence] answers to [nbt]. */
    fun summoned(age: String, sentence: String, creature: String, nbt: String): Boolean {
        server.run("age write $age $SEED $sentence")
        inThe(age, "forceload add $FAR $FAR")
        server.waitUntil("the chunk a $creature is summoned into loading in '$age'") {
            inThe(age, "execute if loaded $FAR $PROBE_HEIGHT $FAR").startsWith("Test passed")
        }
        inThe(age, "summon minecraft:$creature $FAR $PROBE_HEIGHT $FAR {NoAI:1b}")
        val answers = inThe(age, "execute if entity @e[distance=0..,type=minecraft:$creature,nbt=$nbt]")
        inThe(age, "forceload remove all")
        return answers.startsWith("Test passed")
    }

    test("a creeper summoned into a charged-creeper Age is powered, and one elsewhere is not") {
        check(!summoned(UNCHARGED, "age creeper", "creeper", POWERED)) { "a creeper came powered where nothing charged it" }
        check(summoned(CHARGED, "age charged creeper", "creeper", POWERED)) { "a creeper in 'charged creeper' came unpowered" }
    }

    test("a teeming Age holds more of every pass, and an ordinary one holds vanilla's") {
        fun capsIn(age: String, sentence: String): Map<String, Double> {
            server.run("age write $age $SEED $sentence")
            val caps = server.ask("spawns", "$age $NO_CENSUS").getAsJsonArray("cap") ?: return emptyMap()
            return caps.associate { it.asJsonObject.get("pass").asString to it.asJsonObject.get("times").asDouble }
        }
        val teeming = capsIn(TEEMING, "age teeming inhabitants")
        check(teeming["monster"] == TEEMING_RUNG && teeming["creature"] == TEEMING_RUNG) { "'teeming inhabitants' held $teeming" }
        check(capsIn(ORDINARY, "age clear sky").isEmpty()) { "an Age that said nothing of life moved a cap" }
    }

    test("a witch hut in an incongruous Age offers its witches half the time, and everything else the other half") {
        server.run("age write $INCONGRUOUS $SEED age overworld landmass incongruous inhabitants clear sky")
        val located = inThe(INCONGRUOUS, "locate structure minecraft:swamp_hut")
        val (x, z) = checkNotNull(Regex("""\[(-?\d+), ~, (-?\d+)]""").find(located)) { "no swamp hut was found: $located" }
            .destructured.toList().map { it.toInt() + INTO_THE_HUT }
        fun monstersAt(y: Int): Map<String, Int> {
            val offered = server.ask("spawns", "$INCONGRUOUS at $x $y $z").getAsJsonArray("offered") ?: return emptyMap()
            val monsters = offered.map { it.asJsonObject }.firstOrNull { it.get("pass").asString == "monster" } ?: return emptyMap()
            return monsters.getAsJsonArray("creatures").associate { entry ->
                val (creature, weight) = entry.asString.split(" x")
                creature to weight.toInt()
            }
        }
        val inside = (HUT_LOWEST..HUT_HIGHEST step 2).map(::monstersAt).firstOrNull { (it["minecraft:witch"] ?: 0) > WITCHES_ANYWHERE }
        checkNotNull(inside) { "no height in the swamp hut at $x $z offered more witches than anywhere else does" }
        val witches = inside.getValue("minecraft:witch")
        check(witches * 2 == inside.values.sum() && inside.size > 1) { "the swamp hut offered $inside" }
    }

    test("a herd of golems arrives together and spread out, and a golem unherded as the corpus says") {
        fun arrivalIn(age: String, sentence: String): List<Triple<Int, Int, Int>> {
            server.run("age write $age $SEED $sentence")
            inThe(age, "forceload add $GOLEM_GROUND $GOLEM_GROUND")
            server.waitUntil("the chunk golems arrive in loading in '$age'") {
                inThe(age, "execute if loaded $GOLEM_GROUND $PROBE_HEIGHT $GOLEM_GROUND").startsWith("Test passed")
            }
            val answer = server.ask("spawns", "$age arrive minecraft:iron_golem $GOLEM_GROUND $GOLEM_GROUND")
            inThe(age, "forceload remove all")
            val arrived = checkNotNull(answer.getAsJsonArray("arrived")) { "no golem arrived in '$age': $answer" }
            return arrived.map { it.asJsonObject }.map { Triple(it.get("x").asInt, it.get("y").asInt, it.get("z").asInt) }
        }
        val herd = arrivalIn(HERDED_GOLEMS, "age overworld landmass herds of iron_golem")
        // A member that finds no room beside the first is left out, so a herd may come short of the four it drew.
        check(herd.size > GOLEMS_TOGETHER_UNHERDED && herd.size <= HERD_AT_MOST) { "a herd of golems came ${herd.size} together: $herd" }
        check(herd.distinct().size > 1) { "a herd of golems stood on one block: $herd" }
        val alone = arrivalIn(LONE_GOLEMS, "age overworld landmass iron_golem")
        check(alone.size <= GOLEMS_TOGETHER_UNHERDED) { "golems unherded came ${alone.size} together" }
    }

    test("a sheep summoned into a black-sheep Age is black") {
        check(summoned(BLACK_SHEEP, "age black sheep", "sheep", BLACK_WOOL)) { "a sheep in 'black sheep' came another colour" }
    }
})

private const val SEED = 4786177076396069349L
private const val CHARGED = "chargedcreepers"
private const val UNCHARGED = "plaincreepers"
private const val BLACK_SHEEP = "blacksheep"
private const val TEEMING = "teemingspawns"
private const val ORDINARY = "ordinaryspawns"
private const val INCONGRUOUS = "incongruoushuts"
private const val HERDED_GOLEMS = "herdedgolems"
private const val LONE_GOLEMS = "lonegolems"

/** The most a herd brings, and the most golems `art/spawning.json` brings together. */
private const val HERD_AT_MOST = 6
private const val GOLEMS_TOGETHER_UNHERDED = 2

/** Dry, level ground at [SEED] on an overworld landmass, with room around it for a herd. */
private const val GOLEM_GROUND = 400

/** From where `locate` puts a swamp hut to inside it, and the heights its room could stand at. */
private const val INTO_THE_HUT = 3
private const val HUT_LOWEST = 56
private const val HUT_HIGHEST = 90

/** What a swamp offers witches by, out of any structure. */
private const val WITCHES_ANYWHERE = 5

/** What `teeming` asks of every creature. */
private const val TEEMING_RUNG = 4.0

/** A census of no chunks around the origin, so the caps come back without generating any. */
private const val NO_CENSUS = 0
private const val POWERED = "{powered:1b}"

/** Vanilla's saved wool colour for black, the sixteenth dye. */
private const val BLACK_WOOL = "{Color:15b}"

/** Far from the origin, where nothing has been generated by anything else. */
private const val FAR = 5000
private const val PROBE_HEIGHT = 64
