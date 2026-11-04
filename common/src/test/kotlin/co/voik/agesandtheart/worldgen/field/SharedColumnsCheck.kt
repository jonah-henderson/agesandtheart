package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** That columns shared across threads are the columns asked for, each field's its own. */
class SharedColumnsCheck : FunSpec({

    test("a packed column reads back as itself") {
        val columns = listOf(Spans.EMPTY, Spans.of(-60, 70), Spans.of(-60, 10).union(Spans.of(20, 40)).union(Spans.of(90, 91)))
        for (column in columns) check(Spans.unpacked(column.packed()).ranges == column.ranges) { "$column came back changed" }
    }

    test("every thread is answered the column asked for, and a stored column is never worked out again") {
        val worked = AtomicInteger()
        val shared = SharedColumns { x, z -> worked.incrementAndGet(); columnFor(x, z) }
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val asks = (0..<THREADS).map { Callable { (0..<16).flatMap { x -> (0..<16).map { z -> shared.at(x, z) } } } }
            val answers = pool.invokeAll(asks).map { it.get() }
            for (answer in answers) {
                for ((index, column) in answer.withIndex()) {
                    check(column.ranges == columnFor(index / 16, index % 16).ranges) { "column $index came back wrong" }
                }
            }
        } finally {
            pool.shutdown()
        }
        // Threads asking at the same instant may each work a column out, and get the same answer. Once one is
        // stored, nobody works it out again.
        val afterTheRace = worked.get()
        for (x in 0..<16) for (z in 0..<16) shared.at(x, z)
        check(worked.get() == afterTheRace) { "${worked.get() - afterTheRace} stored columns were worked out again" }
    }

    test("two fields never read each other's columns") {
        val low = SharedColumns { _, _ -> Spans.of(0, 10) }
        val high = SharedColumns { _, _ -> Spans.of(100, 110) }
        check(low.at(5, 5).highestSolidY == 10) { "the first field's column was not its own" }
        check(high.at(5, 5).highestSolidY == 110) { "the second field read the first's column" }
    }
})

private const val THREADS = 8

private fun columnFor(x: Int, z: Int): Spans = Spans.of(-60, x * 3 + z).union(Spans.of(200 + x, 210 + z))
