package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.location
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Skew
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.AgeTemplate
import com.mojang.serialization.JsonOps
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Terrain
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * That what we write to disk is what we read back, and that an Age already written still names
 * something. A recipe is the *only* record of what an Age is, so one that fails to round-trip does not
 * corrupt a save — it silently replaces someone's world with a different one.
 *
 * **Registry-free by design**, so it runs offline in a second. That is also why [CodecCheck] exists:
 * staying registry-free means this file never loads a chunk generator.
 */
class RecipeCheck : FunSpec({

    /** Every preset survives the trip to NBT and back, unchanged — composed and bespoke alike. */
    withData(nameFn = { "the '${it.key}' preset round-trips" }, AgePreset.entries.toList()) { preset ->
        roundTrips(AgeRecipe(AgeRecipe.worldFor(preset), seed = SAMPLE_SEED), preset.key)
    }

    /**
     * Every preset of every aspect survives too. "Every [AgePreset] round-trips" no longer reaches most of
     * what a recipe can say, an aspect preset only appearing above if some demo Age names it — so each is
     * asked directly, and a family can gain a member without gaining a blind spot.
     */
    withData(
        nameFn = { "the '${it.aspect.key}=${it.key}' aspect preset round-trips" },
        everyAspectPreset(),
    ) { preset ->
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS)).withPreset(preset.aspect, preset.key)
        roundTrips(
            AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED),
            "${preset.aspect.key}=${preset.key}",
        )
    }

    /**
     * An option nobody recognises comes back out again — the invariant most easily lost to a well-meant
     * cleanup, and it has to survive the *write* as well as the read.
     */
    test("options it cannot understand round-trip") {
        val composition = AgeComposition(terrains = listOf(Terrain.PYRAMIDS))
            .withOption(Aspect.TERRAIN, Terrain.ARRANGEMENT.name, "rings")
            .withOption(Aspect.TERRAIN, "elevation", "towering")
        check(composition.unknownOptions == listOf("landmass.elevation")) {
            "Expected 'elevation' to be reported as unrecognised, got ${composition.unknownOptions}"
        }
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED), "unknown options")
        check(decoded.composition?.options?.of(Aspect.TERRAIN)?.chosen?.get("elevation") == listOf("towering")) {
            "An unrecognised option was dropped in the round trip: $decoded"
        }
    }

    /**
     * A parameter holding several values survives, **and one holding a single value is spelled exactly as
     * it always was** — the load-bearing half. A lone value spells as a bare string, which is what every
     * recipe on disk contains; if that regresses, every Age ever written reads back wrong and nothing else
     * would notice.
     */
    test("a mingled parameter round-trips") {
        val one = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withOption(Aspect.TERRAIN, Terrain.STONE.name, "minecraft:blackstone")
        val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, AgeRecipe(AgeWorld.Composed(one), SAMPLE_SEED))
            .getOrThrow { problem -> IllegalStateException("a single material would not encode: $problem") }
        check("[" !in encoded.toString()) {
            "A lone option was written as a list, so every recipe on disk now reads differently: $encoded"
        }
        roundTrips(AgeRecipe(AgeWorld.Composed(one), SAMPLE_SEED), "one material")

        val mingled = one.withOptions(
            Aspect.TERRAIN,
            Terrain.STONE.name,
            listOf("minecraft:blackstone", "minecraft:tuff"),
        )
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(mingled), SAMPLE_SEED), "two mingled materials")
        check(decoded.composition?.options?.of(Aspect.TERRAIN)?.allOf(Terrain.STONE)?.size == 2) {
            "A mingled parameter came back as ${decoded.composition?.options?.of(Aspect.TERRAIN)}"
        }
        val spelling = mingled.toString()
        check("landmass.stone=minecraft:blackstone,minecraft:tuff" in spelling) {
            "A mingled parameter spells itself wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == mingled) { "'$spelling' does not read back as itself" }
    }

    /**
     * **The template survives the round trip.** It is the base of the recipe (`the-world-model.md` §4): an
     * Age rebuilds from its record on every open, so a world that came back overworld-shaped where it was
     * written infernal would be a different world under the same book.
     */
    test("the world an Age was written over round-trips") {
        val written = AgeRecipe(
            AgeWorld.Composed(AgeComposition(terrains = listOf(Terrain.HILLS))),
            seed = 11L,
            template = AgeTemplate.INFERNAL,
        )
        val encoded = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, written).getOrThrow()
        val read = AgeRecipe.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()
        check(read.template == AgeTemplate.INFERNAL) { "the recipe came back on ${read.template}" }
    }

    /**
     * **A writer's page and a recipe's key are two names, and these are the two aspects that differ.**
     *
     * `terrain` became `landmass` and `carvers` became `depths` for readability, and each rename was a
     * save-format change until the two were told apart. Pack content and commands are pages, because they
     * are re-read on every load and a rename costs an edit to what ships; a save is keys, because a rename
     * there costs every Age ever written.
     */
    test("a writer's page and the recipe's key are told apart") {
        val written = AgeComposition(terrains = listOf(Terrain.HILLS), carvers = listOf(Carvers.CAVES))
            .withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf("minecraft:tuff"))
        check("${Aspect.TERRAIN.page}=" in written.toString()) {
            "the spelling a person reads does not say '${Aspect.TERRAIN.page}': $written"
        }

        val recipe = AgeRecipe(AgeWorld.Composed(written), seed = SAMPLE_SEED)
        val json = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow().toString()
        for (aspect in listOf(Aspect.TERRAIN, Aspect.CARVERS)) {
            check(aspect.page !in json) { "the save recorded '${aspect.page}', which is the name that moves: $json" }
            check(aspect.key in json) { "the save does not record '${aspect.key}': $json" }
        }
    }

    /**
     * **Vanilla's rock is the whole world's or none of it**, the field tree and vanilla's router being
     * either/or. Composing it beside a landform of ours used to parse cleanly and then throw out of the
     * generator, where there is nobody to tell.
     */
    test("vanilla's rock cannot divide the world with ours") {
        val alone = AgeComposition.parse("landmass=vanilla sea=water")
        check(alone.isSuccess) { "`landmass=vanilla` alone was refused: ${alone.exceptionOrNull()?.message}" }

        val shared = AgeComposition.parse("landmass=vanilla,hills sea=water")
        check(shared.isFailure) { "`landmass=vanilla,hills` was composed rather than refused" }
        check(Terrain.HILLS.key in shared.exceptionOrNull()?.message.orEmpty()) {
            "the refusal does not say what it clashed with: ${shared.exceptionOrNull()?.message}"
        }
    }

    /**
     * **A cast survives the round trip, bodies and all.** An Age is rebuilt from its recipe on every open,
     * so a sky that came back with one sun where three were written would be a different world under the
     * same book — and a body described by nothing at all is the case that would go first, having no options
     * to be remembered by.
     */
    test("a cast of bodies round-trips") {
        val written = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withCastOf(Aspect.SUN, 3)
            .withOptionsFor(Aspect.SUN, 1, Sky.SUNCOLOUR.name, listOf("red"))
        check(written.membersIn(Aspect.SUN) == 3) { "the cast was not written: ${written.membersIn(Aspect.SUN)}" }
        val read = AgeComposition.parse(written.toString()).getOrThrow()
        check(read.membersIn(Aspect.SUN) == 3) {
            "'$written' came back with ${read.membersIn(Aspect.SUN)} suns rather than three"
        }
        check(read.optionsFor(Aspect.SUN, 1).of(Sky.SUNCOLOUR) == "red") {
            "the second sun lost its colour: '$read'"
        }
    }

    /**
     * A population's `only` and `except` survive the trip. A marked value is not a well-formed
     * `Identifier`, so reading it through `allOf` filters it out and the exclusion simply does not
     * happen — which is why `Options.claimsOn` strips the mark first, and why this asserts the result
     * rather than the spelling.
     */
    test("an excluded structure round-trips") {
        val written = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withOptions(
                Aspect.STRUCTURES,
                Structures.BUILT.name,
                listOf(
                    "minecraft:villages[only,amount=4]",
                    "minecraft:woodland_mansions",
                    "minecraft:ocean_monuments[except]",
                ),
            )
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(written), SAMPLE_SEED), "a steered population")
        val asked = Skew.of(
            decoded.composition?.optionsFor(Aspect.STRUCTURES, 0)?.claimsOn(Structures.BUILT).orEmpty(),
        )
        check(asked.exclusive) { "'only' did not survive the round trip: $asked" }
        check(asked.wanted.map { it.value } == listOf("minecraft:villages", "minecraft:woodland_mansions")) {
            "the wanted sets came back as ${asked.wanted}"
        }
        // All three marks at once, because they are read from one string and a greedy parse would eat the others.
        check(asked.wanted.first().density == TEEMING) {
            "a density rung did not survive beside an 'only': ${asked.wanted.first()}"
        }
        check(asked.wanted.last().density == Rung.ORDINARY) { "an unmarked value invented a density rung" }
        check(asked.struck == listOf("minecraft:ocean_monuments")) { "the struck sets came back as ${asked.struck}" }

        val spelling = written.toString()
        check(
            "structures.built=minecraft:villages[only,amount=4],minecraft:woodland_mansions," +
                "minecraft:ocean_monuments[except]"
                in spelling,
        ) {
            "a steered population spells itself wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == written) { "'$spelling' does not read back as itself" }
    }

    /**
     * Two territories of one aspect, steered differently — copper spires beside andesite hills, which an
     * aspect-wide `Options` could not hold at all. Both directions, because the collapse is as
     * load-bearing as the division: territories that *agree* must still spell themselves once, or every
     * recipe on disk reads differently.
     */
    test("two territories are steered apart") {
        val divided = AgeComposition(terrains = listOf(Terrain.SPIRE_ISLANDS, Terrain.HILLS))
            .withOptionsFor(Aspect.TERRAIN, 0, Terrain.STONE.name, listOf("minecraft:copper_block"))
            .withOptionsFor(Aspect.TERRAIN, 1, Terrain.STONE.name, listOf("minecraft:andesite"))

        for ((member, expected) in listOf("minecraft:copper_block", "minecraft:andesite").withIndex()) {
            val held = divided.optionsFor(Aspect.TERRAIN, member).allOf(Terrain.STONE)
            check(held == listOf(expected)) { "Territory $member holds $held rather than $expected" }
        }

        val spelling = divided.toString()
        check("landmass=spire_islands[stone=minecraft:copper_block],hills[stone=minecraft:andesite]" in spelling) {
            "Two steered territories spell themselves wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == divided) { "'$spelling' does not read back as itself" }

        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(divided), SAMPLE_SEED), "two territories steered apart")
        check(
            decoded.composition?.optionsFor(Aspect.TERRAIN, 1)?.allOf(Terrain.STONE) == listOf("minecraft:andesite"),
        ) {
            "The second territory's material did not survive the codec: ${decoded.composition}"
        }

        // The other half: territories that agree collapse back to one entry, spelled the way they always were.
        val agreeing = AgeComposition(terrains = listOf(Terrain.SPIRE_ISLANDS, Terrain.HILLS))
            .withOption(Aspect.TERRAIN, Terrain.STONE.name, "minecraft:tuff")
        val together = agreeing.toString()
        check("landmass.stone=minecraft:tuff" in together) {
            "Agreeing territories stopped spelling themselves once: '$together'"
        }
        check(AgeComposition.parse(together).getOrThrow() == agreeing) { "'$together' does not read back as itself" }
    }

    /**
     * What a composition prints is what the composer reads back — two hand-written halves of one grammar,
     * and nothing but this makes them agree. Drifting apart means `/age list` prints recipes
     * `/age compose` cannot accept, which survives for months because each half looks right alone.
     */
    test("compositions are spelled the way they are read") {
        val compositions = everyAspectPreset().map { preset ->
            AgeComposition(terrains = listOf(Terrain.HILLS)).withPreset(preset.aspect, preset.key)
        } + AgeComposition(terrains = listOf(Terrain.PYRAMIDS))
            .withOption(Aspect.TERRAIN, Terrain.ARRANGEMENT.name, "rings")
            .withOption(Aspect.SEA, Sea.DEPTH.name, "deep")

        for (composition in compositions) {
            val spelling = composition.toString()
            val read = AgeComposition.parse(spelling).getOrThrow()
            check(read == composition) { "'$spelling' reads back as '$read', which is not what wrote it" }
        }
    }

    /**
     * An Age written before aspects existed still opens, and opens as the same Age.
     *
     * The migration lives in a codec default rather than anywhere obvious, so it is exactly the kind of
     * path that goes unexercised until somebody's save is the thing exercising it.
     */
    test("recipes written before slots still read") {
        for (preset in AgePreset.entries) {
            val written = CompoundTag().apply {
                put("preset", StringTag.valueOf(preset.key))
                putLong("seed", SAMPLE_SEED)
                putInt(GENERATOR_VERSION_KEY, PRE_SLOTS_GENERATOR_VERSION)
            }
            val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
                .getOrThrow { problem ->
                    IllegalStateException("a pre-aspects '${preset.key}' would not load: $problem")
                }
            check(decoded.world == AgeRecipe.worldFor(preset)) {
                "A pre-aspects '${preset.key}' migrated to ${decoded.world}, not ${AgeRecipe.worldFor(preset)}"
            }
            check(decoded.generatorVersion == PRE_SLOTS_GENERATOR_VERSION) {
                "Migration overwrote the stamp on '${preset.key}', which is how an Age forgets what made it"
            }
        }
    }

    /**
     * A terrain aspect holding several presets survives, and prints in a form the composer reads back.
     *
     * The set is the whole point of regions (§3.4), and it is the part of the recipe most recently changed
     * shape — so it is the part most likely to round-trip as *something*, just not the same something.
     */
    test("a set-valued landform round-trips") {
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS, Terrain.CAVERNS))
            .withPreset(Aspect.SEA, Sea.WATER.key)
            .withPresets(Aspect.SEA, listOf(Sea.WATER.key, Sea.LAVA.key))
            .withPresets(Aspect.CARVERS, listOf(Carvers.CAVES.key, Carvers.SOLID.key))
        val recipe = AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED, character = SAMPLE_CHARACTER)
        val decoded = roundTrips(recipe, "a three-terrain Age")

        check(decoded.composition?.terrains == composition.terrains) {
            "The terrain set came back as ${decoded.composition?.terrains}, not ${composition.terrains}"
        }
        check(decoded.composition?.seas == composition.seas) {
            "The sea set came back as ${decoded.composition?.seas}"
        }
        check(decoded.composition?.carvers == composition.carvers) {
            "The carving set came back as ${decoded.composition?.carvers}"
        }
        check(decoded.character == SAMPLE_CHARACTER) {
            "An Age's character did not survive: ${decoded.character}, not $SAMPLE_CHARACTER"
        }

        val spelling = composition.toString()
        check("landmass=hills,pillars,caverns" in spelling) { "A set should print comma-joined, got '$spelling'" }
        check("depths=caves,solid" in spelling) { "So should a carving set, got '$spelling'" }
        // Ids, because the sea aspect is open (design §3.1) — the referent is the value, not a preset naming it.
        check("sea=minecraft:water,minecraft:lava" in spelling) { "And a sea set, got '$spelling'" }
        check(AgeComposition.parse(spelling).getOrThrow() == composition) {
            "'$spelling' does not read back as what wrote it"
        }
    }

    /**
     * An Age written before terrain was a set still opens, as the single-terrain Age it was.
     *
     * Its `terrain` is a bare string where today's is a list, and both spellings have to keep working —
     * this is the second time that field has changed shape, and the first migration is still load-bearing.
     */
    test("recipes written before regions still read") {
        val written = CompoundTag().apply {
            put(
                "world",
                CompoundTag().apply {
                    put("kind", StringTag.valueOf("composed"))
                    put("terrain", StringTag.valueOf(Terrain.ERODED.key))
                    put("sea", StringTag.valueOf(Sea.WATER.key))
                },
            )
            putLong("seed", SAMPLE_SEED)
            putInt(GENERATOR_VERSION_KEY, PRE_REGIONS_GENERATOR_VERSION)
        }
        val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
            .getOrThrow { problem -> IllegalStateException("a pre-regions recipe would not load: $problem") }

        check(decoded.composition?.terrains == listOf(Terrain.ERODED)) {
            "A pre-regions terrain read back as ${decoded.composition?.terrains}"
        }
        check(decoded.character == AgeCharacter.LEGACY) {
            "A recipe with no character should read as LEGACY, not ${decoded.character}"
        }
        check(decoded.generatorVersion == PRE_REGIONS_GENERATOR_VERSION) { "Migration overwrote the stamp" }
    }

    /**
     * An Age somebody *wrote* keeps its words and its flaws. Both are provenance — nothing rebuilds a
     * world from them, which is exactly why they are easy to lose without noticing, and the flaws are what
     * makes an unstable Age diagnosable rather than merely punished (§5.1).
     */
    test("a written Age keeps its words and its flaws") {
        val composition = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withPresets(Aspect.CARVERS, listOf(Carvers.CAVES.key, Carvers.SOLID.key))
        val instability = Instability(
            listOf(
                Flaw(
                    Register.FRACTURE, listOf("riddled", "unbroken"), Aspect.CARVERS,
                    listOf("cavernous", "solid"), severity = 3,
                ),
                Flaw(
                    Register.TENSION, listOf("riddled", "unbroken"), Aspect.CARVERS,
                    listOf("cavernous", "solid"), severity = 1,
                ),
            ),
        )
        val recipe = AgeRecipe(
            AgeWorld.Composed(composition),
            seed = SAMPLE_SEED,
            character = SAMPLE_CHARACTER,
            instability = instability,
            words = listOf("verdant", "lifeless"),
        )
        val decoded = roundTrips(recipe, "a written Age")

        check(decoded.words == listOf("verdant", "lifeless")) { "the words were lost: ${decoded.words}" }
        check(decoded.instability.flaws == instability.flaws) { "the flaws were lost: ${decoded.instability.flaws}" }
        check(decoded.instability.index == instability.index) {
            "the index came back as ${decoded.instability.index}, not ${instability.index}"
        }
        // The severity is stored rather than recomputed, so retuning the charges cannot rewrite an Age that
        // has already been written — the same argument as persisting the recipe rather than the words (§4.6).
        check(instability.index == EXPECTED_SAMPLE_INDEX) { "the sample flaws no longer sum to what they did" }
    }

    /**
     * An Age whose aspects divide unevenly keeps its shares, through NBT and through its own spelling.
     * Shares are generation inputs, so losing one hands back a different world on the next open — and an
     * even division must keep its old spelling, or every recipe written before shares reads as something
     * else.
     */
    test("an uneven division round-trips") {
        val uneven = AgeComposition(terrains = listOf(Terrain.HILLS))
            .withPresets(
                Aspect.CARVERS,
                listOf(Carvers.CAVES.key, Carvers.POROUS.key, Carvers.FLOODED_CAVES.key),
                listOf(Share.EVEN, A_QUARTER, A_SIXTEENTH),
            )
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(uneven), seed = SAMPLE_SEED), "an uneven division")
        check(
            decoded.composition?.spreadOf(Aspect.CARVERS)?.shares == listOf(Share.EVEN, A_QUARTER, A_SIXTEENTH),
        ) {
            "the shares came back as ${decoded.composition?.spreadOf(Aspect.CARVERS)?.shares}"
        }

        val spelling = uneven.toString()
        check("depths=caves,porous@0.25,flooded_caves@0.06" in spelling) {
            "an uneven division spells itself wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == uneven) { "'$spelling' does not read back as itself" }

        // An even division says nothing about shares at all, which is what keeps a hand-composed Age — and
        // every recipe written before shares existed — spelled exactly as it was.
        val even = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS))
        check("@" !in even.toString()) { "an even division should not mention shares: '$even'" }
        check(even.spreadOf(Aspect.TERRAIN).shares == listOf(Share.EVEN, Share.EVEN)) {
            "an unmentioned division should be even, not ${even.spreadOf(Aspect.TERRAIN).shares}"
        }
    }

    /**
     * A divided Age keeps the **form** of each boundary as well as the ground either side of it, through
     * NBT and through its own spelling. It is a generation input like a share, and a lost one hands back a
     * flat world where there was a cliff.
     *
     * Two boundaries at once, deliberately: they are per population now, so a spelling that named only the
     * one it happened to walk first would pass a single-seam Age and lose the second.
     */
    test("the form of each boundary round-trips") {
        val riven = AgeComposition(terrains = listOf(Terrain.HILLS, Terrain.PILLARS))
            .withPresets(Aspect.CARVERS, listOf(Carvers.CAVES.key, Carvers.SOLID.key))
            .withOptions(Aspect.TERRAIN, Spread.SEAM, listOf(Seam.RIFT.key))
            .withOptions(Aspect.CARVERS, Spread.SEAM, listOf(Seam.FUZZED.key))
        val decoded = roundTrips(AgeRecipe(AgeWorld.Composed(riven), seed = SAMPLE_SEED), "a riven Age")
        check(decoded.composition?.spreadOf(Aspect.TERRAIN)?.seam == Seam.RIFT) {
            "the rift came back as ${decoded.composition?.spreadOf(Aspect.TERRAIN)?.seam}"
        }
        check(decoded.composition?.spreadOf(Aspect.CARVERS)?.seam == Seam.FUZZED) {
            "the fuzz came back as ${decoded.composition?.spreadOf(Aspect.CARVERS)?.seam}"
        }

        val spelling = riven.toString()
        check("landmass.seam=rift" in spelling && "depths.seam=fuzzed" in spelling) {
            "a riven Age spells its boundaries wrong: '$spelling'"
        }
        check(AgeComposition.parse(spelling).getOrThrow() == riven) { "'$spelling' does not read back as itself" }

        // An undivided Age has no boundary and says nothing about one, so every recipe written before this
        // is spelled exactly as it was.
        val whole = AgeComposition(terrains = listOf(Terrain.HILLS))
        check(Spread.SEAM !in whole.toString()) { "an undivided Age should mention no seam: '$whole'" }
    }

    /**
     * An Age written before words existed still opens, and opens as a coherent Age with nothing to say for
     * itself — which is the truth about it, since nobody wrote it from a sentence.
     */
    test("recipes written before words still read") {
        val written = CompoundTag().apply {
            put(
                "world",
                CompoundTag().apply {
                    put("kind", StringTag.valueOf("composed"))
                    put("terrain", StringTag.valueOf(Terrain.HILLS.key))
                },
            )
            putLong("seed", SAMPLE_SEED)
            putInt(GENERATOR_VERSION_KEY, PRE_WORDS_GENERATOR_VERSION)
        }
        val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, written)
            .getOrThrow { problem -> IllegalStateException("a pre-words recipe would not load: $problem") }

        check(decoded.words.isEmpty()) { "a recipe with no words read back with ${decoded.words}" }
        check(decoded.instability == Instability.NONE) {
            "a recipe with no flaws read back as ${decoded.instability}"
        }
        check(decoded.generatorVersion == PRE_WORDS_GENERATOR_VERSION) { "Migration overwrote the stamp" }
    }

    /**
     * Every generator-kind string ever persisted still names a preset. **The list is frozen history, not a
     * mirror of the enum** — renaming an [AgePreset.key] compiles perfectly and orphans every Age written
     * with the old name, sending it to the fallback and handing the player a different world.
     */
    test("every written kind still resolves") {
        for (kind in LEGACY_KINDS) {
            checkNotNull(AgePreset.byKey(kind)) {
                "No preset named '$kind' any more — Ages written with it would fall back to Spire. " +
                    "Restore the key, or migrate those Ages deliberately in AgeSavedData."
            }
        }
    }

    /**
     * The generator stamp is actually written down. Declared `optionalFieldOf(name, default)` the codec
     * would *omit* it whenever it matched the current version, so an old Age would read back claiming to
     * have been made by whatever code is current — the one question the stamp exists to answer.
     */
    test("the generator version is stamped") {
        val recipe = AgeRecipe(AgeRecipe.worldFor(AgePreset.SPIRE), seed = SAMPLE_SEED)
        val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, recipe)
            .getOrThrow { problem -> IllegalStateException("recipe would not encode: $problem") }
        val fields = encoded as? CompoundTag ?: error("A recipe should encode to a compound, not $encoded")
        check(fields.contains(GENERATOR_VERSION_KEY)) {
            "A written recipe carries no '$GENERATOR_VERSION_KEY', so its generation could never be told " +
                "apart from today's"
        }
        // `getInt` answers with an `Optional` now, so the absent case has to be named; -1 is a stamp no
        // generation ever has, and the message beside it says which it was.
        val stamped = fields.getIntOr(GENERATOR_VERSION_KEY, NOT_STAMPED)
        check(stamped == AgeRecipe.CURRENT_GENERATOR_VERSION) {
            "Stamped generation $stamped, expected ${AgeRecipe.CURRENT_GENERATOR_VERSION}"
        }
    }
})

