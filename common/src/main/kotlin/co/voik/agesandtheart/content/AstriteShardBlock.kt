package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import co.voik.agesandtheart.location

/**
 * A shard of astrite set into a face, which is sharp (design §7.1.2).
 *
 * **The item and the block are the same thing**, which is what makes this different from an amethyst
 * cluster: a shard is placed and taken back whole rather than being broken off something that grew it.
 * Pointed dripstone is the shape of that, and it is what lets a player build with them.
 *
 * **Any of six faces**, which comes free from [AmethystClusterBlock] — floors, walls and ceilings all hold
 * one, and the crater that laid it and the player who moves it are placing the same block.
 */
class AstriteShardBlock(properties: BlockBehaviour.Properties) :
    AmethystClusterBlock(SHARD_HEIGHT, SHARD_WIDTH, properties) {

    // Typed as the parent's own: `codec()` is invariant, so a narrower return is not an override.
    override fun codec(): MapCodec<AmethystClusterBlock> = CODEC

    /**
     * Cactus's rule exactly: touch it and it costs you, every tick you stay against it.
     *
     * The precise pass is ignored the way vanilla's own spikes ignore it, so the damage this deals is the
     * damage a cactus deals rather than something near it.
     */
    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        entity.hurt(cuttingIn(level), CUTS_FOR)
    }

    /**
     * **A damage type of ours rather than [net.minecraft.world.damagesource.DamageSources.cactus].**
     * The pack borrows vanilla damage sources nearly everywhere and this is the one place that reads
     * wrong: dying to a shard of sky-metal should not say a cactus did it. `DamageSources.source` is
     * private, so the holder is looked up and the source built.
     */
    private fun cuttingIn(level: Level): DamageSource =
        DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(CUTTING))

    companion object {
        val CODEC: MapCodec<AmethystClusterBlock> = simpleCodec(::AstriteShardBlock)

        val CUTTING: ResourceKey<DamageType> =
            ResourceKey.create(Registries.DAMAGE_TYPE, "astrite_shard".location())

        /** A cactus's, which is what was asked for and what a player already knows the feel of. */
        private const val CUTS_FOR = 1.0f

        /** Slighter than vanilla's full cluster: a shard rather than a growth. */
        private const val SHARD_HEIGHT = 5.0f
        private const val SHARD_WIDTH = 3.0f
    }
}
