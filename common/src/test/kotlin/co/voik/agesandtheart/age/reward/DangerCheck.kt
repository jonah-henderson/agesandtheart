package co.voik.agesandtheart.age.reward

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.DialSteps
import co.voik.agesandtheart.age.Ladder
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.Spreads
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Pool
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Spawning
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.reward.Danger.Source
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.level.biome.MobSpawnSettings
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * That an Age is scored for what it is, and that the rules §7.7 calls load-bearing hold.
 *
 * **Every composition is built by hand and scored against a table written here**, never the shipped one, so
 * retuning `art/danger.json` by play never breaks a build. What the shipped file has to satisfy is
 * [DangerTableCheck], and how it scores the Ages it was rated against is [DangerAnchorsCheck].
 *
 * The registries are needed because naming a block in a material parameter asks the block registry whether a
 * world could be built of it.
 */
@Tags(NEEDS_REGISTRIES)
class DangerCheck : FunSpec({

    test("an Age nobody said anything dangerous about holds no hazard and pays nothing") {
        val plain = score(oneTerritory())
        check(plain.hazards.isEmpty()) { "a plain Age held ${plain.hazards}" }
        check(plain.score == 0.0) { "a plain Age scored ${plain.score}" }
        check(!plain.paysOut) { "a plain Age pays out" }
        check(plain.allowsRuins) { "a plain Age is too dangerous for ruins" }
    }

    test("hazards combine as the root of the sum of their powers, more than either and less than both") {
        val volcanoOverLava = growing(VOLCANO).copy(seas = listOf(Sea.LAVA))
        val both = score(volcanoOverLava, table = TABLE.copy(combining = 2.0)).score
        val volcano = score(growing(VOLCANO)).score
        val lava = score(oneTerritory().copy(seas = listOf(Sea.LAVA))).score
        check(abs(both - sqrt(volcano * volcano + lava * lava)) < A_ROUNDING) {
            "a volcano ($volcano) and a lava sea ($lava) combined at two came to $both"
        }
        check(both > maxOf(volcano, lava) && both < volcano + lava) { "they combined to $both" }
    }

    test("the features placed in an Age count as their worst, one hazard") {
        val both = score(growing(VOLCANO, LAVA_TUBES)).from(Source.FEATURE)
        check(both.map { it.name } == listOf(VOLCANO)) { "a volcano and tubes were scored as $both" }
    }

    test("a feature written out of an Age stops counting, and scenery never counts") {
        val struck = growing(VOLCANO, "$VOLCANO${Claim.OPEN}${Claim.EXCEPT}${Claim.CLOSE}", LAVA_TUBES)
        check(score(struck).from(Source.FEATURE).map { it.name } == listOf(LAVA_TUBES)) {
            "an Age that wrote its volcano out held ${score(struck).from(Source.FEATURE)}"
        }
        val withScenery = growing(VOLCANO, "minecraft:patch_sunflower")
        check(score(withScenery).score == score(growing(VOLCANO)).score) { "a sunflower patch moved the score" }
    }

    /**
     * The rule the design rests on: what covers part of an Age scores the part it covers. Otherwise a safe base
     * written into one territory of a hostile Age costs nothing and pays in full.
     */
    test("a five-percent magma region does not score like a magma world") {
        val whole = ground(oneTerritory().withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf(MAGMA)))
        val half = ground(twoTerritories(shares = listOf(1.0, 1.0), second = MAGMA))
        val corner = ground(twoTerritories(shares = listOf(1.0, A_TWENTIETH), second = MAGMA))
        check(whole == MAGMA_WORTH) { "a world of magma scored $whole" }
        check(corner < MAGMA_WORTH / 10) { "a magma corner scored $corner" }
        check(whole > half && half > corner) { "danger should follow the ground covered, and went $whole, $half, $corner" }
    }

    test("materials mingled into one territory are scored at their worst, and a surface counts as ground") {
        val mingled = oneTerritory().withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf(ORDINARY_STONE, MAGMA))
        check(ground(mingled) == MAGMA_WORTH) { "magma mingled through a territory scored ${ground(mingled)}" }
        val dressed = oneTerritory().withOptions(Aspect.SURFACE, Surface.MATERIAL.name, listOf(MAGMA))
        check(ground(dressed) == MAGMA_WORTH) { "a magma skin scored ${ground(dressed)}" }
    }

    test("a sea of lava is a hazard, and a sea of water is not") {
        val lava = score(oneTerritory().copy(seas = listOf(Sea.LAVA))).from(Source.SEA)
        val water = score(oneTerritory().copy(seas = listOf(Sea.WATER))).from(Source.SEA)
        check(lava.singleOrNull()?.value == LAVA_SEA_WORTH) { "a lava sea scored $lava" }
        check(water.isEmpty()) { "a water sea scored $water" }
    }

    test("land over the void is a hazard, unless a sea lies beneath to fall into") {
        val islands = AgeComposition(terrains = listOf(Terrain.SPIRE_ISLANDS)).copy(seas = listOf(Sea.NONE))
        val overWater = islands.copy(seas = listOf(Sea.WATER))
        check(score(islands).from(Source.VOID).singleOrNull()?.value == VOID_WORTH) { "islands over the void scored ${score(islands).hazards}" }
        check(score(overWater).from(Source.VOID).isEmpty()) { "islands over a sea scored ${score(overWater).from(Source.VOID)}" }
    }

    test("a crowd is scored by the monster cap the book set") {
        val teeming = monsters(score(oneTerritory().withOptions(Aspect.SPAWNS, Spawns.CAPS.name, listOf("monster:4"))))
        check(abs(teeming - MONSTERS_DOUBLED * 3) < A_ROUNDING) { "the world's own monsters under four times the cap scored $teeming" }
    }

    test("a creature counts for how often it is met, and more of it for more") {
        val wardens = monsters(score(spawning(WARDEN)))
        val moreWardens = monsters(score(spawning("$WARDEN[amount=4]")))
        val allWardens = MONSTERS_DOUBLED * (WARDEN_WORTH / OWN_WORLDS_WORTH - 1)
        check(wardens > 0.0) { "wardens named into the Age scored nothing" }
        check(moreWardens > wardens) { "wardens four times as thick scored $moreWardens against $wardens" }
        check(moreWardens < allWardens) { "wardens among the world's own scored $moreWardens, as if nothing else were met" }
    }

    test("a creature named where nothing else spawns is all that is met there") {
        val mushrooms = oneTerritory().withOptions(Aspect.BIOMES, Biomes.GROWN.name, listOf("$MUSHROOM_FIELDS[only]"))
        val wardensAmongMushrooms = monsters(score(mushrooms.withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(WARDEN))))
        val allWardens = MONSTERS_DOUBLED * (WARDEN_WORTH / OWN_WORLDS_WORTH - 1)
        check(abs(wardensAmongMushrooms - allWardens) < A_ROUNDING) { "wardens among mushrooms scored $wardensAmongMushrooms" }
        check(monsters(score(mushrooms)) == 0.0) { "ground no monster spawns on scored ${monsters(score(mushrooms))}" }
        val sealedMushrooms = mushrooms.withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
        check(monsters(score(sealedMushrooms)) == 0.0) { "a roof over ground no monster spawns on let out ${monsters(score(sealedMushrooms))}" }
    }

    test("a creature made commoner where the Age grows none counts nothing") {
        check(monsters(score(spawning("$GHASTS[${Claim.WHERE_IT_GROWS},amount=4]"))) == 0.0) { "ghasts made commoner where none grow were scored" }
    }

    test("a creature confined to one biome counts where that biome is") {
        val everywhere = monsters(score(spawning(WARDEN)))
        val confined = monsters(score(spawning("$WARDEN[in=$PLAINS]")))
        check(confined > 0.0 && confined < everywhere) { "wardens in one of two biomes scored $confined against $everywhere everywhere" }
    }

    /** More monsters and monsters out at every hour are one danger met two ways, and rated together they were the worse. */
    test("monsters and the darkness that lets them out count as the worse of them, not both") {
        val sealed = oneTerritory().withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
        val teemingUnderARoof = sealed.withOptions(Aspect.SPAWNS, Spawns.CAPS.name, listOf("monster:4"))
        check(monsters(score(sealed)) == SEALED_WORTH) { "a sealed sky scored ${monsters(score(sealed))}" }
        check(abs(monsters(score(teemingUnderARoof)) - SEALED_WORTH * 4) < A_ROUNDING) {
            "four times the monsters under a roof scored ${monsters(score(teemingUnderARoof))}"
        }
        check(score(teemingUnderARoof).from(Source.CREATURES).size == 1) { "the monsters were counted as more than one hazard" }
    }

    test("a sealed Age is dangerous, unless nothing lives in it") {
        val sealed = oneTerritory().withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
        val sealedAndEmpty = sealed.withOptions(Aspect.SPAWNS, Spawns.LIVES.name, listOf(Pool.NOTHING))
        check(monsters(score(sealedAndEmpty)) == 0.0) { "an Age nothing lives in took danger from its roof" }
    }

    test("monsters made stronger or quicker are more dangerous, by how much of the whole they are") {
        val strongZombies = monsters(score(oneTerritory().withOptions(Aspect.SPAWNS, "strength", listOf("1.0..1.0[for=$ZOMBIE]"))))
        val swiftEverything = monsters(score(oneTerritory().withOptions(Aspect.SPAWNS, "pace", listOf("1.0..1.0"))))
        check(strongZombies > 0.0) { "strong zombies scored nothing" }
        check(swiftEverything > strongZombies) { "every monster quickened scored $swiftEverything, below strong zombies at $strongZombies" }
        val strongEverything = monsters(score(oneTerritory().withOptions(Aspect.SPAWNS, "strength", listOf("1.0..1.0"))))
        check(abs(strongEverything - MONSTERS_DOUBLED * (STRONGEST - 1.0)) < A_ROUNDING) {
            "every monster strengthened scored $strongEverything"
        }
    }

    test("an incongruous Age's monsters come from all its biomes everywhere, and walk at noon") {
        val loosed = oneTerritory().withOptions(Aspect.SPAWNS, Spawns.INCONGRUOUS.name, listOf(Parameter.TRUE))
        val mostlyPlains = monsters(score(loosed, census = MOSTLY_PLAINS_AND_A_WASTE))
        check(mostlyPlains > INCONGRUOUS_WORTH) { "the waste's ghasts loosed over the plains scored $mostlyPlains" }
        check(monsters(score(loosed)) == INCONGRUOUS_WORTH) { "monsters loosed into the daylight scored ${monsters(score(loosed))}" }
    }

    test("darkness lets out monsters as hard as the book made them") {
        val sealed = oneTerritory().withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
        val swiftInTheDark = monsters(score(sealed.withOptions(Aspect.SPAWNS, "pace", listOf("1.0..1.0"))))
        check(swiftInTheDark > SEALED_WORTH) { "swift monsters under a roof scored $swiftInTheDark, no more than the roof" }
    }

    test("a charged creeper or a herd of them is more dangerous, and a creature rooted where it stands less") {
        val sealed = oneTerritory().withOptions(Aspect.SKY, Sky.SEALED.name, listOf(Parameter.TRUE))
        fun creepersWith(vararg parameters: Pair<String, String>) = monsters(score(parameters.fold(sealed) { held, (name, value) ->
            held.withOptions(Aspect.SPAWNS, name, listOf(value))
        }))
        val creepers = creepersWith()
        check(creepersWith(Spawns.CHARGED.name to "${Parameter.TRUE}[for=$CREEPER]") > creepers) { "charged creepers were no worse" }
        check(creepersWith(Spawns.STILL.name to Parameter.TRUE) < creepers) { "rooted creepers were no better" }
        check(creepersWith(Spawns.HERD.name to "0.0..0.0[for=$CREEPER]") > creepers) { "creepers in herds were no worse" }
    }

    test("weakened monsters take nothing below the biomes' own") {
        val weak = monsters(score(oneTerritory().withOptions(Aspect.SPAWNS, "strength", listOf("-1.0..-1.0"))))
        check(weak == 0.0) { "weakening the monsters already here scored $weak" }
    }

    test("a hazard the book struck out is not scored") {
        check(monsters(score(spawning(WARDEN, "$WARDEN[except]"))) == 0.0) { "a struck-out warden was scored" }
    }

    test("what happens here is scored, and a sight is not a hazard") {
        val burning = score(oneTerritory().withOptions(Aspect.PHENOMENA, Phenomena.HAPPENS.name, listOf(INFERNO)))
        val lovely = score(oneTerritory().withOptions(Aspect.PHENOMENA, Phenomena.HAPPENS.name, listOf(AURORA)))
        check(burning.from(Source.PHENOMENON).singleOrNull()?.value == INFERNO_WORTH) { "an inferno scored ${burning.hazards}" }
        check(lovely.hazards.isEmpty()) { "an aurora scored ${lovely.hazards}" }
    }

    test("a phenomenon written more often or harder is worse, and one written rarely is milder") {
        fun inferno(vararg dials: Pair<String, String>) = score(dials.fold(
            oneTerritory().withOptions(Aspect.PHENOMENA, Phenomena.HAPPENS.name, listOf(INFERNO)),
        ) { held, (name, value) -> held.withOptions(Aspect.PHENOMENA, name, listOf(value)) }).from(Source.PHENOMENON).single().value
        check(inferno(Phenomena.FREQUENCY.name to "0.5..0.5") > INFERNO_WORTH) { "a frequent inferno was no worse" }
        check(inferno(Phenomena.FREQUENCY.name to "-0.5..-0.5") < INFERNO_WORTH) { "a rare inferno was no milder" }
        check(inferno(Phenomena.STRENGTH.name to "0.5..0.5") > INFERNO_WORTH) { "a strong inferno was no worse" }
    }

    test("a thick haze and constant thunder make an Age harder to be in, and a clear one does not") {
        fun condition(aspect: Aspect, parameter: String, value: String) =
            score(oneTerritory().withOptions(aspect, parameter, listOf(value))).from(Source.CONDITION).sumOf { it.value }
        check(condition(Aspect.AIR, "haze", "0.9..1") > 0.0) { "a smothering haze took no danger" }
        check(condition(Aspect.WEATHER, "thunder", "0.9..1") > 0.0) { "thunder in every storm took no danger" }
        check(condition(Aspect.AIR, "haze", "-1..-0.9") == 0.0) { "a clear Age took danger from its clarity" }
    }

    test("a heavier pull is a little worse, and one too heavy to jump a block is much worse") {
        fun pulled(pull: String) = score(oneTerritory().withOptions(Aspect.GRAVITY, "pull", listOf(pull))).from(Source.GRAVITY).sumOf { it.value }
        val heavier = pulled("0.25..0.25")
        val crushing = pulled("1.0..1.0")
        check(heavier > 0.0 && heavier < HEAVIER_WORTH) { "a slightly heavier pull scored $heavier" }
        check(crushing == UNJUMPABLE_WORTH) { "a pull no block can be jumped under scored $crushing" }
        check(pulled("-1.0..-1.0") == 0.0) { "a lighter pull scored as a hazard" }
    }

    /** Danger and instability are separate axes: a contradiction pays only where the budget bought a hazard. */
    test("a contradiction that only tore some seams is worth nothing") {
        check(score(oneTerritory(), bought = Manifestation.TORN_SEAMS).hazards.isEmpty()) { "torn seams were scored" }
    }

    test("a contradiction that bought wounds or a sandfall bought a hazard") {
        check(score(oneTerritory(), bought = Manifestation.WOUNDS).from(Source.WOUNDS).isNotEmpty()) { "wounds were not scored" }
        check(score(oneTerritory(), bought = Manifestation.SANDFALL).from(Source.PHENOMENON).isNotEmpty()) {
            "an inflicted sandfall was not scored"
        }
    }

    test("a phenomenon both written and inflicted is one hazard, at the worse of the two") {
        val written = oneTerritory().withOptions(Aspect.PHENOMENA, Phenomena.HAPPENS.name, listOf(SANDFALL))
        val sandfalls = score(written, bought = Manifestation.SANDFALL).from(Source.PHENOMENON)
        check(sandfalls.size == 1) { "a written and inflicted sandfall was counted ${sandfalls.size} times" }
    }

    test("an Age that will not last is terminal without that moving its score") {
        check(!score(oneTerritory()).isTerminal) { "a coherent Age is terminal" }
        check(!score(oneTerritory(), bought = Manifestation.SANDFALL).isTerminal) { "an Age that never reached collapse is terminal" }
        val collapsing = score(oneTerritory(), bought = Manifestation.COLLAPSE)
        check(collapsing.isTerminal && collapsing.paysOut && !collapsing.allowsRuins) { "a collapsing Age read as $collapsing" }
        check(collapsing.score == score(oneTerritory()).score) { "collapse moved the score" }
    }
}) {
    companion object {
        private const val MAGMA = "minecraft:magma_block"
        private const val ORDINARY_STONE = "minecraft:stone"
        private const val GHASTS = "minecraft:ghast"
        private const val ZOMBIE = "minecraft:zombie"
        private const val CREEPER = "minecraft:creeper"
        private const val WARDEN = "minecraft:warden"
        private const val INFERNO = "inferno"
        private const val SANDFALL = "sandfall"
        private const val AURORA = "aurora"
        private const val VOLCANO = "agesandtheart:volcano"
        private const val LAVA_TUBES = "agesandtheart:lava_tubes"

        private const val MAGMA_WORTH = 0.7
        private const val LAVA_SEA_WORTH = 0.2
        private const val VOID_WORTH = 0.5
        private const val PLAINS = "minecraft:plains"
        private const val MUSHROOM_FIELDS = "minecraft:mushroom_fields"
        private const val GHAST_WORTH = 1.5
        private const val CREEPER_WORTH = 1.5
        private const val WARDEN_WORTH = 20.0
        private const val ORDINARY_MONSTER_WORTH = 1.0
        private const val MONSTERS_DOUBLED = 0.6
        private const val STRONGEST = 1.3
        private const val SEALED_WORTH = 0.65
        private const val INCONGRUOUS_WORTH = 0.4
        private const val INFERNO_WORTH = 0.45
        private const val HEAVIER_WORTH = 0.1
        private const val UNJUMPABLE_WORTH = 0.6
        private const val CONFINED = 0.65

        /** What the world's own monsters are worth all told: zombies and creepers, as many of each. */
        private const val OWN_WORLDS_WORTH = (ORDINARY_MONSTER_WORTH + CREEPER_WORTH) / 2

        private const val A_TWENTIETH = 0.05
        private const val A_ROUNDING = 1e-9
        private const val STEPS_EACH = 4
        private const val ANY_SEED = 7L

        init {
            MinecraftRegistries.ensureStoodUp()
        }

        private val TABLE = DangerTable.NONE.copy(
            lines = ScoredDanger.LINES,
            combining = 1.8,
            confined = CONFINED,
            ground = DangerTable.Ground(
                madeOf = mapOf(MAGMA to MAGMA_WORTH),
                seas = mapOf("minecraft:lava" to LAVA_SEA_WORTH),
                abyss = 0.65,
                void = mapOf(Terrain.SPIRE_ISLANDS.key to VOID_WORTH),
                landforms = emptyMap(),
                underground = emptyMap(),
            ),
            creatures = mapOf("monsters" to ORDINARY_MONSTER_WORTH, GHASTS to GHAST_WORTH, CREEPER to CREEPER_WORTH, WARDEN to WARDEN_WORTH),
            monstersDoubled = MONSTERS_DOUBLED,
            darkness = DangerTable.Darkness(sealed = SEALED_WORTH, sunless = 0.5, roofedByRock = 0.1, incongruous = INCONGRUOUS_WORTH),
            hardiness = DangerTable.Hardiness(
                strength = DangerTable.Dial(raised = STRONGEST, lowered = 0.5),
                pace = DangerTable.Dial(raised = 2.0, lowered = 0.2),
                size = DangerTable.Dial(raised = 2.4, lowered = 1.0),
                charged = 2.0,
                still = 0.05,
                herds = 1.3,
            ),
            phenomena = mapOf(INFERNO to INFERNO_WORTH, SANDFALL to 0.3, AURORA to 0.0),
            phenomenonDials = DangerTable.PhenomenonDials(
                frequency = DangerTable.Dial(raised = 1.3, lowered = 0.3),
                constant = 2.0,
                strength = DangerTable.Dial(raised = 2.5, lowered = 0.5),
            ),
            features = mapOf(VOLCANO to 0.45, LAVA_TUBES to 0.4),
            conditions = mapOf("haze" to 0.45, "thunder" to 0.1),
            gravity = DangerTable.GravityHazard(heavier = HEAVIER_WORTH, unjumpable = UNJUMPABLE_WORTH, unjumpableFrom = 1.75),
            wounds = 0.05,
        )

        /** Ladders that sell [only]'s steps and nothing else, so a check names what instability bought with no draw between. */
        private fun selling(only: Manifestation?): Map<Manifestation, Ladder> = Manifestation.entries.associateWith {
            if (it == only) Ladder.flat(it, tier = Spending.STRANGE, most = STEPS_EACH)
            else Ladder(it.dials.associateWith { DialSteps(emptyList()) })
        }

        private fun score(
            composition: AgeComposition,
            bought: Manifestation? = null,
            table: DangerTable = TABLE,
            census: MonsterCensus = PLAINS_AND_A_FOREST,
        ): Danger {
            val instability = bought?.let { Instability.forced(STEPS_EACH * it.dials.size) } ?: Instability.NONE
            return Danger.of(composition, instability, ANY_SEED, table, selling(bought), census = census)
        }

        private fun offering(vararg monsters: Pair<String, Int>): WeightedList<MobSpawnSettings.SpawnerData> =
            WeightedList.of(monsters.map { (creature, weight) ->
                val type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(creature))
                Weighted(MobSpawnSettings.SpawnerData(type, UniformInt.of(1, 1)), weight)
            })

        private fun census(ground: (List<BiomePreference>) -> List<MonsterCensus.Patch>) = MonsterCensus(
            { preferences, _, _ -> ground(preferences) },
            Spawning(),
            livesSomewhere = setOf(ZOMBIE, CREEPER, GHASTS).map(Identifier::parse).toSet(),
        )

        /** Two biomes alike, zombies and creepers in each — or mushroom fields, offering nothing, where a book asks. */
        private val PLAINS_AND_A_FOREST = census { preferences ->
            val asksForMushrooms = preferences.any { it.biome.toString() == MUSHROOM_FIELDS }
            if (asksForMushrooms) {
                listOf(MonsterCensus.Patch(Identifier.parse(MUSHROOM_FIELDS), 1.0, offering()))
            } else {
                listOf(PLAINS, "minecraft:forest").map { MonsterCensus.Patch(Identifier.parse(it), 1.0, offering(ZOMBIE to 100, CREEPER to 100)) }
            }
        }

        /** Plains over three quarters of the ground, zombies in them, and a waste of ghasts over the rest. */
        private val MOSTLY_PLAINS_AND_A_WASTE = census {
            listOf(
                MonsterCensus.Patch(Identifier.parse(PLAINS), 3.0, offering(ZOMBIE to 100)),
                MonsterCensus.Patch(Identifier.parse("minecraft:nether_wastes"), 1.0, offering(GHASTS to 100)),
            )
        }

        private fun ground(composition: AgeComposition): Double = score(composition).from(Source.GROUND).sumOf { it.value }

        private fun monsters(danger: Danger): Double = danger.from(Source.CREATURES).sumOf { it.value }

        private fun oneTerritory(): AgeComposition = AgeComposition(terrains = listOf(Terrain.HILLS))

        private fun spawning(vararg lives: String): AgeComposition =
            oneTerritory().withOptions(Aspect.SPAWNS, Spawns.LIVES.name, lives.toList())

        private fun growing(vararg placed: String): AgeComposition =
            oneTerritory().withOptionsFor(Aspect.FEATURES, 0, Features.PLACES.name, placed.toList())

        /** Two landforms dividing the Age, ordinary rock in the first and [second] in the other. */
        private fun twoTerritories(shares: List<Double>, second: String): AgeComposition =
            AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.ERODED))
                .copy(spreads = Spreads().withShares(Aspect.TERRAIN, shares))
                .withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf(ORDINARY_STONE))
                .withOptionsFor(Aspect.TERRAIN, 1, Terrain.STONE.name, listOf(second))
    }
}
