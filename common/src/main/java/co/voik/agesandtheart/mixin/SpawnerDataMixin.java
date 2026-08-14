package co.voik.agesandtheart.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A way to put a creature vanilla refuses to carry into a spawn list — and **only where the Age's own
 * recipe named it**.
 *
 * <p>Vanilla's {@code SpawnerData} constructor reads
 * {@code this.type = type.getCategory() == MobCategory.MISC ? EntityType.PIG : type}, so an iron golem or
 * a snow golem put in a list silently becomes a pig. That guard is worth keeping for every path but ours:
 * a datapack or another mod tripping it is a mistake, where a writer asking for a world of snow golems is
 * a sentence.
 *
 * <p><b>The recipe check is the call site, not a condition here.</b> This is an accessor and nothing
 * more — it changes no behaviour and is reached from exactly one place, {@code Spawns.added}, which only
 * ever builds an entry for a creature the Age's own words asked for. So vanilla's construction path is
 * untouched and keeps its safeguard, and there is no way to reach this without having been written.
 *
 * <p><b>Alternatives checked</b>, per the rule in {@code CLAUDE.md}. There is no loader event for building
 * a spawn entry on either side, this being a record constructor rather than a lifecycle point. Rebuilding
 * the natural spawner ourselves would reimplement placement rules, mob caps and the per-category passes to
 * change one field. Redirecting the ternary inside the constructor would apply to every caller and lose the
 * safeguard for all of them, which is the thing we are trying not to do.
 */
@Mixin(MobSpawnSettings.SpawnerData.class)
public interface SpawnerDataMixin {
    /**
     * The type this entry spawns, settable — see the class note for why this is safe to expose.
     *
     * <p>{@code @Mutable} because the field is final: a record component is an ordinary final field, and
     * writing one after construction is a class transform rather than reflection, so it verifies.
     */
    @Accessor("type")
    @Mutable
    @Shadow
    @Final
    void agesandtheart$setType(EntityType<?> type);
}
