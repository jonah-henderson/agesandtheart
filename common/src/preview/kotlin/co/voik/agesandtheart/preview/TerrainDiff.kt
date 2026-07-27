package co.voik.agesandtheart.preview

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Compares the terrain of two saved worlds, Age by Age, block for block.
 *
 * The instrument a refactor of the generation pipeline is checked against: generate a set of Ages,
 * change the code, generate them again from the same starting save, and ask whether *anything* moved.
 * A probe of the spawn column can only ever sample; this reads every chunk both worlds have written.
 *
 * It hashes each section's `block_states` and `biomes` — the terrain and its dressing, and nothing
 * else. Deliberately not the whole chunk: `LastUpdate` and `InhabitedTime` count ticks, so they differ
 * between two runs of the same world for reasons that have nothing to do with generation, and stored
 * lighting depends on how far the light engine had got when the chunk was saved.
 *
 *     ./gradlew :common:terraindiff --args="<world-a> <world-b>"
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 2) { "Usage: terraindiff <world-a> <world-b>" }
    val (left, right) = arguments.map { File(it) }

    val leftAges = agesIn(left)
    val rightAges = agesIn(right)
    val shared = leftAges.keys.intersect(rightAges.keys).sorted()

    for (age in leftAges.keys - rightAges.keys) println("only in ${left.name}: $age")
    for (age in rightAges.keys - leftAges.keys) println("only in ${right.name}: $age")
    check(shared.isNotEmpty()) { "The two saves share no Age names, so there is nothing to compare" }

    var identical = 0
    var moved = 0
    var unchecked = 0
    var chunksCompared = 0
    var unfinished = 0

    for (age in shared) {
        val before = terrainOf(leftAges.getValue(age))
        val after = terrainOf(rightAges.getValue(age))

        // Only chunks both saves finished. A chunk left partway up the pipeline holds partly-built
        // sections, and *which* neighbours get dragged how far before the save lands is a matter of
        // timing — two runs of identical code disagree about it. Comparing those would be measuring
        // the harness, not the generator.
        val finished = before.keys.intersect(after.keys)
            .filter { before.getValue(it).status == FULL && after.getValue(it).status == FULL }
        unfinished += before.keys.intersect(after.keys).size - finished.size
        chunksCompared += finished.size

        // An Age neither save finished a chunk of has not been checked, and must not be counted as
        // agreeing — a comparison of nothing is the one result this tool must never report as a pass.
        if (finished.isEmpty()) {
            println("  %-14s NOT CHECKED — no chunk is finished in both saves".format(age))
            unchecked++
            continue
        }

        val differing = finished.filter { before[it]?.sections != after[it]?.sections }.sorted()
        if (differing.isEmpty()) {
            identical++
            println("  %-14s %5d finished chunks identical".format(age, finished.size))
        } else {
            moved++
            println("  %-14s %5d finished chunks, %d DIFFER".format(age, finished.size, differing.size))
            // Which *heights* moved, since that is what tells you the kind of difference: sections up at
            // the surface mean decoration, sections through the rock mean the shape itself.
            for (position in differing.take(MAX_REPORTED)) {
                val was = before.getValue(position).sections
                val now = after.getValue(position).sections
                val movedSections = (was.keys + now.keys).filter { was[it] != now[it] }.sorted()
                println("      $position: sections y=${movedSections.map { it * SECTION_BLOCKS }}")
            }
        }
    }

    println()
    println("$chunksCompared finished chunks compared across ${shared.size} Ages: $identical unchanged, $moved changed.")
    println("($unfinished chunk(s) skipped as unfinished in one save or the other.)")
    if (unchecked > 0) println("$unchecked Age(s) could not be checked at all.")
    check(chunksCompared > 0) { "Not one finished chunk was compared — this checked nothing" }
    check(moved == 0) { "$moved Age(s) generate different terrain than before" }
}

/**
 * Every Age in a save, by name, pointing at its region folder.
 *
 * Insists on finding some, because the failure this guards against is silent: a mistyped path reads as
 * a world with no Ages, every comparison is then vacuous, and the check passes having compared
 * nothing. (Gradle runs this from the module directory, so a path relative to the repo root is exactly
 * the mistake to expect.)
 */
