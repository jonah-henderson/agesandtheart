package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.book.LecternBooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Tells a client which book a lectern holds, where it is one of ours (design §7.8.2).
 *
 * <p><b>Vanilla never does.</b> {@code LecternBlockEntity} overrides neither method here, so it inherits
 * {@code BlockEntity}'s empty tag and null packet, and its renderer reads only the {@code HAS_BOOK}
 * blockstate. A client cannot draw a cover, a title or a panel for a book it has never been told about.
 *
 * <p><b>Why a mixin, and what was checked first.</b> What a block entity sends is decided by overriding
 * these two methods on its own class, and the class is vanilla's; neither loader offers a way in from
 * outside it. A payload of our own was the alternative, and it would be a second route to the same client
 * object, kept in step by hand with every change the block already broadcasts — {@code setBlock} sends this
 * packet on its own whenever the book or the lectern's state changes.
 *
 * <p>The methods are added rather than injected, because the target does not declare them: extending
 * {@code BlockEntity} here makes them overrides once merged. What goes in the tag is
 * {@link LecternBooks#updateTagFor}'s to decide.
 */
@Mixin(LecternBlockEntity.class)
public abstract class LecternBlockEntityMixin extends BlockEntity {

    private LecternBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return LecternBooks.updateTagFor((LecternBlockEntity) (Object) this, registries);
    }
}
