package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That every block of ours something can stand on is solid to the game (`/age solidity`). Since 26.3,
 * `#minecraft:blocks_motion` is the whole of what the heightmaps, suffocation and fluid flow mean by solid, and a
 * block left out of it is walked on but rained through, never suffocates, and is invisible to a fall's landing.
 */
@Tags(NEEDS_SERVER)
class SolidityOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("every block of ours with a collision shape blocks motion, or is meant not to") {
        val blocks = server.ask("solidity")["blocks"].asJsonArray.map { it.asJsonObject }
        val leftOut = blocks
            .filter { it["collision"].asString != NO_COLLISION && !it["blocks_motion"].asBoolean }
            .map { it["id"].asString }
            .filterNot { it in LETS_MOTION_THROUGH }
        check(leftOut.isEmpty()) {
            "${leftOut.size} block(s) of ours can be stood on but are not in data/minecraft/tags/block/" +
                "blocks_motion_no_leaves.json, nor named as letting motion through here:\n" +
                leftOut.joinToString("\n") { "  $it" }
        }
    }
})

private const val NO_COLLISION = "none"

/**
 * The blocks of ours that have a collision shape and still do not block motion: the rime crystals, astrite shards
 * and gloomgrit, which grow as clusters (Jonah, 2026-10-19), and what vanilla leaves out of the tag in its own
 * analogue — cocoa for the coconuts, a flower pot for the potted sapling.
 */
private val LETS_MOTION_THROUGH = setOf(
    "astrite_shard",
    "gloomgrit",
    "blue_rime_crystal",
    "cyan_rime_crystal",
    "green_rime_crystal",
    "magenta_rime_crystal",
    "orange_rime_crystal",
    "red_rime_crystal",
    "white_rime_crystal",
    "yellow_rime_crystal",
    "coconut",
    "hanging_coconut",
    "potted_palm_sapling",
)