private fun agesIn(world: File): Map<String, File> {
    require(world.isDirectory) { "No such world folder: ${world.absolutePath}" }
    val ages = File(world, "dimensions/agesandtheart").listFiles().orEmpty()
        .filter { File(it, "region").isDirectory }
        .associate { it.name to File(it, "region") }
    require(ages.isNotEmpty()) { "No Ages with saved chunks under ${world.absolutePath}" }
    return ages
}

/** What a stored chunk holds, reduced to the part generation is answerable for: one digest per section. */
private data class ChunkTerrain(val sections: Map<Int, String>, val status: String)

/** Every stored chunk in an Age, by position, digested down to what generation put there. */
private fun terrainOf(regionFolder: File): Map<String, ChunkTerrain> {
    val terrain = mutableMapOf<String, ChunkTerrain>()
    for (region in regionFolder.listFiles().orEmpty().filter { it.extension == "mca" }.sortedBy { it.name }) {
        for ((position, chunk) in chunksIn(region)) {
            terrain[position] = ChunkTerrain(terrainDigest(chunk), chunk.getString("Status"))
        }
    }
    return terrain
}

/**
 * Reads a region file's chunks straight out of the container format: a 4 KiB header of 1024 packed
 * `(offset, sectors)` locations, then each chunk as a length, a compression byte and a payload.
 *
 * Hand-rolled because vanilla's own [net.minecraft.world.level.chunk.storage.RegionFile] wants a live
 * storage context to be built, and this is only reading.
 */
private fun chunksIn(region: File): List<Pair<String, CompoundTag>> {
    val bytes = region.readBytes()
    if (bytes.size < SECTOR_BYTES) return emptyList()
    val chunks = mutableListOf<Pair<String, CompoundTag>>()

    for (slot in 0..<CHUNKS_PER_REGION) {
        val location = readInt(bytes, slot * Int.SIZE_BYTES)
        val sector = location ushr 8
        if (sector == 0) continue // never written

        val start = sector * SECTOR_BYTES
        if (start + CHUNK_HEADER_BYTES > bytes.size) continue
        val length = readInt(bytes, start)
        val compression = bytes[start + Int.SIZE_BYTES].toInt()
        val payload = bytes.copyOfRange(start + CHUNK_HEADER_BYTES, start + CHUNK_HEADER_BYTES + length - 1)

        val stream = when (compression) {
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(payload))
            COMPRESSION_ZLIB -> InflaterInputStream(ByteArrayInputStream(payload))
            else -> ByteArrayInputStream(payload)
        }
        val chunk = DataInputStream(stream).use { NbtIo.read(it, NbtAccounter.unlimitedHeap()) }
        chunks += "${region.nameWithoutExtension}#$slot" to chunk
    }
    return chunks
}

/** What generation actually put in a chunk: the blocks and the biomes, digested one section at a time. */
private fun terrainDigest(chunk: CompoundTag): Map<Int, String> {
    val sections = chunk.getList("sections", Tag.TAG_COMPOUND.toInt())
    return (0..<sections.size).associate { index ->
        val section = sections.getCompound(index)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(section.getCompound("block_states").toString().toByteArray())
        digest.update(section.getCompound("biomes").toString().toByteArray())
        section.getByte("Y").toInt() to HexFormat.of().formatHex(digest.digest())
    }
}

private fun readInt(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF shl 24) or
        (bytes[at + 1].toInt() and 0xFF shl 16) or
        (bytes[at + 2].toInt() and 0xFF shl 8) or
        (bytes[at + 3].toInt() and 0xFF)

/** The only chunk status whose contents generation is finished answering for. */
private const val FULL = "minecraft:full"
private const val MAX_REPORTED = 8
private const val SECTION_BLOCKS = 16
private const val SECTOR_BYTES = 4096
private const val CHUNKS_PER_REGION = 1024

// A chunk's own header: four bytes of length, then one naming its compression.
private const val CHUNK_HEADER_BYTES = 5
private const val COMPRESSION_GZIP = 1
private const val COMPRESSION_ZLIB = 2