/** [recipe] written and read back, unchanged — the check every case above is a variation of. */
private fun roundTrips(recipe: AgeRecipe, what: String): AgeRecipe {
    val encoded = AgeRecipe.CODEC.encodeStart(NbtOps.INSTANCE, recipe)
        .getOrThrow { problem -> IllegalStateException("$what would not encode: $problem") }
    val decoded = AgeRecipe.CODEC.parse(NbtOps.INSTANCE, encoded)
        .getOrThrow { problem -> IllegalStateException("$what would not decode: $problem") }
    check(decoded == recipe) { "$what came back as $decoded, not $recipe" }
    return decoded
}

/**
 * Every preset a composition can be written from: every authored one, the three seas the mod names, and
 * **one referent naming content this pack does not have** — a recipe must hold an id from an uninstalled
 * mod and give it back unchanged, a save moving between modpacks being ordinary.
 */
private fun everyAspectPreset(): List<AspectPreset> =
    Aspect.entries.flatMap { it.authored } +
        listOf(Sea.NONE, Sea.WATER, Sea.LAVA, Sea(Identifier.parse("examplemod:creosote")))

/** Every generator kind that has ever been written into a save. Append-only; never edit a line. */
private val LEGACY_KINDS = listOf(
    "spire", "field", "pyramids", "pyrings", "pyrvaried",
    "hills", "shapes", "pillars", "caverns", "eroded",
    "vanilla", "vanillabare",
)

