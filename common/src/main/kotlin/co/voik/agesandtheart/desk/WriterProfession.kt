package co.voik.agesandtheart.desk

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.location
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.item.trading.TradeSet
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import com.google.common.collect.ImmutableSet
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap

/**
 * The writer: a villager who works a writer's desk, and sells what the Art is written with.
 *
 * **Only the desk's centre is the job site.** A desk is five blocks and one of them answers every
 * question; if the wings were job sites too, one desk would record five points of interest and a writer
 * could take up post at a bookshelf.
 *
 * **Both job-site predicates read [AgeConfig.villagerWriters] live**, which is the only way a registry
 * that freezes at startup can be turned off at all. `acquirableJobSite` stops villagers taking the job;
 * `heldJobSite` stops them keeping it, so the setting means what it says the moment it changes.
 *
 * The trades themselves are datapack content — `data/agesandtheart/trade_set/` and `villager_trade/`,
 * both datapack registries in 26.1 — so nothing about what a writer sells is decided here.
 */
object WriterProfession {

    val ID: Identifier = "writer".location()

    val POI: ResourceKey<PoiType> = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, ID)

    /** One writer to a desk, and it must be beside it to be at work — vanilla's numbers for a workstation. */
    private const val MAX_TICKETS = 1

    private const val VALID_RANGE = 1

    private const val LEVELS = 5

    /** Every facing of the desk's centre, and nothing else. */
    fun jobSiteStates(desk: Block): Set<BlockState> =
        desk.stateDefinition.possibleStates
            .filter { it.getValue(WritersDeskBlock.PART) == DeskPart.CENTRE }
            .toSet()

    fun poiType(desk: Block): PoiType = PoiType(jobSiteStates(desk), MAX_TICKETS, VALID_RANGE)

    fun profession(): VillagerProfession = VillagerProfession(
        // Vanilla builds this same key from the id it is registered under; ours is spelled once here
        // because the constructor is what a mod gets, and `register` is private.
        Component.translatable("entity.${ID.namespace}.villager.${ID.path}"),
        ::isTheDesk,
        ::isTheDesk,
        ImmutableSet.of(),
        ImmutableSet.of(),
        // Page-turning, borrowed until Phase 9 has a sound of its own.
        SoundEvents.VILLAGER_WORK_LIBRARIAN,
        tradeSetsByLevel(),
    )

    private fun isTheDesk(poi: Holder<PoiType>): Boolean =
        AgeConfig.villagerWriters.get() && poi.`is`(POI)

    /** `agesandtheart:writer_level_1` … `_5`, matching the files under `data/agesandtheart/trade_set/`. */
    private fun tradeSetsByLevel(): Int2ObjectMap<ResourceKey<TradeSet>> {
        val byLevel = Int2ObjectOpenHashMap<ResourceKey<TradeSet>>()
        for (level in 1..LEVELS) {
            byLevel.put(level, ResourceKey.create(Registries.TRADE_SET, "${ID.path}_level_$level".location()))
        }
        return Int2ObjectMaps.unmodifiable(byLevel)
    }
}
