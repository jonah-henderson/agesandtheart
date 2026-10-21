package co.voik.agesandtheart.generation

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.book.LinkTarget
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.phys.Vec3

/**
 * Where a found linking book home leads: the world's spawn, or somewhere else in the same world — a spot on dry land,
 * a biome worth seeing ([BIOMES]), or beside a structure worth finding ([STRUCTURES]), the nearest of them to a point
 * drawn between [NEAREST] and [FARTHEST] from spawn. Anything that finds nothing falls back to the spot, and the spot
 * to spawn.
 */
object FoundDestinations {

    /** How often a book leads away from spawn, and how those that do are shared between biomes and structures. */
    data class Wandering(val elsewhereChance: Float, val biomeShare: Float, val structureShare: Float) {
        companion object {
            /** Half home; of the rest, mostly a spot, sometimes a biome, rarely a structure (Jonah). */
            val DEFAULT = Wandering(elsewhereChance = 0.5f, biomeShare = 0.3f, structureShare = 0.1f)

            val CODEC: Codec<Wandering> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.floatRange(0f, 1f).optionalFieldOf("elsewhere_chance", DEFAULT.elsewhereChance).forGetter(Wandering::elsewhereChance),
                    Codec.floatRange(0f, 1f).optionalFieldOf("biome_share", DEFAULT.biomeShare).forGetter(Wandering::biomeShare),
                    Codec.floatRange(0f, 1f).optionalFieldOf("structure_share", DEFAULT.structureShare).forGetter(Wandering::structureShare),
                ).apply(instance, ::Wandering)
            }
        }
    }

    /**
     * A found book's way home. [mayLookForStructures] is false off the server thread — a structure processor's
     * books — since finding a structure reads chunks the level holds; biomes and spots are read off the generator.
     */
    fun home(server: MinecraftServer, random: RandomSource, wandering: Wandering, mayLookForStructures: Boolean): LinkTarget {
        val spawn = LinkTarget.home(server)
        val world = server.getLevel(spawn.dimension) ?: return spawn
        // One draw for the whole outcome, each share a stretch of it: structures first, then biomes, then spots.
        val drawn = random.nextFloat()
        if (drawn >= wandering.elsewhereChance) return spawn
        val structureUpTo = wandering.elsewhereChance * wandering.structureShare
        val biomeUpTo = structureUpTo + wandering.elsewhereChance * wandering.biomeShare
        val wantsAStructure = mayLookForStructures && drawn < structureUpTo
        val wantsABiome = !wantsAStructure && drawn < biomeUpTo
        val from = pointAwayFrom(BlockPos.containing(spawn.position), random)
        val found = when {
            wantsAStructure -> besideAStructure(world, from, random)
            wantsABiome -> inABiome(world, from)?.let { Arrival(it, yaw = random.nextFloat() * FULL_TURN) }
            else -> null
        }
        val arrival = found ?: onDryLand(world, spawn, random) ?: return spawn
        return LinkTarget(world.dimension(), Vec3.atBottomCenterOf(arrival.at), arrival.yaw, WordNames.placeName(world.dimension()))
    }

    private data class Arrival(val at: BlockPos, val yaw: Float)

    private fun pointAwayFrom(spawn: BlockPos, random: RandomSource): BlockPos {
        val angle = (random.nextFloat() * Mth.TWO_PI).toDouble()
        val distance = NEAREST + random.nextFloat() * (FARTHEST - NEAREST)
        return BlockPos(spawn.x + (Mth.cos(angle) * distance).toInt(), spawn.y, spawn.z + (Mth.sin(angle) * distance).toInt())
    }

    /** A spot drawn away from spawn whose surface is not the sea, in a few tries. */
    private fun onDryLand(world: ServerLevel, spawn: LinkTarget, random: RandomSource): Arrival? {
        val generator = world.chunkSource.generator
        val randomState = world.chunkSource.randomState()
        fun isDry(at: BlockPos): Boolean {
            val surface = generator.getBaseHeight(at.x, at.z, Heightmap.Types.WORLD_SURFACE_WG, world, randomState)
            val floor = generator.getBaseHeight(at.x, at.z, Heightmap.Types.OCEAN_FLOOR_WG, world, randomState)
            return surface == floor
        }
        val dry = generateSequence { pointAwayFrom(BlockPos.containing(spawn.position), random) }
            .take(DRY_LAND_TRIES)
            .firstOrNull(::isDry) ?: return null
        return Arrival(Ages.footingIn(world, dry.x, dry.z), yaw = random.nextFloat() * FULL_TURN)
    }

    private fun inABiome(world: ServerLevel, from: BlockPos): BlockPos? =
        LinkingIn.inBiome(world, from.atY(world.seaLevel), BIOME_REACH) { it.`is`(BIOMES) }

    /** On the ground beside the nearest structure to [from], facing it, or in front of where it starts. */
    private fun besideAStructure(world: ServerLevel, from: BlockPos, random: RandomSource): Arrival? {
        val wanted = world.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(STRUCTURES).orElse(null) ?: return null
        val located = world.chunkSource.generator.findNearestMapStructure(world, wanted, from, STRUCTURE_REACH_CHUNKS, false) ?: return null
        val (at, structure) = located.first to located.second
        val box = boundsOf(world, at, structure.value()) ?: BoundingBox(at)
        val (x, z) = besideOf(box, random)
        val standing = Ages.footingIn(world, x, z)
        return Arrival(standing, yaw = yawToward(standing, box.center))
    }

    /** The structure's whole extent, laid out as far as its start and no further. */
    private fun boundsOf(world: ServerLevel, at: BlockPos, structure: Structure): BoundingBox? {
        val chunk = world.getChunk(SectionPos.blockToSectionCoord(at.x), SectionPos.blockToSectionCoord(at.z), ChunkStatus.STRUCTURE_STARTS, true)
        return chunk?.getStartForStructure(structure)?.takeIf { it.isValid }?.boundingBox
    }

    /** A column [BESIDE] out from the middle of one side of [box], the side drawn. */
    private fun besideOf(box: BoundingBox, random: RandomSource): Pair<Int, Int> {
        val middle = box.center
        return when (random.nextInt(SIDES)) {
            0 -> middle.x to box.minZ() - BESIDE
            1 -> middle.x to box.maxZ() + BESIDE
            2 -> box.minX() - BESIDE to middle.z
            else -> box.maxX() + BESIDE to middle.z
        }
    }

    /** Minecraft's yaw: nothing faces south, a quarter turn faces west. */
    private fun yawToward(from: BlockPos, to: BlockPos): Float =
        Math.toDegrees(Mth.atan2(-(to.x - from.x).toDouble(), (to.z - from.z).toDouble())).toFloat()

    private val BIOMES: TagKey<Biome> = TagKey.create(Registries.BIOME, "found_book_destinations".location())
    private val STRUCTURES: TagKey<Structure> = TagKey.create(Registries.STRUCTURE, "found_book_destinations".location())

    /** How far from spawn the point a destination is looked for from lies. */
    private const val NEAREST = 400
    private const val FARTHEST = 2000

    /** How far round that point a biome or a structure is looked for; vanilla's `/locate` reaches 100 chunks. */
    private const val BIOME_REACH = 1024
    private const val STRUCTURE_REACH_CHUNKS = 64

    private const val DRY_LAND_TRIES = 8

    /** Out from a structure's wall, enough to stand clear of it and see it. */
    private const val BESIDE = 6
    private const val SIDES = 4

    private const val FULL_TURN = 360f
}
