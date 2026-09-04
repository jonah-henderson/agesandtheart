package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import net.minecraft.world.level.ChunkPos

/**
 * The ring a panel loads, which is the whole of what the feature costs a server.
 *
 * The affordability argument rests on the chunk set being known, constant and small, so a ring that grew or
 * stopped being centred on the arrival would take that argument away without anything failing.
 */
@Tags(NEEDS_REGISTRIES)
class PanelCheck : StringSpec({

    // `ChunkPos` reaches `ChunkStatus` in its static initialiser, so even this arithmetic needs registries.
    beforeSpec { MinecraftRegistries.ensureStoodUp() }

    "the ring is a square of known size" {
        val ring = PanelRing.around(ChunkPos(0, 0))
        check(ring.size == PanelRing.COUNT) {
            "the ring holds ${ring.size} chunks where ${PanelRing.COUNT} were promised"
        }
        check(ring.size == PanelRing.SIDE * PanelRing.SIDE) {
            "a ${PanelRing.SIDE}-a-side square should hold ${PanelRing.SIDE * PanelRing.SIDE}"
        }
        check(ring.toSet().size == ring.size) { "the ring names the same chunk twice" }
    }

    "no chunk lies outside the radius" {
        val centre = ChunkPos(12, -30)
        val radius = PanelRing.RADIUS_CHUNKS
        for (position in PanelRing.around(centre)) {
            val away = maxOf(kotlin.math.abs(position.x - centre.x), kotlin.math.abs(position.z - centre.z))
            check(away <= radius) { "$position is $away chunks from $centre, past the radius of $radius" }
        }
    }

    "the ring is centred wherever it is asked about" {
        val centre = ChunkPos(-401, 977)
        val ring = PanelRing.around(centre)
        check(ring.first() == centre) { "the first chunk sent is ${ring.first()}, not the arrival at $centre" }
        check(centre in ring) { "the ring around $centre does not contain it" }
    }

    "chunks are sent nearest first, so the picture fills outwards" {
        val centre = ChunkPos(5, 5)
        val distances = PanelRing.around(centre).map {
            maxOf(kotlin.math.abs(it.x - centre.x), kotlin.math.abs(it.z - centre.z))
        }
        check(distances == distances.sorted()) {
            "the ring is sent out of order, so a panel would fill in from a corner: $distances"
        }
    }

    "more is streamed than is shown, or the outermost chunks can never be drawn" {
        // `compileSections` will not compile a section it has never compiled unless `hasAllNeighbors()`,
        // so anything shown out to the edge of what was streamed is a permanent hole in the picture.
        check(PanelRing.RADIUS_CHUNKS > PanelRing.SHOWN_RADIUS_CHUNKS) {
            "the ring is streamed to ${PanelRing.RADIUS_CHUNKS} and shown to " +
                "${PanelRing.SHOWN_RADIUS_CHUNKS}, so its outermost shown chunks will never mesh"
        }
    }

    "the radius stays small enough to be a glance rather than a render distance" {
        // Not a style rule: the whole affordability argument is that this set is small and fixed. A radius
        // that crept up to a render distance would make a book open cost what walking into the Age costs.
        check(PanelRing.RADIUS_CHUNKS in 1..6) {
            "a ring radius of ${PanelRing.RADIUS_CHUNKS} is no longer a glance"
        }
    }
})