/** A character unlike the default in every field, so a lazy round trip cannot pass by accident. */
private val SAMPLE_CHARACTER = AgeCharacter(alignment = Alignment.INDEPENDENT, regionBlocks = 1600)

/**
 * **A linking book carries the Age it points at, and it has to survive being written down** (design §9,
 * "Losing the books").
 *
 * The whole value of it is a book that outlives its Age — which means the recipe is read back off an item
 * on disk long after everything that could have re-derived it is gone. A recipe that failed to round-trip
 * here would leave the book pointing at nothing, silently, exactly when it was needed.
 */
class LinkTargetCheck : FunSpec({
    test("a link target round-trips with the Age behind it") {
        MinecraftRegistries.ensureStoodUp()
        val composition = AgeComposition(terrains = listOf(Terrain.PYRAMIDS))
            .withOption(Aspect.TERRAIN, Terrain.STONE.name, "minecraft:blackstone")
        val recipe = AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED)
        val target = LinkTarget(
            dimension = ResourceKey.create(Registries.DIMENSION, "someage".location()),
            position = Vec3(1.5, 64.0, -2.5),
            yaw = 90.0f,
            name = "Some Age",
            recipe = recipe,
        )
        val encoded = LinkTarget.CODEC.encodeStart(NbtOps.INSTANCE, target)
            .getOrThrow { problem -> IllegalStateException("a link target would not encode: $problem") }
        val decoded = LinkTarget.CODEC.parse(NbtOps.INSTANCE, encoded)
            .getOrThrow { problem -> IllegalStateException("a link target would not decode: $problem") }
        check(decoded.recipe == recipe) { "the Age behind the book was lost: ${decoded.recipe}" }
        check(decoded == target) { "the link target came back as $decoded" }
    }

    /** A book to a vanilla dimension carries none, and must still be a legal book. */
    test("a link target with no Age behind it round-trips") {
        MinecraftRegistries.ensureStoodUp()
        val target = LinkTarget(
            dimension = Level.OVERWORLD,
            position = Vec3(0.0, 64.0, 0.0),
            yaw = 0.0f,
            name = "Overworld",
        )
        val encoded = LinkTarget.CODEC.encodeStart(NbtOps.INSTANCE, target)
            .getOrThrow { problem -> IllegalStateException("a bare link target would not encode: $problem") }
        val decoded = LinkTarget.CODEC.parse(NbtOps.INSTANCE, encoded)
            .getOrThrow { problem -> IllegalStateException("a bare link target would not decode: $problem") }
        check(decoded.recipe == null) { "an Age was invented behind an Overworld link: ${decoded.recipe}" }
        check(decoded == target) { "the link target came back as $decoded" }
    }
})

private const val GENERATOR_VERSION_KEY = "generator_version"

/** A stamp no generation ever has, so an absent one fails the comparison rather than passing by accident. */
private const val NOT_STAMPED = -1

/** What every Age written before aspects is stamped with. */
private const val PRE_SLOTS_GENERATOR_VERSION = 1

/** And what every Age written after aspects but before regions is stamped with. */
private const val PRE_REGIONS_GENERATOR_VERSION = 2

/** And what every Age written after every aspect became positional but before words could write one is. */
private const val PRE_WORDS_GENERATOR_VERSION = 5

/** What the sample flaws add up to: a division at exact precision, plus the tension behind it. */
private const val EXPECTED_SAMPLE_INDEX = 4

private const val SAMPLE_SEED = 0x5EED_A9EL

/** What `art/grammar/teeming.json` asks for — four times as many. */
private const val TEEMING = 4.0

/** Two shares an Age could plausibly hold, far enough apart to tell one territory from another. */
private const val A_QUARTER = 0.25
private const val A_SIXTEENTH = 0.06
