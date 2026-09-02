package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.mojang.datafixers.util.Pair
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
 * What a weight does to the climate table — the half of a population's answer that turns into ground.
 *
 * **A weight is a multiple of what the Age would have grown anyway**, so the two directions are the whole
 * point: above [BiomePreference.ORDINARY] a biome answers for more of the climate cube, below it for less.
 * The second direction is why an entry is *resized where it stands*; adding a narrowed copy beside the
 * original would leave the original covering exactly what it did, and "fewer swamps" would read as no
 * change at all.
 */
@Tags(NEEDS_REGISTRIES)
class BiomeWeightCheck : FunSpec({

    test("a weight above ordinary gives a biome more of the climate cube") {
        val grown = applied(BiomePreference(FAVOURED, ABOVE_ORDINARY))
        check(reachOf(grown, FAVOURED) > reachOf(table(), FAVOURED)) {
            "a mention left the biome answering for ${reachOf(grown, FAVOURED)}, against ${reachOf(table(), FAVOURED)}"
        }
    }

    test("a weight below ordinary takes climate away") {
        val thinned = applied(BiomePreference(FAVOURED, HALF))
        check(reachOf(thinned, FAVOURED) < reachOf(table(), FAVOURED)) {
            "a half weight left the biome answering for ${reachOf(thinned, FAVOURED)}, " +
                "against ${reachOf(table(), FAVOURED)}"
        }
        // But it is still in the world. Being small is what makes a biome rare; being gone is `except`.
        check(entriesFor(thinned, FAVOURED).isNotEmpty()) { "a thinned biome vanished from the table" }
    }

    test("a biome nobody spoke about is left exactly as it was") {
        val grown = applied(BiomePreference(FAVOURED, ABOVE_ORDINARY))
        // The boxes, not the entries: a standalone holder is equal only to itself, and every table built
        // here builds its own.
        val untouched = entriesFor(grown, IGNORED).map { it.first }
        check(untouched == entriesFor(table(), IGNORED).map { it.first }) {
            "speaking about one biome moved another: $untouched"
        }
    }

    test("except deletes a biome's entries") {
        val struck = applied(BiomePreference(FAVOURED, BiomePreference.STRUCK_OUT))
        check(entriesFor(struck, FAVOURED).isEmpty()) { "'except' left the biome in the table" }
        check(entriesFor(struck, IGNORED).isNotEmpty()) { "'except' took an unnamed biome with it" }
    }

    test("only keeps what was named and nothing else") {
        val singledOut = applied(BiomePreference(FAVOURED, BiomePreference.ORDINARY), keepsOnlyNamed = true)
        check(entriesFor(singledOut, IGNORED).isEmpty()) { "'only' left an unnamed biome standing" }
        check(entriesFor(singledOut, FAVOURED).isNotEmpty()) { "'only' struck out the biome it named" }
    }

    /** A world with nothing in it is not a world, so a narrowing that empties the table is refused. */
    test("a narrowing that would empty the table is refused whole") {
        val nothingLeft = applied(
            BiomePreference(FAVOURED, BiomePreference.STRUCK_OUT),
            BiomePreference(IGNORED, BiomePreference.STRUCK_OUT),
        )
        check(nothingLeft.values().size == table().values().size) {
            "an Age narrowed itself down to ${nothingLeft.values().size} entries"
        }
    }
})

private val FAVOURED = Identifier.withDefaultNamespace("cherry_grove")
private val IGNORED = Identifier.withDefaultNamespace("badlands")

/** Twice what the Age would have grown anyway — what naming a biome comes to (`Resolver.claimForMember`). */
private const val ABOVE_ORDINARY = 2.0

/** Half of what the Age would have grown anyway — a biome spoken against rather than struck out. */
private const val HALF = 0.5

private const val ENTRIES_EACH = 3

/** How far apart the sample boxes sit on each axis, so two entries are not the same box twice. */
private const val ENTRY_STRIDE = 0.1f

private const val SAMPLE_HALF_WIDTH = 0.05f

private const val A_SEED = 7L

private fun applied(
    vararg preferences: BiomePreference,
    keepsOnlyNamed: Boolean = false,
): Climate.ParameterList<Holder<Biome>> {
    MinecraftRegistries.ensureStoodUp()
    return BiomePreference.applied(table(), preferences.toList(), keepsOnlyNamed, NO_BIOMES, A_SEED)
}

/** A table of two biomes, three boxes each — enough to tell "resized" from "gone" and from "untouched". */
private fun table(): Climate.ParameterList<Holder<Biome>> = Climate.ParameterList(
    listOf(FAVOURED, IGNORED).flatMap { biome ->
        (0..<ENTRIES_EACH).map { entry -> Pair(boxAt(entry * ENTRY_STRIDE), standaloneHolder(biome)) }
    },
)

private fun boxAt(middle: Float): Climate.ParameterPoint {
    val box = Climate.Parameter.span(middle - SAMPLE_HALF_WIDTH, middle + SAMPLE_HALF_WIDTH)
    return Climate.ParameterPoint(box, box, box, box, box, box, 0L)
}

private fun entriesFor(table: Climate.ParameterList<Holder<Biome>>, biome: Identifier) =
    table.values().filter { it.second.unwrapKey().orElse(null)?.identifier() == biome }

/** How much of the climate cube a biome answers for — the sum of its boxes' widths on one axis. */
private fun reachOf(table: Climate.ParameterList<Holder<Biome>>, biome: Identifier): Long =
    entriesFor(table, biome).sumOf { it.first.temperature().max() - it.first.temperature().min() }

/**
 * A holder that knows its key and nothing else. Biomes are datapack content, so there is no registry to
 * take a reference from offline — and [BiomePreference.applied] only ever asks a holder its id.
 */
private fun standaloneHolder(biome: Identifier): Holder<Biome> =
    Holder.Reference.createStandAlone(BIOME_OWNER, ResourceKey.create(Registries.BIOME, biome))

private val BIOME_OWNER = object : HolderOwner<Biome> {}

/** Nothing to look up: every biome these checks name is already in the table. */
private val NO_BIOMES = object : HolderGetter<Biome> {
    override fun get(key: ResourceKey<Biome>): Optional<Holder.Reference<Biome>> = Optional.empty()

    override fun get(tag: TagKey<Biome>): Optional<HolderSet.Named<Biome>> = Optional.empty()
}
