package co.voik.agesandtheart.content

import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * Algae in the hand: **planted where you are looking at water, eaten where you are not.**
 *
 * The two share one gesture because the plant is both — a crop you sow and the meal you take off it. A
 * lily pad's item is the only thing in vanilla that plants *on* a fluid, and it settles the aim the same
 * way, by raycasting against liquid rather than against blocks. Eating is what happens when that finds
 * nothing to sow, which is `Item.use`'s own behaviour and needs no help.
 */
class AlgaeItem(block: AlgaeBlock, properties: Properties) : BlockItem(block, properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val aim = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY)
        if (aim.type != HitResult.Type.BLOCK) return super.use(level, player, hand)
        // Against the face rather than through it: the mat goes *in* the water block, which is the one
        // the ray struck, so the hit is rebuilt as an outside hit on that position.
        val onTheWater = BlockHitResult(aim.location, aim.direction, aim.blockPos, false)
        val sown = place(BlockPlaceContext(UseOnContext(player, hand, onTheWater)))
        // **Falling through to the meal rather than failing**, so aiming at water it cannot grow in — lit,
        // or already grown over — feeds you instead of doing nothing at all.
        return if (sown.consumesAction()) sown else super.use(level, player, hand)
    }
}
