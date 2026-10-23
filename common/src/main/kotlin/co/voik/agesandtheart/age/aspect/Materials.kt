package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import java.lang.reflect.Method

/**
 * What an Age's **rock** may be made of — the fill a world is built from (§3.2's material parameters).
 *
 * **The fear this answers is an Age of wooden signs**: a writer names a block, the whole world is built of
 * it, and there is nothing to stand on. A sign has no collision, so the ground is not ground and a player
 * arrives inside the world and falls out of the bottom of it. That is not a hazard an Age is allowed to
 * be, because it is not a *place* — §7.7's dangerous Age is one you have to survive, not one you cannot
 * enter.
 *
 * **The rule is one question: is it a whole block you cannot fall through.** Measured against the 26.1.2
 * registry rather than guessed — of 1162 blocks that are neither air nor fluid, 437 have a full collision
 * cube and 394 of those carry no block entity. Stone, sand, gravel, glass, leaves and a pumpkin are worlds;
 * a cake, a chest, a sign, a carpet, a slab, a chain and a scaffold are not.
 *
 * **A block entity is the other half, and it is the expensive half.** A world of chests is one block entity
 * per block — held in memory, written into chunk NBT and walked on every load and save. That cost is what
 * took the entity out of a *wound*, where sixteen to a chunk was ten thousand objects at an ordinary render
 * distance; a world made of them is millions of the things and there is no render distance that helps.
 *
 * **Two proxies were tried and thrown away**, which is worth knowing before either is proposed again:
 *
 * - **`PushReaction.POPPED`** as "fragile" reads well and is wrong. Leaves and pumpkins are destroyed by
 *   pistons and are perfectly good worlds, and it kept carpets, which are not.
 * - **Stacking "has collision" with "takes no shape from its neighbours"** keeps 579 blocks and lets in
 *   carpets, trapdoors, chains, end rods, piston heads and anvils. A full cube is the simpler question and
 *   the better answer.
 *
 * **This constrains the material, never the word.** A sign still has a word, and `Features` and minting
 * never ask this: an Age scattered with chain obelisks is a thing a writer should be able to make, and
 * only the ground it stands on has to hold somebody up.
 */
object Materials {

    /**
     * **What a named block is laid as, where that is not itself** — the one place a word's block and the
     * block a world is built of part company.
     *
     * Living yema is the case (design §7.1.2): it is a word, and a world written of it comes out of **dead**
     * yema, so the only living yema anywhere is what a tended tree grew — writing it cannot stand in for
     * tending it. Read wherever a block id from a sentence becomes a block to place: the rock's and the
     * skin's materials, the sea, and a minted formation's substance.
     */
    private val STAND_INS: Map<Identifier, Identifier> = mapOf(
        "paper_tree_log".location() to "dead_paper_tree_log".location(),
    )

    /** [named] as a world is built of it — itself, unless it has a stand-in. */
    fun laidAs(named: Identifier): Identifier = STAND_INS[named] ?: named

    /**
     * Blocks the shape rule keeps that a rock should not be made of anyway — behaviour rather than form,
     * which nothing about a block's shape can answer.
     *
     * Leaves are the case: a full cube, no block entity, and they **decay** where no log stands near, so a
     * world of them would quietly evaporate after it was written. A better answer exists — place them
     * `persistent` — and it is a placement change rather than a rule, so this is the honest stopgap.
     */
    val INVALID_FOR_TERRAIN: TagKey<Block> = TagKey.create(Registries.BLOCK, "invalid_for_terrain".location())

    /**
     * Whether an Age's rock may be made of [block] — see the rule above.
     *
     * There is no exception channel: a block that is not a whole cube is never a world. Cake was one, and a
     * cake Age ran badly with no cake in sight. Nor is one that checks what it stands on: a chorus flower is a
     * whole cube that breaks off anything but end stone or chorus, so every one mined would bring down the next.
     *
     * **The refusal tag is empty offline**, since a tag binds on a server: the shape rule is the whole of
     * what an offline check sees. That is the same bargain `DerivedWords.DOES_NOT_HAVE_A_WORD` makes.
     */
    fun makesAWorld(block: Block): Boolean {
        val state = block.defaultBlockState()
        // Nothing is a world made of nothing, and no tag may say otherwise.
        if (state.isAir) return false
        if (state.typeHolder().`is`(INVALID_FOR_TERRAIN)) return false
        if (state.hasBlockEntity()) return false
        // **A fluid is allowed though nothing stands on it**, and the distinction is accident against
        // intent: an ocean world and a world of lava are Ages somebody meant, with a boat and a potion as
        // the answers, where a world of signs is a block nobody thought of as a world at all.
        if (state.block is LiquidBlock) return true
        return isWholeCube(state) && !needsSupport(block)
    }

