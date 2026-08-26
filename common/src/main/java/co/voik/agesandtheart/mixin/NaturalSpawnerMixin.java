package co.voik.agesandtheart.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Letting a creature an Age's own book asked for past the one refusal vanilla makes of it by category.
 *
 * <p>{@code NaturalSpawner.isValidSpawnPostitionForType} opens with
 * {@code if (type.getCategory() == MobCategory.MISC) return false;}, so an iron golem or a snow golem is
 * declined after the list has offered it, whatever pass it was offered in and whatever it weighed. That is
 * the last of three refusals a written golem met, and the only one no weight could reach: the first was the
 * `SpawnerData` constructor swapping it for a pig ({@code SpawnerDataMixin}), the second was arriving in the
 * `CREATURE` pass, whose cap world generation has already spent.
 *
 * <p><b>Any {@code MISC} type reaching this method is necessarily ours, and that is the whole guard.</b>
 * Vanilla cannot produce a {@code MISC}-typed {@code SpawnerData} at all — its own constructor forbids it —
 * so a biome list, a structure's spawn overrides and a datapack can none of them put one here. The only
 * route is {@code Spawns.carrying}, which builds an entry for a creature the Age's own words named. The
 * same argument as {@code SpawnerDataMixin}'s, one step further along the same path.
 *
 * <p><b>A redirect on the category read rather than a cancel on the method.</b> Cancelling would skip the
 * distance test, the summon test, the placement rules and the collision check with it, and those are
 * exactly what keeps a written creature somewhere it can stand. This changes the answer to one question and
 * leaves the other five to vanilla — {@code MONSTER} being what the pass already treats it as, since
 * {@code Spawns.spawnPassFor} offers it there.
 *
 * <p><b>Alternatives checked</b>, per the rule in {@code CLAUDE.md}. Neither loader has an event on spawn
 * validation: NeoForge's {@code FinalizeSpawnEvent} fires after the decision and cannot un-refuse one, and
 * Fabric has nothing at this seam. Registering the golems into {@code SpawnPlacements} does not help, that
 * being a later check than this one. Giving them a category of our own would need every mob in the game
 * re-examined, {@code MobCategory} being an enum vanilla switches on.
 */
@Mixin(net.minecraft.world.level.NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    @Redirect(
        method = "isValidSpawnPostitionForType",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/EntityType;getCategory()Lnet/minecraft/world/entity/MobCategory;",
            ordinal = 0
        )
    )
    private static MobCategory agesandtheart$carryTheBuiltOnes(EntityType<?> type) {
        // The real answer for everything else, so the despawn distance read below is untouched.
        MobCategory category = type.getCategory();
        return category == MobCategory.MISC ? MobCategory.MONSTER : category;
    }
}
