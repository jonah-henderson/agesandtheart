package co.voik.agesandtheart.worldgen.dni

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.generation.AgeChunkGenerator
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings

/**
 * Which Ages have a D'ni city, how it is offered to their generator, and where a visitor arrives in it
 * (design §7.6). Where it stands is [DniCitySite]'s; what it is built of is data, see [DniCityStructure].
 */
object DniCity {

    val STRUCTURE: ResourceKey<Structure> = ResourceKey.create(Registries.STRUCTURE, "dni_city".location())

    private val ALGAE = "algae".location()

    /**
     * A sealed world carved into chambers, with the algae named — read off the recipe, since the chambers
     * guarantee the lakes.
     *
     * Not yet asked: `Danger.allowsRuins` and `AgeRecipe.authored`, both of which the design says withhold
     * the ruins.
     */
    fun qualifies(composition: AgeComposition): Boolean =
        composition.underground == Underground.CHAMBERED &&
            Sky.isRoofed(composition) &&
            Features.claimNaming(composition, ALGAE) != null

    /**
     * The city as a set only this Age can place, or nothing. Built here rather than shipped as a
     * `structure_set` for the star fissure's reason — a registered set would reach the overworld too.
     */
    fun structureSets(server: MinecraftServer, composition: AgeComposition): List<Holder<StructureSet>> {
        if (!qualifies(composition)) return emptyList()
        val city = server.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(STRUCTURE).orElse(null)
        if (city == null) {
            Constants.LOG.error("The pack ships no '{}', so an Age written for one has no city", STRUCTURE.identifier())
            return emptyList()
        }
        return listOf(Holder.direct(StructureSet(city, ON_EVERY_CHUNK)))
    }

    /** Every chunk is offered; [DniCityStructure] declines all but the site's. */
    private val ON_EVERY_CHUNK = RandomSpreadStructurePlacement(1, 0, RandomSpreadType.LINEAR, CITY_SALT)

    private const val CITY_SALT = 0xD41

    /**
     * On the floor in front of the start piece's reinforced deepslate frame, facing it — or null where the
     * Age has no city. Read off the structure start, so no chunk is built to answer it.
     */
    fun arrivalIn(level: ServerLevel): Ages.Arrival? {
        val generator = level.chunkSource.generator as? AgeChunkGenerator ?: return null
        val city = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(STRUCTURE) ?: return null
        fun holdsTheCity(set: Holder<StructureSet>) = set.value().structures().any { it.structure().value() === city }
        if (level.chunkSource.generatorState.possibleStructureSets().none(::holdsTheCity)) return null

        val site = DniCitySite.of(generator) ?: return null
        val home = ChunkPos.containing(site.start)
        val start = level.getChunk(home.x, home.z, ChunkStatus.STRUCTURE_STARTS, true)?.getStartForStructure(city)
        if (start == null || !start.isValid) {
            Constants.LOG.warn("The D'ni city at {} did not assemble, so its Age is arrived at the ordinary way", site.start)
            return null
        }
        val centre = start.pieces.firstOrNull() as? PoolElementStructurePiece ?: return null
        return inFrontOfTheFrame(level, centre)
    }

    private fun inFrontOfTheFrame(level: ServerLevel, centre: PoolElementStructurePiece): Ages.Arrival? {
        val element = centre.element as? SinglePoolElement ?: return null
        val template = level.server.structureTemplateManager.getOrCreate(element.templateLocation)
        // A single element places with no pivot, so these are the positions its blocks land at.
        val placed = StructurePlaceSettings().setRotation(centre.rotation)
        val frame = template.filterBlocks(centre.position, placed, Blocks.REINFORCED_DEEPSLATE).map { it.pos() }
        val box = BoundingBox.encapsulatingPositions(frame).orElse(null) ?: return null
        // The frame is one block thick, and is looked through along that axis.
        val facing = if (box.xSpan <= box.zSpan) Direction.EAST else Direction.SOUTH
        val bottomMiddle = BlockPos((box.minX() + box.maxX()) / 2, box.minY(), (box.minZ() + box.maxZ()) / 2)
        return Ages.Arrival(bottomMiddle.relative(facing.opposite), facing)
    }
}