    private fun isWholeCube(state: BlockState): Boolean =
        Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))

    /**
     * Whether an Age's rock may be made of [block]: a world ([makesAWorld]) that does not fall.
     *
     * Sand, gravel, concrete powder and an anvil have nothing under them wherever the rock is carved, and
     * each one that comes down frees the one above it. A gravel Age with flooded caves reached twenty-five
     * thousand falling blocks within seventeen seconds of server time. The skin may still be one, being a
     * single layer over rock that holds.
     */
    fun makesARock(block: Block): Boolean = makesAWorld(block) && block !is FallingBlock

    /** The same, of whatever a writer named — false where the pack has no such block. */
    fun makesARock(named: String): Boolean = blockNamed(named)?.let(::makesARock) ?: false

    /** What opens a material query: `#frozen` asks for a frozen block, where `minecraft:ice` names one. */
    const val QUERY_MARK = "#"

    /**
     * Whether [value] asks for a material by what it is like rather than by name. Our tags carry no
     * namespace, which is what tells `#frozen` from a block tag such as `#minecraft:ice`.
     */
    fun isQuery(value: String): Boolean =
        value.startsWith(QUERY_MARK) && ':' !in value && value.length > QUERY_MARK.length

    /** The same, of whatever a writer named — false where the pack has no such block. */
    fun makesAWorld(named: String): Boolean = blockNamed(named)?.let(::makesAWorld) ?: false

    /** Blocks a sea may not be made of though the sea's rule would allow them. */
    val INVALID_FOR_SEA: TagKey<Block> = TagKey.create(Registries.BLOCK, "invalid_for_sea".location())

    /**
     * Whether an Age's sea may be made of [block]: air, a fluid, or a whole cube that carries no block entity
     * and needs no support. A sea fills a volume over whatever the rock left, so a block that checks what it
     * stands on breaks wherever that is stone or a cave, and drops itself — a sea of bamboo became tens of
     * thousands of items and a server ticking twice a second. A sea of cobweb, which is not a whole cube,
     * held up but barely ran.
     *
     * A block that falls is refused for the rock's reason ([makesARock]): the volume a sea fills is over
     * caves.
     *
     * Air is an open sea. The refusal tag is empty offline, as the rock's is.
     */
    fun makesASea(block: Block): Boolean {
        val state = block.defaultBlockState()
        if (state.isAir) return true
        if (state.typeHolder().`is`(INVALID_FOR_SEA)) return false
        if (block is FallingBlock) return false
        if (state.hasBlockEntity()) return false
        if (state.block is LiquidBlock) return true
        return isWholeCube(state) && !needsSupport(block)
    }

    /** The same, of whatever a writer named — false where the pack has no such block. */
    fun makesASea(named: String): Boolean = blockNamed(named)?.let(::makesASea) ?: false

    /**
     * Whether [block] has a survival rule at all, read as whether its class overrides `canSurvive`.
     * Asking the rule itself needs a whole `LevelReader` to answer against; every block that checks what
     * it stands on or hangs from says so by overriding it, and a block that might survive some
     * arrangements is refused with the rest.
     */
    private fun needsSupport(block: Block): Boolean =
        generateSequence<Class<*>>(block.javaClass) { it.superclass }
            .takeWhile { it != BlockBehaviour::class.java }
            .any { owner -> owner.declaredMethods.any(::isCanSurvive) }

    private fun isCanSurvive(method: Method): Boolean =
        method.name == "canSurvive" && method.parameterTypes.contentEquals(CAN_SURVIVE_PARAMETERS)

    private val CAN_SURVIVE_PARAMETERS =
        arrayOf(BlockState::class.java, LevelReader::class.java, BlockPos::class.java)

    /** Whether the block [named] has a fluid in it, which is the whole of what a spring asks of its substance. */
    fun holdsAFluid(named: String?): Boolean =
        named?.let(::blockNamed)?.defaultBlockState()?.fluidState?.isEmpty == false

    private fun blockNamed(named: String): Block? {
        val id = Identifier.tryParse(named) ?: return null
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null)
    }
}
