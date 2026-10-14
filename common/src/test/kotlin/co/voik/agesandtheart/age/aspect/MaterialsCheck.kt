package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries

/**
 * What a world may be made of (§3.2) — the rule, against the registry it is a rule about.
 *
 * **A ratchet rather than a list.** Naming every block a world may be would be the registry copied into a
 * check, so what is held here is the *count* and a handful of blocks nobody should be able to move without
 * arguing: a world of stone yes, a world of signs no. If a Minecraft version adds fifty blocks the count
 * moves and the floor below it does not, which is the same bargain `TagCoverageCheck` makes.
 */
@Tags(NEEDS_REGISTRIES)
class MaterialsCheck : FunSpec({

    val blocks by lazy {
        BuiltInRegistries.BLOCK.listElements().toList().map { it.key().identifier() to it.value() }
    }

    /**
     * **Enough to build a world out of, and nowhere near everything.** Measured at 394 on 26.1.2 with no
     * tags bound; the floor is what stops the rule quietly tightening into "stone and nothing else", which
     * is the failure a shape rule drifts towards when a proxy is swapped for a stricter one.
     */
    test("the rule leaves a writer plenty to build with") {
        val worlds = blocks.count { Materials.makesAWorld(it.second) }
        check(worlds >= ENOUGH_TO_CHOOSE_FROM) {
            "only $worlds blocks can be a world, which is fewer than the $ENOUGH_TO_CHOOSE_FROM this rule " +
                "was measured at — a proxy has been swapped for a stricter one"
        }
    }

    /** The ones the rule exists for, and the ones it must not take with them. */
    test("a world is something you can stand on") {
        for (name in WORLDS) {
            check(Materials.makesAWorld(blockNamed(blocks, name))) { "'$name' cannot be a world and should be" }
        }
        for (name in NOT_WORLDS) {
            check(!Materials.makesAWorld(blockNamed(blocks, name))) { "'$name' can be a world and should not be" }
        }
    }

    /**
     * **The expensive half.** A world of chests is one block entity per block, which is the cost that took
     * the entity out of a wound — and there is no render distance that helps, because the cost is in memory
     * and in chunk NBT rather than in drawing.
     */
    test("nothing carrying a block entity can be a world") {
        val carrying = blocks.filter { it.second.defaultBlockState().hasBlockEntity() }
        val allowed = carrying.filter { Materials.makesAWorld(it.second) }
        check(allowed.isEmpty()) {
            "these carry a block entity and are allowed as a world: ${allowed.joinToString(" ") { it.first.path }}"
        }
    }

    /**
     * The rock refuses through the same gate every reader already goes through — and **the skin does not**.
     *
     * A skin is one layer over rock that already holds you up, so a strange one costs a block of fall onto
     * solid ground; and a skin of *air* is how a writer says the ground wears nothing, which is a sentence
     * the rock could never be allowed. Only the fill is fatal.
     */
    test("the rock refuses what cannot be a world, and the skin refuses nothing") {
        check(Terrain.STONE.accepts("minecraft:stone")) { "the rock refused stone" }
        check(!Terrain.STONE.accepts("minecraft:oak_sign")) { "the rock accepted a sign" }
        check(Terrain.STONE.accepts(Parameter.UNCHANGED)) { "the rock refused its own default" }
        check(Surface.MATERIAL.accepts("minecraft:oak_sign")) { "the skin refused a sign, and should not" }
        check(Surface.MATERIAL.accepts("minecraft:air")) { "a skin of air is how the ground wears nothing" }
    }

    /**
     * **Rock does not fall.** Sand and gravel are worlds a player can stand on and still not rock: carved
     * under, they come down and bring down what is above, so a gravel Age with caves went to twenty-five
     * thousand falling blocks. The skin may still be one.
     */
    test("the rock refuses a block that falls, and the skin does not") {
        for (name in FALLING) {
            val block = blockNamed(blocks, name)
            check(Materials.makesAWorld(block)) { "'$name' stopped being a world, which this rule did not mean" }
            check(!Materials.makesARock(block)) { "'$name' can be the rock and falls" }
            check(!Terrain.STONE.accepts("minecraft:$name")) { "'$name' landmass is still a sentence" }
            check(Surface.MATERIAL.accepts("minecraft:$name")) { "the skin refused '$name', which dresses a desert" }
        }
        check(Materials.makesARock(blockNamed(blocks, "sandstone"))) { "sandstone cannot be the rock" }
    }

    /**
     * **What the grammar guide says a page applies to** is `Grammar.placesFor`, which asks `canBeMadeOf` —
     * so a block refused here is never offered as a landmass or a sea.
     */
    test("a part-block is offered as neither a landmass nor a sea") {
        for (name in listOf("cake", "cobweb", "stone_slab")) {
            check(!Aspect.TERRAIN.canBeMadeOf("minecraft:$name")) { "'$name landmass' is still a sentence" }
            check(!Aspect.SEA.canBeMadeOf("minecraft:$name")) { "'$name sea' is still a sentence" }
        }
        check(Aspect.TERRAIN.canBeMadeOf("minecraft:stone")) { "'stone landmass' stopped being a sentence" }
    }

    /** A fluid holds nobody up and is allowed, because an ocean world is a thing somebody meant. */
    test("a world may be a fluid") {
        check(Materials.makesAWorld(blockNamed(blocks, "water"))) { "an ocean world was refused" }
        check(Materials.makesAWorld(blockNamed(blocks, "lava"))) { "a world of lava was refused" }
    }

    /**
     * **A sea is never a block that checks what it stands on**: it fills a volume over stone and caves, and
     * a sea of bamboo broke and dropped itself into tens of thousands of items. Nor is it anything short of
     * a whole cube: a sea of cobweb barely ran.
     */
    test("a sea refuses what needs support, and what is not a whole cube") {
        for (name in SEAS) {
            check(Materials.makesASea(blockNamed(blocks, name))) { "'$name' cannot be a sea and should be" }
        }
        for (name in NOT_SEAS) {
            check(!Materials.makesASea(blockNamed(blocks, name))) { "'$name' can be a sea and should not be" }
        }
        for (name in FALLING) {
            check(!Materials.makesASea(blockNamed(blocks, name))) { "'$name' can be a sea and falls" }
        }
        check(!Aspect.SEA.canBeMadeOf("minecraft:bamboo")) { "'bamboo sea' is still a sentence" }
        check(!Aspect.SEA.canBeMadeOf("minecraft:cobweb")) { "'cobweb sea' is still a sentence" }
        check(Aspect.SEA.canBeMadeOf("minecraft:water")) { "'water sea' stopped being a sentence" }
    }
})

private fun blockNamed(blocks: List<Pair<net.minecraft.resources.Identifier, net.minecraft.world.level.block.Block>>, path: String) =
    blocks.first { it.first.path == path }.second

/** Measured 2026-09-01 against 26.1.2, with no tags bound — see the spec's own note. */
private const val ENOUGH_TO_CHOOSE_FROM = 380

private val WORLDS = listOf("stone", "sand", "gravel", "glass", "pumpkin", "obsidian", "gold_block")

private val FALLING = listOf("sand", "red_sand", "gravel", "white_concrete_powder")

private val NOT_WORLDS = listOf(
    "oak_sign", "chest", "white_carpet", "stone_slab", "oak_stairs", "iron_chain", "scaffolding", "torch", "cake",
)

private val SEAS = listOf(
    "air", "water", "lava", "ice", "frosted_ice", "magma_block", "stone", "glass",
)

private val NOT_SEAS = listOf(
    "bamboo", "sugar_cane", "cactus", "kelp", "poppy", "short_grass", "torch", "white_carpet", "snow", "chest",
    "cobweb", "stone_slab", "cake",
)
