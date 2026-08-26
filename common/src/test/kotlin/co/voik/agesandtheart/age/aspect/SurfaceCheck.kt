package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.CeilingField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Union
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * **Shutting a world overhead must not change what its ground wears.**
 *
 * [Surface.ruleFor] hands its terrain to a gate that finds the surface by *measuring* it, so the field it
 * is given decides where the whole tree fires. A sealed Age's [AgeRock.Ours.field] reaches the top of the
 * world, and giving it that put every rule in the top eight blocks of the sky: the ground came out as
 * undressed fill, and over the infernal template that is a warped forest of bare netherrack with nothing
 * growing on it, nylium being both the skin and what the vegetation is placed against.
 *
 * The wrong field is no longer nameable here — [Surface.ruleFor] takes the whole rock — so what is left to
 * check is the fact underneath: that [AgeRock.Ours.landform] stays the land. These are the same Age twice,
 * once with a lid and once without, and the dressing has to come out identical.
 */
@Tags(NEEDS_REGISTRIES)
class SurfaceCheck : FunSpec({
    val window = VerticalWindow.DEFAULT
    val land = Slab(lowY = window.minY, highY = 64)
    val sealed = AgeRock.Ours(
        field = Union(listOf(land, CeilingField.over(window, salt = 31L))),
        ground = land,
    )
    val open = AgeRock.Ours(field = land)

    /** Whatever a book left unsaid — the branch that delegates to the biomes, and the one that broke. */
    val saidNothing = Options()

    test("a sealed Age is dressed exactly as the same land left open") {
        MinecraftRegistries.ensureStoodUp()

        val underALid = Surface.ruleFor(saidNothing, sealed, AgeTemplate.INFERNAL)
        val underTheSky = Surface.ruleFor(saidNothing, open, AgeTemplate.INFERNAL)

        check(underALid == underTheSky) {
            "a lid changed the surface rule, so the dressing hangs from the roof rather than the land"
        }
    }

    test("and so is one wearing a skin the writer named") {
        MinecraftRegistries.ensureStoodUp()
        val blackstone = Options(mapOf(Surface.MATERIAL.name to listOf("minecraft:blackstone")))

        val underALid = Surface.ruleFor(blackstone, sealed, AgeTemplate.INFERNAL)
        val underTheSky = Surface.ruleFor(blackstone, open, AgeTemplate.INFERNAL)

        check(underALid == underTheSky) { "a named skin is hung from the roof of a sealed Age" }
    }

    /**
     * The control, and it is not ceremony: both tests above compare two rules for equality, and a lid that
     * had stopped reaching the roof — or a [Slab] that had started to — would make them agree for a reason
     * that has nothing to do with what they are asking.
     */
    test("the lid is a roof, and the land is not") {
        val landTop = sealed.landform.columnSpans(0, 0).highestSolidY
        val roofTop = sealed.field.columnSpans(0, 0).highestSolidY

        check(landTop == land.highY) { "the land of a sealed Age reads as $landTop rather than ${land.highY}" }
        check(roofTop == window.topY) { "the lid stops at $roofTop, so there is no roof to be fooled by" }
    }
})
