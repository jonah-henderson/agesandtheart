package co.voik.agesandtheart.book

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.location
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * **A linking book carries the Age it points at, and it has to survive being written down** (design §9,
 * "Losing the books").
 *
 * The whole value of it is a book that outlives its Age — which means the recipe is read back off an item
 * on disk long after everything that could have re-derived it is gone. A recipe that failed to round-trip
 * here would leave the book pointing at nothing, silently, exactly when it was needed.
 */
@Tags(NEEDS_REGISTRIES)
class LinkTargetCheck : FunSpec({
    test("a link target round-trips with the Age behind it") {
        MinecraftRegistries.ensureStoodUp()
        val composition = AgeComposition(terrains = listOf(Terrain.PYRAMIDS))
            .withOptions(Aspect.TERRAIN, Terrain.STONE.name, listOf("minecraft:blackstone"))
        val recipe = AgeRecipe(AgeWorld.Composed(composition), seed = SAMPLE_SEED)
        val target = LinkTarget(
            dimension = ResourceKey.create(Registries.DIMENSION, "someage".location()),
            position = Vec3(1.5, 64.0, -2.5),
            yaw = 90.0f,
            name = "Some Age",
            recipe = recipe,
        )
        val encoded = LinkTarget.CODEC.encodeStart(NbtOps.INSTANCE, target)
            .getOrThrow { problem -> IllegalStateException("a link target would not encode: $problem") }
        val decoded = LinkTarget.CODEC.parse(NbtOps.INSTANCE, encoded)
            .getOrThrow { problem -> IllegalStateException("a link target would not decode: $problem") }
        check(decoded.recipe == recipe) { "the Age behind the book was lost: ${decoded.recipe}" }
        check(decoded == target) { "the link target came back as $decoded" }
    }

    /** A book to a vanilla dimension carries none, and must still be a legal book. */
    test("a link target with no Age behind it round-trips") {
        MinecraftRegistries.ensureStoodUp()
        val target = LinkTarget(
            dimension = Level.OVERWORLD,
            position = Vec3(0.0, 64.0, 0.0),
            yaw = 0.0f,
            name = "Overworld",
        )
        val encoded = LinkTarget.CODEC.encodeStart(NbtOps.INSTANCE, target)
            .getOrThrow { problem -> IllegalStateException("a bare link target would not encode: $problem") }
        val decoded = LinkTarget.CODEC.parse(NbtOps.INSTANCE, encoded)
            .getOrThrow { problem -> IllegalStateException("a bare link target would not decode: $problem") }
        check(decoded.recipe == null) { "an Age was invented behind an Overworld link: ${decoded.recipe}" }
        check(decoded == target) { "the link target came back as $decoded" }
    }
})

private const val SAMPLE_SEED = 0x5EED_A9EL
