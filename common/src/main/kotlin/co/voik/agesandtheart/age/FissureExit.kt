package co.voik.agesandtheart.age

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * Where everything falling out of one Age's fissures comes down, once a book thrown into one has said: the
 * world [dimension], over [at] — a linking book's own place — or, where [at] is null, that Age's arrival, as a
 * descriptive book links.
 */
data class FissureExit(val dimension: Identifier, val at: BlockPos?) {
    companion object {
        val CODEC: Codec<FissureExit> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("dimension").forGetter(FissureExit::dimension),
                BlockPos.CODEC.optionalFieldOf("at").forGetter { Optional.ofNullable(it.at) },
            ).apply(instance) { dimension, at -> FissureExit(dimension, at.orElse(null)) }
        }
    }
}
