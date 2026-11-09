package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/**
 * What each creature a book wrote `tamable` is tempted and tamed with — one `art/temptations.json`, keyed by entity
 * type. A creature it does not name cannot be tamed that way, which leaves the ones vanilla tames to vanilla.
 */
data class Temptations(val byKind: Map<Identifier, List<Want>>) {

    /** Whether [stack] is something [mob] wants. */
    fun tempts(mob: Mob, stack: ItemStack): Boolean = wantsOf(mob.type).any { it.isMet(stack, isBaby = mob.isBaby) }

    fun wantsOf(type: EntityType<*>): List<Want> = byKind[BuiltInRegistries.ENTITY_TYPE.getKey(type)].orEmpty()

    /**
     * One thing a creature wants: an item, or an item tag written `#namespace:path`, holding one of [potions] if any
     * are named, and only as a baby where [babiesOnly].
     */
    data class Want(val item: String, val potions: List<Identifier> = emptyList(), val babiesOnly: Boolean = false) {
        private val tag: TagKey<Item>? =
            item.takeIf { it.startsWith(TAG_MARK) }?.let { TagKey.create(Registries.ITEM, Identifier.parse(it.drop(1))) }

        fun isMet(stack: ItemStack, isBaby: Boolean): Boolean {
            if (babiesOnly && !isBaby) return false
            val isTheItem = tag?.let(stack::`is`) ?: (BuiltInRegistries.ITEM.getKey(stack.item).toString() == item)
            return isTheItem && holdsAWantedPotion(stack)
        }

        private fun holdsAWantedPotion(stack: ItemStack): Boolean {
            if (potions.isEmpty()) return true
            val held = stack.get(DataComponents.POTION_CONTENTS)?.potion()?.orElse(null) ?: return false
            return held.unwrapKey().map { it.identifier() in potions }.orElse(false)
        }

        companion object {
            const val TAG_MARK = "#"

            private val WRITTEN_OUT: Codec<Want> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.STRING.fieldOf("item").forGetter(Want::item),
                    Identifier.CODEC.listOf().optionalFieldOf("potions", emptyList()).forGetter(Want::potions),
                    Codec.BOOL.optionalFieldOf("babies_only", false).forGetter(Want::babiesOnly),
                ).apply(instance, ::Want)
            }

            /** A bare string where nothing but the item is said. */
            val CODEC: Codec<Want> = Codec.either(Codec.STRING, WRITTEN_OUT).xmap(
                { either -> either.map({ Want(it) }, { it }) },
                { want -> if (want.potions.isEmpty() && !want.babiesOnly) Either.left(want.item) else Either.right(want) },
            )

            val STREAM_CODEC: StreamCodec<FriendlyByteBuf, Want> = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Want::item,
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), Want::potions,
                ByteBufCodecs.BOOL, Want::babiesOnly,
                ::Want,
            )
        }
    }

    companion object {
        val FILE: Identifier = "art/temptations.json".location()

        val NONE = Temptations(emptyMap())

        val CODEC: Codec<Temptations> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Identifier.CODEC, Want.CODEC.listOf()).fieldOf("creatures").forGetter(Temptations::byKind),
            ).apply(instance, ::Temptations)
        }

        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, Temptations> =
            ByteBufCodecs.map(::HashMap, Identifier.STREAM_CODEC, Want.STREAM_CODEC.apply(ByteBufCodecs.list()))
                .map(::Temptations) { HashMap(it.byKind) }

        fun of(server: MinecraftServer): Temptations = current.of(server)

        private val current = PerReload { server -> load(server.resourceManager) }

        /** The table in [resources]. The last pack to ship the file wins outright, as the danger table does. */
        fun load(resources: ResourceManager): Temptations {
            val problems = mutableListOf<String>()
            val layers = resources.getResourceStack(FILE)
            val table = layers.asReversed().firstNotNullOfOrNull { ResourceParsing.parse(it, FILE, CODEC, problems) }
            for (problem in problems) Constants.LOG.error("Temptations: {}", problem)
            return table ?: NONE
        }
    }
}
