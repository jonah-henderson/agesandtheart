package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.JsonOps
import com.google.gson.JsonParser
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Holder
import net.minecraft.core.HolderGetter
import net.minecraft.core.HolderOwner
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import java.util.Optional

/**
 * A biome of ours that declares where it belongs is put there — and a stronger naming widens its climate
 * and leaves where it stands on the ground alone.
 */
@Tags(NEEDS_REGISTRIES)
class BiomeHomeCheck : FunSpec({

    test("a declared home is where a biome the table has never seen goes") {
        val placed = entriesFor(applied(ORDINARY), HOMED)
        check(placed.map { it.first } == HOME.parameters) {
            "a biome with a home was put at ${placed.map { it.first }}, not at ${HOME.parameters}"
        }
    }

    test("a stronger naming widens the climate axes") {
        val wider = entriesFor(applied(TWICE), HOMED).single().first
        val declared = HOME.parameters.single()
        check(widthOf(wider.temperature()) > widthOf(declared.temperature())) { "temperature did not widen" }
        check(widthOf(wider.humidity()) > widthOf(declared.humidity())) { "humidity did not widen" }
    }

    // A beach asked for strongly is more beach in more climates, not beach climbing the hillside.
    test("and leaves the shape axes where they were") {
        val wider = entriesFor(applied(TWICE), HOMED).single().first
        val declared = HOME.parameters.single()
        check(wider.continentalness() == declared.continentalness()) { "continentalness moved" }
        check(wider.erosion() == declared.erosion()) { "erosion moved" }
        check(wider.weirdness() == declared.weirdness()) { "weirdness moved" }
        check(wider.depth() == declared.depth()) { "depth moved" }
    }

    test("the palm beach's own file reads") {
        val file = BiomeHomeCheck::class.java.getResourceAsStream("/data/agesandtheart/biome_home/palm_beach.json")
        checkNotNull(file) { "no palm beach home on the classpath" }
        val home = BiomeHome.CODEC.parse(JsonOps.INSTANCE, file.reader().use(JsonParser::parseReader))
            .getOrThrow()
        check(home.parameters.isNotEmpty()) { "the palm beach's home declares no boxes" }
    }
})

private val HOMED = Identifier.fromNamespaceAndPath("agesandtheart", "palm_beach")
private val NATIVE = Identifier.withDefaultNamespace("beach")

private const val ORDINARY = 1.0
private const val TWICE = 2.0

private val HOME = BiomeHome(
    listOf(
        Climate.parameters(
            Climate.Parameter.span(0.2f, 1.0f),
            Climate.Parameter.span(-0.5f, 0.5f),
            Climate.Parameter.span(-0.27f, -0.11f),
            Climate.Parameter.span(-0.2225f, 1.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.span(-1.0f, 1.0f),
            0.0f,
        ),
    ),
)

private fun applied(weight: Double): Climate.ParameterList<Holder<Biome>> = BiomePreference.applied(
    Climate.ParameterList(listOf(Pair(Climate.parameters(0f, 0f, 0f, 0f, 0f, 0f, 0f), holderOf(NATIVE)))),
    listOf(BiomePreference(HOMED, weight)),
    keepsOnlyNamed = false,
    biomes = HOLDERS,
    seed = 7L,
    homes = mapOf(HOMED to HOME),
)

private fun entriesFor(table: Climate.ParameterList<Holder<Biome>>, biome: Identifier) =
    table.values().filter { it.second.unwrapKey().orElse(null)?.identifier() == biome }
        // The table ranks an introduced biome with an offset, which breaks ties and moves nothing else.
        .map { Pair(withoutOffset(it.first), it.second) }

private fun withoutOffset(point: Climate.ParameterPoint) = Climate.ParameterPoint(
    point.temperature(), point.humidity(), point.continentalness(), point.erosion(), point.depth(),
    point.weirdness(), 0L,
)

private fun widthOf(parameter: Climate.Parameter): Long = parameter.max() - parameter.min()

private fun holderOf(biome: Identifier): Holder<Biome> =
    Holder.Reference.createStandAlone(OWNER, ResourceKey.create(Registries.BIOME, biome))

private val OWNER = object : HolderOwner<Biome> {}

/** Every biome asked for is there — the table only ever asks a holder its id. */
private val HOLDERS = object : HolderGetter<Biome> {
    override fun get(key: ResourceKey<Biome>): Optional<Holder.Reference<Biome>> =
        Optional.of(Holder.Reference.createStandAlone(OWNER, key))

    override fun get(tag: TagKey<Biome>): Optional<HolderSet.Named<Biome>> = Optional.empty()
}
