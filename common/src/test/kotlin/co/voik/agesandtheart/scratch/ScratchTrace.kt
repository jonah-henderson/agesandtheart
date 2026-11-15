package co.voik.agesandtheart.scratch

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.reading.Trace
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.io.File

/** Scratch: never commit. Traces the rows in build/claude-scratch/trace-rows.txt (`row|seed`) into trace-out.txt. */
@Tags(NEEDS_REGISTRIES)
class ScratchTrace : FunSpec({
    test("trace") {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val rows = File(root, "build/claude-scratch/trace-rows.txt").readLines().filter { it.isNotBlank() }
        val out = StringBuilder()
        for (line in rows) {
            val (row, seed) = line.split("|").let { it[0] to (it.getOrNull(1)?.toLong() ?: 0L) }
            val written = ShippedCorpus.written(row.split(" "), seed)
            out.appendLine("== $row @ $seed")
            Trace.of(written.reading, written.drawn).forEach { out.appendLine("  $it") }
            written.reading.phrases.forEach { ph -> out.appendLine("  phrase head=${ph.head} part=${ph.part} pol=${ph.polarity} own=${ph.own} placements=${ph.placements.map { it.offer.concept.path + ":" + it.effects }}") }
            out.appendLine("  oakByDefault=${ShippedCorpus.vocabulary.isThereByDefault(co.voik.agesandtheart.age.aspect.Aspect.FEATURES, "minecraft:oak")}")
            written.drawn.possible.forEach { out.appendLine("  possible: ${it.word}@${it.page} ${it.place} ${it.effects} ${it.polarity}") }
            out.appendLine("  flaws: ${written.resolution.instability.flaws}")
            out.appendLine("  world: ${written.resolution.composition}")
            val viaResolve = ShippedCorpus.resolved(seed, *row.split(" ").toTypedArray()).composition
            out.appendLine("  resolve: $viaResolve")
            val written2 = co.voik.agesandtheart.generation.StructureMaterials.Written.of(viaResolve.optionsFor(co.voik.agesandtheart.age.aspect.Aspect.STRUCTURES, 0))
            out.appendLine("  materials: every=${written2.everyStructure} bySet=${written2.bySet} chosen=${viaResolve.optionsFor(co.voik.agesandtheart.age.aspect.Aspect.STRUCTURES, 0).chosen}")
            out.appendLine("  algae: ${co.voik.agesandtheart.age.aspect.Features.algaeColourIn(viaResolve.optionsFor(co.voik.agesandtheart.age.aspect.Aspect.FEATURES))} features=${viaResolve.optionsFor(co.voik.agesandtheart.age.aspect.Aspect.FEATURES).chosen}")
        }
        File(root, "build/claude-scratch/trace-out.txt").writeText(out.toString())
    }
})
