package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.PlacedFeature
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Spawn
import co.voik.agesandtheart.age.aspect.StructureSet
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** What aspects and members take, read off what they own (`the-world-model.md` §3, Acceptance). */
@Tags(NEEDS_REGISTRIES)
class AcceptanceCheck : FunSpec({

    fun ofMember(member: Taggable?): Acceptance =
        Acceptance.ofMember(requireNotNull(member) { "no such member" }, MinecraftRegistries.worldgen)

    fun feature(id: String): Acceptance = ofMember(PlacedFeature.named(id))

    test("an aspect's page takes what the aspect owns, and lands on the whole of it") {
        val climate = Acceptance.ofAspect(Aspect.CLIMATE)
        check(climate.takesParameter("temperature")) { "the climate does not take a temperature" }
        check(!climate.takesParameter("size")) { "the climate takes a size" }
        check(climate.place == Place(Aspect.CLIMATE)) { "the climate's page lands somewhere else: ${climate.place}" }
        val landmass = Acceptance.ofAspect(Aspect.TERRAIN)
        check(landmass.takesChoices && landmass.takesSubstance) { "the landmass takes no landform or no substance" }
        check(!Acceptance.ofAspect(Aspect.SKY).takesChoices) { "the sky, which seats nothing, takes a choice" }
    }

    test("the stars take an abundance, and the sun does not") {
        check(Acceptance.ofAspect(Aspect.STARS).takesAbundance) { "`teeming stars` has nothing to land on" }
        check(!Acceptance.ofAspect(Aspect.SUN).takesAbundance) { "the sun takes an abundance" }
    }

    test("only villages take abandonment, and every structure takes a substance and an abundance") {
        val villages = ofMember(StructureSet.named("minecraft:villages"))
        val outposts = ofMember(StructureSet.named("minecraft:pillager_outposts"))
        check(villages.takesParameter("abandoned")) { "villages cannot be deserted" }
        check(!outposts.takesParameter("abandoned")) { "outposts, which have no abandoned pieces, take abandonment" }
        for (structure in listOf(villages, outposts)) {
            check(structure.takesSubstance && structure.takesAbundance) { "${structure.place} takes no substance or abundance" }
        }
        check(villages.place == Place(Aspect.STRUCTURES, Members.Named(setOf("minecraft:villages")))) {
            "a village word lands on ${villages.place}, not on the villages"
        }
    }

    test("a feature takes the dials its code does something to") {
        val trees = feature("minecraft:trees_plains")
        check(trees.takesParameter("bare") && trees.takesParameter("size")) { "trees cannot be bare or colossal" }
        val diamonds = feature("minecraft:ore_diamond")
        check(diamonds.takesParameter("height") && diamonds.takesParameter("size")) { "an ore has no depth or vein size" }
        check(!diamonds.takesParameter("bare")) { "an ore can be bare" }
        check(!feature("minecraft:vines").takesParameter("size")) { "vines take a size, though nothing resizes them" }
        check(feature("minecraft:pale_moss_patch").takesParameter("thickness")) { "a moss patch cannot be thickened" }
    }

    test("a creature takes every dial creatures hold") {
        val zombie = ofMember(Spawn.named("minecraft:zombie"))
        for (dial in listOf("still", "pace", "strength", "size")) {
            check(zombie.takesParameter(dial)) { "a zombie does not take '$dial'" }
        }
    }

    test("a phenomenon takes its own dials, and an abundance only where it happens in a place") {
        val tempest = ofMember(Phenomenon.TEMPEST)
        val overgrowth = ofMember(Phenomenon.OVERGROWTH)
        check(tempest.takesParameter("frequency") && !tempest.takesParameter("pace")) {
            "the tempest takes ${tempest.parameters}"
        }
        check(overgrowth.takesParameter("pace")) { "the overgrowth cannot be slowed" }
        check(!tempest.takesAbundance && overgrowth.takesAbundance) { "a rung lands on the tempest, or not the overgrowth" }
    }

    test("with no registries to look in, a registry entry takes every dial") {
        val unseen = Acceptance.ofMember(requireNotNull(PlacedFeature.named("minecraft:vines")), registries = null)
        check(unseen.takesParameter("size")) { "an entry nothing could look at refused a dial" }
    }
})
