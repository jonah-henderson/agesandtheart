package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.DialSteps
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.Spreads
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Pool
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
     * **Hazards in the ground add up** (Jonah, 2026-09-11: *"those danger numbers need fixing, I believe
     * they should all be adding up"*).
     *
     * It was the worst entry, on the argument the materials still make — that a wheat field beside a
     * volcano should not make an Age worse. The materials have to reason that way because an Age is made
     * of exactly one rock, so its three answers are rivals; a feature list is not rivals, and scoring it as
     * one meant an Age holding every volcanic hazard read exactly as dangerous as one holding a single
     * cone. Nothing was traded away to fix it: an unlisted feature is worth a literal zero here, so the
     * wheat field was always free.
     */
    test("what an Age asked to have placed in it adds up rather than counting only its worst") {
        val cones = groundScore(growing(VOLCANO)).features
        val tubes = groundScore(growing(LAVA_TUBES)).features
        val both = groundScore(growing(VOLCANO, LAVA_TUBES)).features

        check(cones == 0.5) { "a volcano of weight 1.0 against a full world of 2.0 scored $cones" }
        check(tubes == 0.6 / 2.0) { "lava tubes of weight 0.6 against a full world of 2.0 scored $tubes" }
        check(both == cones + tubes) { "a volcano ($cones) and tubes ($tubes) together scored $both" }
        check(both > cones) { "adding a second hazard to a volcano did not make the Age worse" }
    }

    /** And a hazard the book struck out is not one the Age has. The maximum never checked. */
    test("a feature written out of an Age stops counting against it") {
        val struck = growing(VOLCANO, "$VOLCANO${Claim.OPEN}${Claim.EXCEPT}${Claim.CLOSE}", LAVA_TUBES)
        check(groundScore(struck).features == 0.6 / 2.0) {
            "an Age that wrote its volcano out still scored ${groundScore(struck).features}"
        }
    }

    /** Scenery is free, which is what lets the sum be a sum. */
    test("a feature the table has no line for costs nothing") {
        val withScenery = growing(VOLCANO, "minecraft:patch_sunflower")
        check(groundScore(withScenery).features == groundScore(growing(VOLCANO)).features) {
            "adding a sunflower patch to a volcano moved the score"
        }
    }

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

    test("monsters made stronger or quicker are more dangerous, asked for or already here") {
        fun spawnsWith(vararg parameters: Pair<String, String>) = parameters.fold(oneTerritory()) { held, (name, value) ->
            held.withOptions(Aspect.SPAWNS, name, listOf(value))
        }
        val nothing = score(oneTerritory()).spawns
        val strongZombies = score(spawnsWith("strength" to "1.0..1.0[for=$ZOMBIE]")).spawns
        val swiftEverything = score(spawnsWith("pace" to "1.0..1.0")).spawns
        check(strongZombies > nothing) { "strong zombies scored $strongZombies, no more than nothing at $nothing" }
        check(swiftEverything > strongZombies) { "every monster quickened scored $swiftEverything, below one kind strengthened" }

        val ghasts = score(oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(GHASTS))).spawns
        val strongGhasts = score(
            oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(GHASTS))
                .withOptions(Aspect.SPAWNS, "strength", listOf("1.0..1.0")),
        ).spawns
        check(strongGhasts > ghasts) { "strong ghasts scored $strongGhasts against $ghasts for ordinary ones" }
    }

    test("a charged creeper is more dangerous, and a creature rooted where it stands less") {
        fun creepersWith(vararg parameters: Pair<String, String>) = parameters.fold(
            oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(CREEPER)),
        ) { held, (name, value) -> held.withOptions(Aspect.SPAWNS, name, listOf(value)) }
        val creepers = score(creepersWith()).spawns
        val charged = score(creepersWith(Spawns.CHARGED.name to "${Parameter.TRUE}[for=$CREEPER]")).spawns
        val rooted = score(creepersWith(Spawns.STILL.name to Parameter.TRUE)).spawns
        check(charged > creepers) { "charged creepers scored $charged against $creepers" }
        check(rooted < creepers) { "rooted creepers scored $rooted against $creepers" }
    }

    test("monsters walking at noon are dangerous as a roof is") {
        val loosed = oneTerritory().withOptions(Aspect.SPAWNS, Spawns.INCONGRUOUS.name, listOf(Parameter.TRUE))
        check(score(loosed).lighting > 0.0) { "monsters loosed into the daylight took no danger" }
    }

    test("a thick haze, constant thunder and a heavy pull make an Age harder to be in, and a clear one does not") {
        fun with(aspect: Aspect, parameter: String, value: String) = score(oneTerritory().withOptions(aspect, parameter, listOf(value))).conditions
        check(with(Aspect.AIR, "haze", "0.9..1") > 0.0) { "a smothering haze took no danger" }
        check(with(Aspect.WEATHER, "thunder", "0.9..1") > 0.0) { "thunder in every storm took no danger" }
        check(with(Aspect.GRAVITY, "pull", "0.9..1") > 0.0) { "a heavy pull took no danger" }
        check(with(Aspect.AIR, "haze", "-1..-0.9") == 0.0) { "a clear Age took danger from its clarity" }
        check(score(oneTerritory()).conditions == 0.0) { "an Age that said nothing of its conditions scored them" }
    }

    test("weakened monsters take danger away, but never below the biomes' own") {
        val weak = score(oneTerritory().withOptions(Aspect.SPAWNS, "strength", listOf("-1.0..-1.0"))).spawns
        check(weak == 0.0) { "weakening the monsters already here scored $weak" }
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
        val sealedAndEmpty = sealed.withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(Pool.NOTHING))

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
        val coherent = score(oneTerritory())
        val torn = score(oneTerritory(), bought = Manifestation.TORN_SEAMS)
        check(torn.score == coherent.score) { "torn seams scored ${torn.score} against ${coherent.score}" }
    }

    test("a contradiction that bought wounds is hostile life, and one that bought a sandfall is a hazard") {
        val wounded = score(oneTerritory(), bought = Manifestation.WOUNDS)
        val buried = score(oneTerritory(), bought = Manifestation.SANDFALL)
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
        check(!score(oneTerritory()).isTerminal) { "a coherent Age is terminal" }
        check(!score(oneTerritory(), bought = Manifestation.SANDFALL).isTerminal) {
            "an Age that never reached collapse is terminal"
        }
        check(score(oneTerritory(), bought = Manifestation.COLLAPSE).isTerminal) {
            "an Age that bought all the collapse there is is not terminal"
        }
    }

    /** Exposed rather than scored, so §7.7's terminal multiplier can be settled without reopening this. */
    test("an Age that will not last says so without it changing the score") {
        val terminal = score(oneTerritory(), bought = Manifestation.COLLAPSE)
        val ordinary = score(oneTerritory())
        check(terminal.terminal > 0.0) { "a collapsing Age reported no terminal reach" }
        check(terminal.materials == ordinary.materials) { "collapse moved a contributor it has nothing to do with" }
    }
}) {
    companion object {
        private const val MAGMA = "minecraft:magma_block"
        private const val ORDINARY_STONE = "minecraft:stone"
        private const val GHASTS = "minecraft:ghast"
        private const val ZOMBIE = "minecraft:zombie"
        private const val CREEPER = "minecraft:creeper"

        /** What the table calls any monster it has no line for. */
        private const val ANY_MONSTER = "monsters"
        private const val INFERNO = "inferno"
        private const val AURORA = "aurora"
        private const val A_TWENTIETH = 0.05
        private const val ONE_TENTH = 0.1

        private const val STEPS_EACH = 4

        /**
         * **Ladders that sell [only]'s steps and nothing else**, so a check names what instability bought
         * with no draw between: every step strange, so every flaw falls through to it, and every other
         * manifestation selling no steps at all.
         */
        private fun selling(only: Manifestation?): Map<Manifestation, Ladder> = Manifestation.entries.associateWith {
            if (it == only) Ladder.flat(it, tier = Spending.STRANGE, most = STEPS_EACH)
            else Ladder(it.dials.associateWith { DialSteps(emptyList()) })
        }

        /** Enough flaws to buy every step [manifestation] sells. */
        private fun allOf(manifestation: Manifestation) = STEPS_EACH * manifestation.dials.size

        private const val ANY_SEED = 7L

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
                features = 0.0,
                conditions = 0.25,
            ),
            paysAbove = 0.2,
            spawnsFull = 1.0,
            phenomenaFull = 1.0,
            featuresFull = 1.0,
            confinedWeight = 0.25,
            woundHostility = 0.5,
            materials = mapOf(MAGMA to 1.0, "minecraft:lava" to 1.0),
            spawns = mapOf(GHASTS to 0.5, ANY_MONSTER to 0.3),
            phenomena = mapOf(INFERNO to 1.0, "sandfall" to 1.0, AURORA to 0.0),
            lighting = mapOf("sealed" to 1.0, "lightless" to 0.5, "incongruous" to 0.75),
            features = emptyMap(),
            conditions = mapOf("haze" to 1.0, "thunder" to 1.0, "pull" to 1.0),
        )

        /**
         * A table that sees nothing but what is placed, with two hazards of known weight in it.
         *
         * **`features_full` is 2.0 here and 1.0 in the shipped file, deliberately.** A divisor of one hides
         * a divisor that is never applied at all, and this file exists to check the arithmetic rather than
         * the tuning — so one hazard of weight 1.0 comes out at half a world full, and a bug that dropped
         * the division would read 1.0 and fail.
         */
        private val GROUND_ONLY = TABLE.copy(
            weights = DangerTable.Weights(
                materials = 0.0,
                spawns = 0.0,
                phenomena = 0.0,
                lighting = 0.0,
                features = 1.0,
            ),
            featuresFull = 2.0,
            features = mapOf(VOLCANO to 1.0, LAVA_TUBES to 0.6),
        )

        private const val VOLCANO = "agesandtheart:volcano"
        private const val LAVA_TUBES = "agesandtheart:lava_tubes"

        /** An Age over one landform asking for exactly [placed] to be grown in it. */
        private fun growing(vararg placed: String): AgeComposition =
            oneTerritory().withOptionsFor(Aspect.FEATURES, 0, Features.PLACES.name, placed.toList())

        private fun groundScore(composition: AgeComposition): Danger =
            Danger.of(composition, Instability.NONE, ANY_SEED, true, GROUND_ONLY, selling(null))

        /** One landform covering the whole Age, made of nothing in particular. */
        private fun oneTerritory(): AgeComposition = AgeComposition(terrains = listOf(Terrain.HILLS))

        /**
         * Two landforms dividing the Age, ordinary rock in the first and [second] in the other, each
         * covering the ground its share gives it.
         */
        private fun twoTerritories(shares: List<Double>, second: String): AgeComposition =
            AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.ERODED))
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
            bought: Manifestation? = null,
        ): Danger {
            // What the flaws *are* does not matter — [Danger] never reads the register, only what was bought.
            val instability = bought?.let { Instability.forced(allOf(it)) } ?: Instability.NONE
            return Danger.of(composition, instability, ANY_SEED, authored, TABLE, selling(bought))
        }
    }
}
