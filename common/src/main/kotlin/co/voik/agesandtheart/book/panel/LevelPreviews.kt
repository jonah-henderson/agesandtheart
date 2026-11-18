package co.voik.agesandtheart.book.panel

import co.voik.ephemeris.LevelClocks
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.dimension.BuiltinDimensionTypes

/** What a client is told about a level before it draws it off-screen without standing in it, as a panel's is. */
object LevelPreviews {

    /** [level]'s dimension type by id, which the client resolves for itself; the overworld's where it has none. */
    fun dimensionTypeOf(level: ServerLevel): Identifier =
        level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getKey(level.dimensionType())
            ?: BuiltinDimensionTypes.OVERWORLD.identifier()

    /** [level]'s own hour, at once, rather than [player]'s until Ephemeris' next beat tells everyone. */
    fun tellTheClock(player: ServerPlayer, level: ServerLevel) = LevelClocks.tell(player, level)
}
