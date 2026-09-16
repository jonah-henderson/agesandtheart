package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.OreColliders;
import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a drifting body be stood on where its rock actually is ({@link OreColliders#standingIn}).
 *
 * <p><b>Why a mixin, and there is genuinely no alternative.</b> {@code EntityGetter.getEntityCollisions}
 * builds its shapes as {@code Shapes.create(entity.getBoundingBox())} — an AABB, hard-coded, with no
 * per-entity hook anywhere in the call. Neither loader has an event: NeoForge's entity events are about
 * spawning, hurting and ticking, and Fabric API has nothing in this area at all. A shaped entity is simply
 * not a vanilla capability, and this is the one place the list of shapes is assembled.
 *
 * <p><b>Why {@code collectColliders} and not {@code collectAllColliders}</b>, which is the one that reads
 * like the funnel and is not. {@code Entity.collide} — the actual movement path — calls
 * {@code getEntityCollisions} itself and then goes to {@code collideBoundingBox}, which reaches
 * {@code collectColliders} directly; {@code collectAllColliders} is only a convenience wrapper used by a
 * floor-height query and by outside callers, so a body added there is added to a list nothing moving ever
 * reads. This one is the genuine funnel: the plain move, the step-up and the wrapper all end here.
 *
 * <p><b>Bodies say they cannot be collided with</b>, so vanilla adds nothing for them and there is nothing
 * to remove here — the shapes are added beside what vanilla found rather than replacing anything, which is
 * what keeps this an append and not a rewrite.
 */
@Mixin(Entity.class)
public abstract class EntityCollidersMixin {

    @Inject(method = "collectColliders", at = @At("RETURN"), cancellable = true)
    private static void agesandtheart$standOnTheRock(
        Entity source,
        Level level,
        List<VoxelShape> entityColliders,
        AABB boundingBox,
        CallbackInfoReturnable<List<VoxelShape>> collision
    ) {
        List<VoxelShape> bodies = OreColliders.INSTANCE.standingIn(source, level, boundingBox);
        if (bodies.isEmpty()) {
            return;
        }
        collision.setReturnValue(
            ImmutableList.<VoxelShape>builder().addAll(collision.getReturnValue()).addAll(bodies).build()
        );
    }
}
