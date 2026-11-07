package co.voik.agesandtheart.content;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Temperstone's bond under a piston, on NeoForge: {@code isStickyBlock} and {@code canStickTo} override the two
 * methods of NeoForge's {@code IBlockExtension}, which it patches into {@code Block}, and are plain methods on
 * Fabric, whose half is {@code PistonStructureResolverMixin}.
 *
 * <p>Java, because common is compiled once against each loader's jar: Kotlin must say {@code override} on the one
 * pass and must not on the other, where Java says nothing either way. The descriptors are vanilla-only, so they bind
 * as overrides at runtime.
 */
public abstract class BondingBlock extends Block {

    protected BondingBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    public boolean isStickyBlock(BlockState state) {
        return TemperstoneBlock.bindsToItsOwnKind(state);
    }

    /** Binds to its own kind and to nothing else, where vanilla's rule would drag whatever a slime block touches. */
    public boolean canStickTo(BlockState state, BlockState other) {
        return TemperstoneBlock.bindsToItsOwnKind(state) && TemperstoneBlock.bindsToItsOwnKind(other);
    }
}
