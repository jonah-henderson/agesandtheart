package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.equipment.ArmorMaterial
import net.minecraft.world.item.equipment.ArmorType
import net.minecraft.world.item.equipment.EquipmentAsset
import net.minecraft.world.item.equipment.EquipmentAssets
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Armour chipped from deretheni — what you wear into an Age you wrote to be survived rather than lived in.
 *
 * **The lore is the design here rather than decoration.** The suit the D'ni Maintainers wore into
 * newly-written Ages was made of overlapping deretheni plates, and a Maintainer's job was to walk into a
 * world nobody knew was safe. That is exactly the slot §7.7 leaves open: the material a dangerous Age
 * yields should be what lets you work in one.
 *
 * **It is armour you wear instead of armour, not as well as it.** Slightly less protection than iron and a
 * good deal more durability than diamond, so a writer kitted for the environment is kitted worse for a
 * fight. That trade is the whole point — the Age stops being the thing that kills you and whatever lives
 * in it starts being the thing that does.
 *
 * **Two of the three protections are vanilla's own and cost no code at all.** Never catching fire is
 * [Attributes.BURNING_TIME] driven to nought, and never freezing is `#minecraft:freeze_immune_wearables`.
 * Only lava and standing in flame need [tick], because those damage you directly rather than by setting
 * you alight.
 */
object ProtectiveSuit {

    /** What may mend it at an anvil — a tag, so a pack can add to it. */
    val REPAIRS_SUIT: TagKey<Item> = TagKey.create(Registries.ITEM, "repairs_pitchstone_armor".location())

    /** The model set the client dresses a wearer in — `assets/agesandtheart/equipment/pitchstone.json`. */
    val ASSET: ResourceKey<EquipmentAsset> =
        ResourceKey.create(EquipmentAssets.ROOT_ID, "pitchstone".location())

    /**
     * **Durability above diamond, protection below iron** (Jonah, 2026-09-05).
     *
     * Diamond's multiplier is 33 and netherite's 37; iron's plates come to fifteen points of armour and
     * these come to thirteen. Nothing tough and nothing that resists knockback: a stone suit that shrugged
     * off a hit the way netherite does would be the combat armour it is meant not to be.
     */
    val MATERIAL: ArmorMaterial = ArmorMaterial(
        DURABILITY,
        mapOf(
            ArmorType.HELMET to HELMET_ARMOUR,
            ArmorType.CHESTPLATE to CHESTPLATE_ARMOUR,
            ArmorType.LEGGINGS to LEGGINGS_ARMOUR,
            ArmorType.BOOTS to BOOTS_ARMOUR,
            ArmorType.BODY to CHESTPLATE_ARMOUR,
        ),
        ENCHANTABILITY,
        SoundEvents.ARMOR_EQUIP_GENERIC,
        NO_TOUGHNESS,
        NO_KNOCKBACK_RESISTANCE,
        REPAIRS_SUIT,
        ASSET,
    )

    /**
     * [type]'s ordinary armour modifiers with the one that makes this suit what it is.
     *
     * **A quarter of the immunity per piece, so the set matters and a piece is still worth wearing.**
     * `ADD_MULTIPLIED_TOTAL` sums before it multiplies, so four quarters take the burning time to exactly
     * nought and three take it to a quarter — which reads in play as a suit that nearly works, and is the
     * right thing for a player who has found deretheni for boots and not yet for a chestplate.
     *
     * The attribute is vanilla's own and it is the whole of the fire protection: everything that burns you
     * does it by setting you alight for a number of ticks, and this multiplies that number. That includes
     * an Age's inferno, which by its own ruling *sets things alight rather than hurting them*.
     */
    fun attributesFor(type: ArmorType) = MATERIAL.createAttributes(type).withModifierAdded(
        Attributes.BURNING_TIME,
        AttributeModifier(
            "armour.${type.getName()}".location(),
            -A_QUARTER_OF_THE_BURN,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
        ),
        EquipmentSlotGroup.bySlot(type.slot),
    )

    /**
     * What the attribute cannot reach: lava and open flame, which hurt you where you stand rather than by
     * igniting you.
     *
     * **Scoped to the moment of contact on purpose.** A suit that granted fire resistance outright would
     * make blazes and ghasts harmless, and this is meant to leave a writer *weaker* in a fight rather than
     * immune to a class of enemy. So the protection lasts a couple of seconds from the last tick spent in
     * the fire — long enough to climb out of what you fell into, and worth nothing at all against something
     * throwing fire at you across a room.
     *
     * Called from both loaders' end-of-tick beside `Happenings.tick`; there is no shared event.
     */
    fun tick(server: MinecraftServer) {
        for (level in server.allLevels) {
            for (player in level.players()) {
                if (!standingInFire(player)) continue
                if (!wearingTheWholeSuit(player)) continue
                player.addEffect(
                    MobEffectInstance(
                        MobEffects.FIRE_RESISTANCE,
                        LONG_ENOUGH_TO_CLIMB_OUT,
                        NO_AMPLIFIER,
                        true,
                        false,
                        true,
                    ),
                )
            }
        }
    }

    /** Whether the fire is touching them, as opposed to being thrown at them. */
    private fun standingInFire(player: ServerPlayer): Boolean =
        player.isInLava || player.level().getBlockState(player.blockPosition()).`is`(BlockTags.FIRE)

    /**
     * Whether all four pieces are on.
     *
     * **The full set, where the burning attribute is per piece**, because this is the strong half: a pair
     * of boots should take the edge off an inferno and should not let anybody wade into lava.
     */
    private fun wearingTheWholeSuit(player: ServerPlayer): Boolean =
        SUIT.all { (slot, piece) -> player.getItemBySlot(slot).item === piece() }

    private val SUIT: Map<EquipmentSlot, () -> Item> = mapOf(
        EquipmentSlot.HEAD to { AgeContent.PITCHSTONE_HELMET },
        EquipmentSlot.CHEST to { AgeContent.PITCHSTONE_CHESTPLATE },
        EquipmentSlot.LEGS to { AgeContent.PITCHSTONE_LEGGINGS },
        EquipmentSlot.FEET to { AgeContent.PITCHSTONE_BOOTS },
    )

    /** Above diamond's 33 and below netherite's 37. Stone that outlasts a gem is the lore's own claim. */
    private const val DURABILITY = 35

    // Thirteen points against iron's fifteen, and the shortfall is taken off the pieces that carry most.
    private const val HELMET_ARMOUR = 2
    private const val CHESTPLATE_ARMOUR = 5
    private const val LEGGINGS_ARMOUR = 4
    private const val BOOTS_ARMOUR = 2

    /** Iron's, this being neither gold's lottery nor netherite's. */
    private const val ENCHANTABILITY = 9

    private const val NO_TOUGHNESS = 0.0f
    private const val NO_KNOCKBACK_RESISTANCE = 0.0f

    /** Four of these take the burning time to nought exactly. */
    private const val A_QUARTER_OF_THE_BURN = 0.25

    private const val LONG_ENOUGH_TO_CLIMB_OUT = 40
    private const val NO_AMPLIFIER = 0
}
