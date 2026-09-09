package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.phenomena.OreColliders;
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
 * <p><b>Why here rather than at {@code getEntityCollisions}.</b> That is an interface default on
 * {@code EntityGetter}, implemented by several levels; this is the single static method all of them funnel
 * into, so one target serves every caller and there is no risk of catching one level and missing another.
 *
 * <p><b>Bodies say they cannot be collided with</b>, so vanilla adds nothing for them and there is nothing
 * to remove here — the shapes are added beside what vanilla found rather than replacing anything, which is
 * what keeps this an append and not a rewrite.
 */
@Mixin(Entity.class)
public abstract class EntityCollidersMixin {

    @Inject(method = "collectAllColliders", at = @At("RETURN"), cancellable = true)
    private static void agesandtheart$standOnTheRock(
        Entity source,
        Level level,
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
