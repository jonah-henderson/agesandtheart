package co.voik.agesandtheart.page

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.util.UUID

/**
 * Passes the first time a player opens a container with [find] in it, and with [otherwiseChance] after
 * that — the guarantee on vanilla's ruined-portal model: a player's first lost library holds a survey
 * report, later ones may.
 *
 * ```json
 * { "type": "agesandtheart:first_find", "find": "survey_report", "otherwise_chance": 0.5 }
 * ```
 *
 * A container opened with no player, by a hopper, only ever rolls the chance.
 */
class FirstFindCondition(val find: String, val otherwiseChance: Float) : LootItemCondition {

    override fun test(context: LootContext): Boolean {
        val player = context.getOptional(LootContextParams.THIS_ENTITY) as? ServerPlayer
        val isTheirFirst = player != null && FirstFinds.of(context.level.server).claim(player.uuid, find)
        return isTheirFirst || context.random.nextFloat() < otherwiseChance
    }

    override fun codec(): MapCodec<out LootItemCondition> = MAP_CODEC

    companion object {
        private const val NEVER = 0.0f

        val MAP_CODEC: MapCodec<FirstFindCondition> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.STRING.fieldOf("find").forGetter(FirstFindCondition::find),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("otherwise_chance", NEVER)
                    .forGetter(FirstFindCondition::otherwiseChance),
            ).apply(instance, ::FirstFindCondition)
        }
    }
}

/** Which first finds each player has had, under the overworld's data storage. */
private class FirstFinds private constructor(rows: List<Row>) : SavedData() {

    constructor() : this(emptyList())

    private val byPlayer: MutableMap<UUID, MutableSet<String>> =
        rows.associateTo(linkedMapOf()) { it.player to it.finds.toMutableSet() }

    /** Records [find] as [player]'s, answering whether it was the first. */
    fun claim(player: UUID, find: String): Boolean {
        val isNew = byPlayer.getOrPut(player) { mutableSetOf() }.add(find)
        if (isNew) setDirty()
        return isNew
    }

    private data class Row(val player: UUID, val finds: List<String>)

    companion object {
        private const val NAME = "agesandtheart_first_finds"

        private val ROW_CODEC: Codec<Row> = RecordCodecBuilder.create { instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(Row::player),
                Codec.STRING.listOf().fieldOf("finds").forGetter(Row::finds),
            ).apply(instance, ::Row)
        }

        private val CODEC: Codec<FirstFinds> = ROW_CODEC.listOf().fieldOf("players").codec().xmap(
            ::FirstFinds,
        ) { saved -> saved.byPlayer.map { (player, finds) -> Row(player, finds.sorted()) } }

        /** [DataFixTypes.LEVEL] for the reason `AgeSavedData` names it: the record demands one. */
        private val TYPE: SavedDataType<FirstFinds> =
            SavedDataType(Identifier.withDefaultNamespace(NAME), ::FirstFinds, CODEC, DataFixTypes.LEVEL)

        fun of(server: MinecraftServer): FirstFinds = server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
