package co.voik.agesandtheart.worldgen.biome

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome

/**
 * A band of an Age that is **indoors** — one biome for everything between [floorY] and [roofY], whatever
 * the climate would otherwise have said there.
 *
 * **Not a climate question, which is why it is answered before the table is consulted.** Vanilla's
 * parameter space has axes for how hot, how wet and how far from an ocean a place is, and no axis at all
 * for having a ceiling over it. Left to the table, a hall a hundred blocks under a forest comes out as
 * lush caves or dripstone — biomes that describe how water got in and what grew there, which is a
 * description of somewhere nobody built.
 *
 * It earns more than the look. In 26.1 a biome carries its own fog, ambient sound, mood and music, so
 * "the halls are a place" and "the halls are dark and sound like something" are the same statement.
 */
data class Roofed(val biome: Holder<Biome>, val floorY: Int, val roofY: Int) {

    /** The biome at this height, or null where the band does not reach — so the climate table answers. */
    fun biomeAt(blockY: Int): Holder<Biome>? = if (blockY in floorY..roofY) biome else null

    companion object {
        val CODEC: Codec<Roofed> = RecordCodecBuilder.create { instance ->
            instance.group(
                Biome.CODEC.fieldOf("biome").forGetter(Roofed::biome),
                Codec.INT.fieldOf("floor_y").forGetter(Roofed::floorY),
                Codec.INT.fieldOf("roof_y").forGetter(Roofed::roofY),
            ).apply(instance, ::Roofed)
        }
    }
}
