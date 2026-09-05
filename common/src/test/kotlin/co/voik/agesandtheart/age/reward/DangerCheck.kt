package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Price
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.Spreads
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That an Age is scored for what it is, and that the rules §7.7 calls load-bearing hold.
 *
 * **Every composition is built by hand and scored against a table written here**, never the shipped one.
 * That separation is the point: `art/danger.json` is meant to be retuned by play, and a check asserting
 * its numbers would turn every tuning pass into a broken build. What the shipped file has to satisfy is
 * [DangerTableCheck].
 *
 * The registries are needed for one reason only, and it is a good one: naming a block in a material
 * parameter goes through `Materials.makesAWorld`, which asks the block registry whether the thing is
 * something a world could be built of. Bypassing that to stay offline would be scoring compositions no
 * writer could produce.
 */
@Tags(NEEDS_REGISTRIES)
class DangerCheck : FunSpec({

    /**
     * The rule the whole design rests on: an Age is its **average**, not its worst corner.
     *
     * Without this, writing a hostile world and then writing yourself a safe base in one territory costs
     * nothing and pays in full, and §7.7's "the ingenuity worth rewarding is solving the problem inside
     * the Age, not at the writing desk" is dead.
     */
    test("a five-percent magma region does not score like a magma world") {
        val magmaWorld = oneTerritory().withOptionsFor(
            Aspect.TERRAIN,
            member = 0,
            parameter = Terrain.STONE.name,
            chosen = listOf(MAGMA),
        )
        val magmaHalf = twoTerritories(shares = listOf(1.0, 1.0), second = MAGMA)
        val magmaCorner = twoTerritories(shares = listOf(1.0, A_TWENTIETH), second = MAGMA)

        val whole = score(magmaWorld).materials
        val half = score(magmaHalf).materials
        val corner = score(magmaCorner).materials

        check(whole == 1.0) { "a world of magma should be as bad as its materials get, and scored $whole" }
        check(corner < ONE_TENTH) { "a magma corner should barely register, and scored $corner" }
        // The whole rule in one line: what it is worth is the ground it covers, and nothing else.
        check(whole > half && half > corner) {
            "danger should follow the ground covered, and went $whole, $half, $corner"
        }
    }

    /**
     * And the other half of the same rule: **within** one territory the worst material wins.
     *
     * Mingled materials are laid together through the whole of the ground they cover, so there is nowhere
     * in the territory the dangerous one is not — which is exactly where an average would be wrong.
     */
    test("materials mingled into one territory are scored at their worst") {
        val mingled = oneTerritory().withOptionsFor(
            Aspect.TERRAIN,
            member = 0,
            parameter = Terrain.STONE.name,
            chosen = listOf(ORDINARY_STONE, MAGMA),
        )
        val scored = score(mingled).materials
        check(scored == 1.0) { "magma mingled through a territory should score in full, and scored $scored" }
    }

    test("an Age nobody said anything dangerous about pays nothing") {
        val plain = score(oneTerritory())
        check(plain.score == 0.0) { "a plain Age scored ${plain.score}" }
        check(!plain.paysOut) { "a plain Age pays out" }
        check(plain.allowsRuins) { "a plain Age is too dangerous for ruins" }
    }

    /** The provenance fence: what a found book describes may be as bad as it likes and still never pays. */
    test("an Age nobody wrote never pays out, however dangerous") {
        val hellish = oneTerritory().withOptionsFor(
            Aspect.TERRAIN,
            member = 0,
            parameter = Terrain.STONE.name,
            chosen = listOf(MAGMA),
        )
        val written = score(hellish, authored = true)
        val found = score(hellish, authored = false)

        check(written.score == found.score) { "provenance should not change the score" }
        check(written.paysOut) { "a written hellish Age should pay, and scored ${written.score}" }
        check(!found.paysOut) { "a found Age paid out" }
    }

    /** A sea is what the Age is made of too, and a sea of lava is the plainest dangerous Age there is. */
    test("a sea of lava is scored, and a sea of water is not") {
        val lava = oneTerritory().copy(seas = listOf(Sea.LAVA))
        val water = oneTerritory().copy(seas = listOf(Sea.WATER))
        check(score(lava).materials == 1.0) { "a lava sea scored ${score(lava).materials}" }
        check(score(water).materials == 0.0) { "a water sea scored ${score(water).materials}" }
    }

    /** The skin is what you actually stand on, so naming it is naming the Age's material. */
    test("a surface of magma is scored though the rock beneath it is ordinary") {
        val dressed = oneTerritory()
            .withOptions(Aspect.SURFACE, Surface.MATERIAL.name, listOf(MAGMA))
        check(score(dressed).materials == 1.0) { "a magma skin scored ${score(dressed).materials}" }
    }

    test("what the book asked to live here is scored, and by how much of it was asked for") {
        val one = oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(GHASTS))
        val many = oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf("$GHASTS[amount=4]"))

        val asked = score(one).spawns
        val insisted = score(many).spawns
        check(asked > 0.0) { "asking for ghasts scored nothing" }
        check(insisted > asked) { "asking for four times as many ghasts scored $insisted against $asked" }
    }

    /** A claim confined to one biome is one biome's worth of danger, not an Age's worth. */
    test("a hazard confined to one biome is scored at less than the same hazard everywhere") {
        val everywhere = oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(GHASTS))
        val somewhere = oneTerritory()
            .withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf("$GHASTS[in=minecraft:plains]"))

        val open = score(everywhere).spawns
        val confined = score(somewhere).spawns
        check(confined < open) { "a confined claim scored $confined against $open everywhere" }
        check(confined > 0.0) { "a confined claim scored nothing at all" }
    }

    test("a hazard the book struck out is not scored") {
        val struck = oneTerritory()
            .withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(GHASTS, "$GHASTS[except]"))
        check(score(struck).spawns == 0.0) { "a struck-out ghast scored ${score(struck).spawns}" }
    }

    test("what happens here is scored, and a sight is not a hazard") {
        val burning = oneTerritory().withOptions(Aspect.PHENOMENA, Phenomena.HAPPENS.name, listOf(INFERNO))
        val lovely = oneTerritory().withOptions(Aspect.PHENOMENA, Phenomena.HAPPENS.name, listOf(AURORA))
        check(score(burning).phenomena > 0.0) { "an inferno scored nothing" }
        check(score(lovely).phenomena == 0.0) { "an aurora scored ${score(lovely).phenomena}" }
    }

    /**
     * §7.7 counts a roof "only insofar as it drives mob pressure: a sealed sky is danger because of what
     * walks under it". So an Age nothing walks in takes nothing from its roof.
     */
    test("a sealed Age is dangerous, unless nothing lives in it") {
        val sealed = oneTerritory().withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
        val sealedAndEmpty = sealed.withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(Spawns.NOTHING))

        check(score(sealed).lighting > 0.0) { "a sealed Age took no danger from its roof" }
        check(score(sealedAndEmpty).lighting == 0.0) {
            "an Age nothing lives in took danger from its roof anyway"
        }
    }

    /**
     * Danger and instability are separate axes, and this is where that is enforced.
     *
     * A contradiction pays only where the budget actually **bought a hazard**. Torn seams are the world's
     * shape being wrong, which is not a thing that can kill you — so an Age that spent everything on them
     * must score exactly what a coherent one does.
     */
    test("a contradiction that only tore some seams is worth nothing") {
        val coherent = score(oneTerritory(), index = NOTHING_WRONG)
        val torn = score(oneTerritory(), index = ONLY_TORN_SEAMS)
        check(torn.score == coherent.score) { "torn seams scored ${torn.score} against ${coherent.score}" }
    }

    test("a contradiction that bought wounds is hostile life, and one that bought a sandfall is a hazard") {
        val wounded = score(oneTerritory(), index = AS_FAR_AS_WOUNDS)
        val buried = score(oneTerritory(), index = AS_FAR_AS_SANDFALL)
        check(wounded.spawns > 0.0) { "wounds drew no hostile life" }
        check(buried.phenomena > 0.0) { "an inflicted sandfall was not a hazard" }
    }

    /**
     * The raid's trigger, and it is a threshold at the very top rather than a slope.
     *
     * An Age that merely went badly wrong must not read as terminal: §7.7's absurd rewards are for the Age
     * nobody could have lived in, and a multiplier that crept in as an Age got worse would make the whole
     * economy a function of how sloppy the writer was.
     */
    test("only an Age that bought every step of collapse is terminal") {
        check(!score(oneTerritory(), index = NOTHING_WRONG).isTerminal) { "a coherent Age is terminal" }
        check(!score(oneTerritory(), index = AS_FAR_AS_SANDFALL).isTerminal) {
            "an Age that never reached collapse is terminal"
        }
        check(score(oneTerritory(), index = AS_FAR_AS_COLLAPSE).isTerminal) {
            "an Age that bought all the collapse there is is not terminal"
        }
    }

    /** Exposed rather than scored, so §7.7's terminal multiplier can be settled without reopening this. */
    test("an Age that will not last says so without it changing the score") {
        val terminal = score(oneTerritory(), index = AS_FAR_AS_COLLAPSE)
        val ordinary = score(oneTerritory(), index = NOTHING_WRONG)
        check(terminal.terminal > 0.0) { "a collapsing Age reported no terminal reach" }
        check(terminal.materials == ordinary.materials) { "collapse moved a contributor it has nothing to do with" }
    }
}) {
    companion object {
        private const val SEED = 42L
        private const val MAGMA = "minecraft:magma_block"
        private const val ORDINARY_STONE = "minecraft:stone"
        private const val GHASTS = "minecraft:ghast"
        private const val INFERNO = "inferno"
        private const val AURORA = "aurora"
        private const val A_TWENTIETH = 0.05
        private const val ONE_TENTH = 0.1

        /** Every manifestation the same price, so an index maps onto what it bought by plain arithmetic. */
        private const val A_STEP = 2
        private const val STEPS_EACH = 4
        private const val FULLY = A_STEP * STEPS_EACH

        private const val NOTHING_WRONG = 0
        private const val ONLY_TORN_SEAMS = FULLY
        private const val AS_FAR_AS_WOUNDS = FULLY * 2
        private const val AS_FAR_AS_SANDFALL = FULLY * 4
        private const val AS_FAR_AS_COLLAPSE = FULLY * 5

        private val PRICES = Manifestation.entries.associateWith { Price(A_STEP, STEPS_EACH) }

        init {
            MinecraftRegistries.ensureStoodUp()
        }

        /**
         * A table written for the checks: one material and one creature worth everything, one phenomenon
         * that is a hazard and one that is a sight, and weights that make each contributor readable on its
         * own.
         */
        private val TABLE = DangerTable(
            weights = DangerTable.Weights(
                materials = 0.25,
                spawns = 0.25,
                phenomena = 0.25,
                lighting = 0.25,
            ),
            paysAbove = 0.2,
            spawnsFull = 1.0,
            phenomenaFull = 1.0,
            confinedWeight = 0.25,
            woundHostility = 0.5,
            materials = mapOf(MAGMA to 1.0, "minecraft:lava" to 1.0),
            spawns = mapOf(GHASTS to 0.5),
            phenomena = mapOf(INFERNO to 1.0, "sandfall" to 1.0, AURORA to 0.0),
            lighting = mapOf("sealed" to 1.0, "lightless" to 0.5),
        )

        /** One landform covering the whole Age, made of nothing in particular. */
        private fun oneTerritory(): AgeComposition = AgeComposition(terrains = listOf(Terrain.HILLS))

        /**
         * Two landforms dividing the Age, ordinary rock in the first and [second] in the other, each
         * covering the ground its share gives it.
         */
        private fun twoTerritories(shares: List<Double>, second: String): AgeComposition =
            AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.CAVERNS))
                .copy(spreads = Spreads().withShares(Aspect.TERRAIN, shares))
                .withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf(ORDINARY_STONE))
                .withOptionsFor(Aspect.TERRAIN, 1, Terrain.STONE.name, listOf(second))

        /**
         * Scored as a composition rather than as a recipe, which is the entry the desk uses — the
         * recipe-level one is checked in [DangerTableCheck].
         */
        private fun score(
            composition: AgeComposition,
            authored: Boolean = true,
            index: Int = NOTHING_WRONG,
        ): Danger = Danger.of(composition, instabilityAt(index), SEED, authored, TABLE, PRICES)

        /**
         * An instability worth exactly [index], as one flaw.
         *
         * What the index *is* does not matter here — [Danger] never reads the register, only what the
         * budget bought — so one flaw carrying the whole severity says all these checks need.
         */
        private fun instabilityAt(index: Int): Instability =
            if (index <= 0) {
                Instability.NONE
            } else {
                Instability(listOf(Flaw(Register.TENSION, listOf("a", "b"), null, emptyList(), index)))
            }
    }
}
