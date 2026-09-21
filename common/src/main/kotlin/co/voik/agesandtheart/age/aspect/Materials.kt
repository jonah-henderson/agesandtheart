package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock

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
     * Blocks the shape rule keeps that a rock should not be made of anyway — behaviour rather than form,
     * which nothing about a block's shape can answer.
     *
     * Leaves are the case: a full cube, no block entity, and they **decay** where no log stands near, so a
     * world of them would quietly evaporate after it was written. A better answer exists — place them
     * `persistent` — and it is a placement change rather than a rule, so this is the honest stopgap.
     */
    val INVALID_FOR_TERRAIN: TagKey<Block> = TagKey.create(Registries.BLOCK, "invalid_for_terrain".location())

    /**
     * And blocks the shape rule refuses that a rock may be made of anyway — the exception channel, for
     * where one question is wrong about one block.
     *
     * Cake is what it was made for: seven sixteenths high, so not a full cube, and a world of it is
     * exactly the sort of thing the Art should be able to say.
     */
    val VALID_FOR_TERRAIN: TagKey<Block> = TagKey.create(Registries.BLOCK, "valid_for_terrain".location())

    /**
     * Whether an Age's rock may be made of [block] — see the rule above.
     *
     * **Both tags are empty offline**, since a tag binds on a server: the shape rule is the whole of what
     * an offline check sees, and holding the exceptions honest is a server check's job. That is the same
     * bargain `DerivedWords.FORBIDDEN` already makes.
     */
    fun makesAWorld(block: Block): Boolean {
        val state = block.defaultBlockState()
        // Nothing is a world made of nothing, and no tag may say otherwise.
        if (state.isAir) return false
        val holder = state.typeHolder()
        // **Refusal first**, so a block written into both files is refused: a contradiction between two
        // authored lists is a mistake, and the safe way to read a mistake is the strict one.
        if (holder.`is`(INVALID_FOR_TERRAIN)) return false
        // **And the exception skips the whole rule, block entity and all.** That is a real way to make a
        // world nobody can load, and it is left possible: writing a block into a tag file is as deliberate
        // as an author gets, and a rule with no override is one that is simply wrong where it is wrong.
        if (holder.`is`(VALID_FOR_TERRAIN)) return true
        if (state.hasBlockEntity()) return false
        // **A fluid is allowed though nothing stands on it**, and the distinction is accident against
        // intent: an ocean world and a world of lava are Ages somebody meant, with a boat and a potion as
        // the answers, where a world of signs is a block nobody thought of as a world at all.
        if (state.block is LiquidBlock) return true
        return Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
    }

    /** The same, of whatever a writer named — false where the pack has no such block. */
    fun makesAWorld(named: String): Boolean {
        val id = Identifier.tryParse(named) ?: return false
        val block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null) ?: return false
        return makesAWorld(block)
    }
}
