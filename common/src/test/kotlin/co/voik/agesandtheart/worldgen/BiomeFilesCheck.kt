package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import com.mojang.serialization.MapCodec
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.world.level.biome.Biome
import java.io.File

/**
 * Every key our biome files write is one the game reads. The biome codec ignores a key it does not know, so a file
 * written for an older version parses and loses that part silently: 26.3 moved a biome's creatures into the
 * `natural_mob_spawns` attribute, and the great hall's `spawners` left it holding nothing.
 */
@Tags(NEEDS_REGISTRIES)
class BiomeFilesCheck : FunSpec({

    test("our biome files write only what the biome codec reads") {
        MinecraftRegistries.ensureStoodUp()
        val read = (Biome.DIRECT_CODEC as MapCodec.MapCodecCodec<Biome>).codec()
            .keys(JsonOps.INSTANCE).map { it.asString }.toList().toSet() + COMMENT
        val files = BIOMES.listFiles { file -> file.name.endsWith(".json") }.orEmpty().toList()
        check(files.isNotEmpty()) { "no biome files under ${BIOMES.absolutePath}" }
        val unread = files.flatMap { file ->
            JsonParser.parseString(file.readText()).asJsonObject.keySet().filterNot { it in read }.map { "${file.name}: $it" }
        }
        check(unread.isEmpty()) { "Biome files write keys the game ignores:\n  ${unread.joinToString("\n  ")}" }
    }
}) {
    private companion object {
        private val BIOMES = File("src/main/resources/data/agesandtheart/worldgen/biome")

        /** Our own note in a data file, which nothing is meant to read. */
        private const val COMMENT = "_comment"
    }
}
