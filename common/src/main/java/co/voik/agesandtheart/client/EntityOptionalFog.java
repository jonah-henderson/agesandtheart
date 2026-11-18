package co.voik.agesandtheart.client;

import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.jspecify.annotations.Nullable;

/**
 * A fog environment asked about a camera that may have no entity. Vanilla declares the entity non-null and passes the
 * camera's, which a camera drawing a level off-screen (a linking panel's orbit) does not have; Kotlin checks a non-null
 * parameter on entry, so an environment written in Kotlin overrides {@link #appliesTo} instead.
 */
public abstract class EntityOptionalFog extends FogEnvironment {

    @Override
    public final boolean isApplicable(@Nullable FogType fogType, Entity entity) {
        return appliesTo(fogType, entity);
    }

    protected abstract boolean appliesTo(@Nullable FogType fogType, @Nullable Entity entity);
}
