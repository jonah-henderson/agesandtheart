package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.AgeRecipe
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.Optional

/**
 * Where a linking book goes: one exact spot, in one world.
 *
 * A *place*, where a Descriptive Book carries a *description* — that is the whole distinction between the
 * two items. A linking book knows nothing about how its destination was made and cannot make another one;
 * it is a door to somewhere that already exists.
 *
 * [name] is stored rather than looked up, so a book stays readable when the world it points at is not
 * loaded — a shelf of them should be legible without touching a single dimension.
 */
data class LinkTarget(
    val dimension: ResourceKey<Level>,
    val position: Vec3,
    val yaw: Float,
    val name: String,
    /**
     * The Age's own recipe, where the destination is one of ours — **so a linking book can put back what
     * it points at** (design §9, "Losing the books").
     *
     * A linking book used to carry a destination and nothing else, so an Age collected while one survived
     * left it pointing at nothing, with no way back. A descriptive book never had that problem: it carries
     * its words and rebuilds through `Ages.ensure`. Carrying the resolved recipe gives a linking book the
     * same power, which is what makes collecting an Age cost its *contents* rather than a route.
     *
     * Null for a vanilla dimension. The Overworld is not ours to rebuild and cannot be collected, so there
     * is nothing to carry and no reason to pretend otherwise.
     */
    val recipe: AgeRecipe? = null,
) {
    companion object {
        val CODEC: Codec<LinkTarget> = RecordCodecBuilder.create { instance ->
            instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(LinkTarget::dimension),
                Vec3.CODEC.fieldOf("position").forGetter(LinkTarget::position),
                Codec.FLOAT.optionalFieldOf("yaw", 0.0f).forGetter(LinkTarget::yaw),
                Codec.STRING.fieldOf("name").forGetter(LinkTarget::name),
                AgeRecipe.MAP_CODEC.codec().optionalFieldOf("recipe").forGetter { Optional.ofNullable(it.recipe) },
            ).apply(instance) { dimension, position, yaw, name, recipe ->
                LinkTarget(dimension, position, yaw, name, recipe.orElse(null))
            }
        }

        val STREAM_CODEC: StreamCodec<ByteBuf, LinkTarget> = StreamCodec.of(
            { buffer, value ->
                ResourceKey.streamCodec(Registries.DIMENSION).encode(buffer, value.dimension)
                ByteBufCodecs.DOUBLE.encode(buffer, value.position.x)
                ByteBufCodecs.DOUBLE.encode(buffer, value.position.y)
                ByteBufCodecs.DOUBLE.encode(buffer, value.position.z)
                ByteBufCodecs.FLOAT.encode(buffer, value.yaw)
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.name)
                // The recipe never leaves the server: a client draws the book's cover and its destination
                // name, and has no use for the world behind it.
            },
            { buffer ->
                LinkTarget(
                    dimension = ResourceKey.streamCodec(Registries.DIMENSION).decode(buffer),
                    position = Vec3(
                        ByteBufCodecs.DOUBLE.decode(buffer),
                        ByteBufCodecs.DOUBLE.decode(buffer),
                        ByteBufCodecs.DOUBLE.decode(buffer),
                    ),
                    yaw = ByteBufCodecs.FLOAT.decode(buffer),
                    name = ByteBufCodecs.STRING_UTF8.decode(buffer),
                )
            },
        )
    }
}
