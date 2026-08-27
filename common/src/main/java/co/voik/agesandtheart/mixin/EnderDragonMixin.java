package co.voik.agesandtheart.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Node;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Laying a dragon's flight around the place it actually lives.
 *
 * <p><b>Vanilla generalised this and missed one place.</b> {@code EnderDragon} carries a
 * {@code fightOrigin} and threads it through {@code DragonHoldingPatternPhase.findNewTarget} and its own
 * egg lookup — but {@code findClosestNode} lays the twenty-four navigation nodes at <i>absolute</i>
 * coordinates, a ring of radius 60, 40 and 20 around {@code (0, 0)}, with the node height sampled from the
 * terrain there. So a dragon anywhere else holds its position and can never complete a lap.
 *
 * <p>That is why one written into an ordinary Age would not fight. It is not a limitation of the phase
 * machinery: {@code DragonHoldingPatternPhase} rolls for a strafing attack every time it picks a new
 * target, and with no fight the roll is one in two. But the whole block is gated on
 * {@code currentPath != null && currentPath.isDone()}, and a path to a ring thousands of blocks away never
 * finishes. The dragon was stuck mid-path, not declining to attack.
 *
 * <p><b>In the End this is bit-identical to vanilla</b>, which is the guard worth having: the fight sets the
 * origin to the podium, the podium is at {@code (0, 0)}, and both redirects add zero. Nothing that already
 * works changes, and there is no condition here to get wrong — the offset simply <i>is</i> zero where
 * vanilla assumed it.
 *
 * <p><b>Alternatives checked</b>, per the rule in {@code CLAUDE.md}. Neither loader has an event on
 * navigation-graph construction, this being a lazy private initialiser rather than a lifecycle point.
 * Widening {@code nodes} and building the graph ourselves means copying vanilla's twenty-five lines of
 * layout, which drifts the day Mojang retunes the arena. Giving the Age a real {@code EnderDragonFight}
 * would fix the paths and bring a boss bar, crystals and an exit portal with it, none of which an ordinary
 * Age wants.
 */
@Mixin(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class)
public abstract class EnderDragonMixin {

    @Shadow
    private BlockPos fightOrigin;

    /**
     * The node's height, sampled where the node will actually be rather than where vanilla assumed it.
     *
     * <p>Redirected as well as the node itself because the height is read <i>before</i> the offset would
     * otherwise be applied: a ring moved sideways but levelled to the terrain at the world origin is a ring
     * of nodes underground, or a hundred blocks up.
     */
    @Redirect(
        method = "findClosestNode()I",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getHeightmapPos("
                + "Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)"
                + "Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos agesandtheart$sampledWhereItWillStand(Level level, Heightmap.Types types, BlockPos where) {
        return level.getHeightmapPos(types, where.offset(this.fightOrigin.getX(), 0, this.fightOrigin.getZ()));
    }

    /** And the node itself, moved to match. */
    @Redirect(
        method = "findClosestNode()I",
        at = @At(value = "NEW", target = "(III)Lnet/minecraft/world/level/pathfinder/Node;")
    )
    private Node agesandtheart$laidAroundItsOwnHome(int x, int y, int z) {
        return new Node(x + this.fightOrigin.getX(), y, z + this.fightOrigin.getZ());
    }
}
