package co.voik.agesandtheart.book.panel

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import net.minecraft.world.level.ChunkPos

/**
 * The ring a panel loads, which is the whole of what the feature costs a server.
 *
 * **Worth checking offline because it is the bound.** `link-panel-research.md` buys the feature out of
 * Immersive Portals' expense with one ruling — the chunk set a fixed orbit sees is known, constant and
 * small — so a ring that quietly grew, or that stopped being centred on the arrival, would take the
 * argument away without anything failing.
 *
 * Registries because `ChunkPos` reaches `ChunkPyramid` and so `ChunkStatus` in its static initialiser —
 * plain arithmetic that nonetheless cannot run until the game has been booted.
 */
@Tags(NEEDS_REGISTRIES)
class PanelCheck : StringSpec({

    // The tag filters; it does not boot. `ChunkPos` reaches `ChunkPyramid` and so `ChunkStatus` in its
    // static initialiser, which needs the registries standing up before the first one is constructed.
    beforeSpec { MinecraftRegistries.ensureStoodUp() }

    "the ring is a square of known size" {
        val ring = PanelProtocol.ringAround(ChunkPos(0, 0))
        check(ring.size == PanelProtocol.RING_CHUNKS) {
            "the ring holds ${ring.size} chunks where ${PanelProtocol.RING_CHUNKS} were promised"
        }
        check(ring.size == PanelProtocol.RING_SIDE * PanelProtocol.RING_SIDE) {
            "a ${PanelProtocol.RING_SIDE}-a-side square should hold ${PanelProtocol.RING_SIDE * PanelProtocol.RING_SIDE}"
        }
        check(ring.toSet().size == ring.size) { "the ring names the same chunk twice" }
    }

    "no chunk lies outside the radius" {
        val centre = ChunkPos(12, -30)
        val radius = PanelProtocol.RING_RADIUS_CHUNKS
        for (position in PanelProtocol.ringAround(centre)) {
            val away = maxOf(kotlin.math.abs(position.x - centre.x), kotlin.math.abs(position.z - centre.z))
            check(away <= radius) { "$position is $away chunks from $centre, past the radius of $radius" }
        }
    }

    "the ring is centred wherever it is asked about" {
        val centre = ChunkPos(-401, 977)
        val ring = PanelProtocol.ringAround(centre)
        check(ring.first() == centre) { "the first chunk sent is ${ring.first()}, not the arrival at $centre" }
        check(centre in ring) { "the ring around $centre does not contain it" }
    }

    "chunks are sent nearest first, so the picture fills outwards" {
        val centre = ChunkPos(5, 5)
        val distances = PanelProtocol.ringAround(centre).map {
            maxOf(kotlin.math.abs(it.x - centre.x), kotlin.math.abs(it.z - centre.z))
        }
        check(distances == distances.sorted()) {
            "the ring is sent out of order, so a panel would fill in from a corner: $distances"
        }
    }

    "the radius stays small enough to be a glance rather than a render distance" {
        // Not a style rule: the whole affordability argument is that this set is small and fixed. A radius
        // that crept up to a render distance would make a book open cost what walking into the Age costs.
        check(PanelProtocol.RING_RADIUS_CHUNKS in 1..6) {
            "a ring radius of ${PanelProtocol.RING_RADIUS_CHUNKS} is no longer a glance"
        }
    }
})
