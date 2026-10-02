package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.reward.ScarabHabitat
import co.voik.agesandtheart.generation.Ages
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BiomeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The scarab medallion: the D'ni instrument that says what an Age is *missing* (design §7.1.2).
 *
 * It asks whether scarabs *could* be here rather than whether they are, condition by condition — the
 * analytic instrument to the tree finder's empirical one.
 *
 * The Age's conditions come out whatever it is standing on, since they are what a writer fixes at the
 * desk; where the mud lies is only said once they are met, a walk being worth nothing in an Age whose book
 * is wrong. A prose readout until §7.1.2's screen exists.
 */
class ScarabMedallionItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        // The reading is the server's: it asks the generator and the chunks, neither of which a client has.
        if (level is ServerLevel && player is ServerPlayer) read(level, player)
        player.cooldowns.addCooldown(player.getItemInHand(hand), QUIET_AFTER_A_READING)
        return InteractionResult.SUCCESS
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        builder.accept(Component.translatable(HINT).withStyle(ChatFormatting.GRAY))
    }

    /** Everything the medallion has to say about where [player] is standing. */
    private fun read(level: ServerLevel, player: ServerPlayer) {
        // The overworld can never qualify, and this is where that is true rather than in the conditions. A
        // bespoke Age has no composition to read either, and reads as cold for the same reason.
        val reading = Ages.recipeOf(level)?.let { recipe -> ScarabHabitat.readAge(level, recipe) }
        if (reading == null) {
            say(player, UNWRITTEN)
            chime(level, player, promising = false)
            return
        }

        say(player, READING)
        say(player, lineFor(reading.warmth))

        // Whether the Age has a jungle is a fact about the Age; where the nearest one is, is a fact about
        // here. They were one question until a walk caught the sweep declaring an Age jungleless with a
        // jungle in it, so the condition now reads the Age and only the direction reads the sweep.
        val jungle = if (reading.anyJungle) ScarabHabitat.nearestJungle(level, player.blockPosition()) else null
        say(player, if (reading.anyJungle) JUNGLE_SOMEWHERE else NO_JUNGLE)

        say(player, lineFor(reading.torchflowers))

        // Last among the Age's conditions because it is the one no rewriting of this book can fix.
        if (!reading.writtenByAPlayer) say(player, ANOTHER_HAND)

        chime(level, player, promising = reading.wouldHoldAColony)
        if (!reading.wouldHoldAColony) return

        sayWhereTheJungleIs(level, player, jungle)
        sayWhatTheGroundHolds(level, player)
    }

    /**
     * Which way the nearest jungle lies.
     *
     * Whether you are in one is asked of the biome underfoot rather than of the sweep, which samples every
     * few chunks and would send somebody standing in a jungle off to the north.
     *
     * A null [jungle] here does **not** mean the Age has none — that condition has already passed — but
     * that the sweep could not reach one, which is worth saying rather than staying silent about.
     */
    private fun sayWhereTheJungleIs(level: ServerLevel, player: ServerPlayer, jungle: BlockPos?) {
        if (level.getBiome(player.blockPosition()).`is`(BiomeTags.IS_JUNGLE)) {
            say(player, JUNGLE_HERE)
            return
        }
        if (jungle == null) {
            say(player, JUNGLE_OUT_OF_REACH)
            return
        }
        val here = player.blockPosition()
        say(player, JUNGLE_TOWARD, bearingFrom(here, jungle), howFar(here, jungle))
    }

    /**
     * What the ground here would give a colony: mud open to the sky or lit, sand to build with, and warmth.
     *
     * One sentence about one place rather than three lacks, because the confluence is the thing — mud
     * somewhere and sand somewhere else is two unrelated facts, and would send a player after the wrong one.
     */
    private fun sayWhatTheGroundHolds(level: ServerLevel, player: ServerPlayer) {
        val here = player.blockPosition()
        val site = ScarabHabitat.siteNear(level, here)
        if (site == null) {
            // A colony that has taken every column is not a place with no mud, and saying so would send a
            // player away from the one place that is working.
            val colony = ScarabHabitat.colonyNear(level, here, COLONY_REACH)
            if (colony == null) {
                say(player, NO_MUD)
            } else {
                say(player, COLONY, bearingFrom(here, colony), howFar(here, colony))
            }
            return
        }
        val bearing = bearingFrom(here, site.mud)
        val distance = howFar(here, site.mud)
        val key = when {
            site.wouldHoldAColony -> SITE
            !site.warm -> MUD_NOT_WARM
            else -> MUD_WITHOUT_SAND
        }
        say(player, key, bearing, distance)
    }

    private fun lineFor(warmth: ScarabHabitat.Warmth): String = when (warmth) {
        ScarabHabitat.Warmth.SUITS -> WARM_ENOUGH
        ScarabHabitat.Warmth.TOO_COLD -> TOO_COLD
        ScarabHabitat.Warmth.TOO_HOT -> TOO_HOT
    }

    private fun lineFor(torchflowers: ScarabHabitat.Torchflowers): String = when (torchflowers) {
        ScarabHabitat.Torchflowers.WILD_IN_THE_JUNGLE -> TORCHFLOWERS_WILD
        ScarabHabitat.Torchflowers.AWAY_FROM_THE_JUNGLE -> TORCHFLOWERS_AWAY
        ScarabHabitat.Torchflowers.NONE -> NO_TORCHFLOWERS
    }

    private fun say(player: ServerPlayer, key: String, vararg parts: Component) {
        player.sendSystemMessage(Component.translatable(key, *parts).withStyle(ChatFormatting.GRAY))
    }

    /** §7.1.2's ambient channel in one note: bright where the book is right, flat where it is not. */
    private fun chime(level: ServerLevel, player: ServerPlayer, promising: Boolean) {
        level.playSound(
            null,
            player.blockPosition(),
            SoundEvents.AMETHYST_BLOCK_CHIME,
            SoundSource.PLAYERS,
            CHIME_VOLUME,
            if (promising) BRIGHT else FLAT,
        )
    }

    /**
     * Which of the eight winds [target] lies on from [from].
     *
     * Minecraft's north is −Z and its east is +X, so the angle is measured clockwise from −Z, which is what
     * the compass in a player's hand agrees with.
     */
    private fun bearingFrom(from: BlockPos, target: BlockPos): Component {
        val east = (target.x - from.x).toDouble()
        val south = (target.z - from.z).toDouble()
        val clockwiseFromNorth = Math.toDegrees(atan2(east, -south))
        val wind = ((clockwiseFromNorth / DEGREES_PER_WIND).roundToInt() % WINDS.size + WINDS.size) % WINDS.size
        return Component.translatable(WINDS[wind])
    }

    /**
     * How far off [target] is, as a word — a walk and never a number (design §3.2). The top band is open
     * because "far off" is the honest answer at any distance past caring.
     */
    private fun howFar(from: BlockPos, target: BlockPos): Component {
        val east = (target.x - from.x).toDouble()
        val south = (target.z - from.z).toDouble()
        val paces = sqrt(east * east + south * south)
        val key = when {
            paces < CLOSE_BY -> DISTANCE_CLOSE
            paces < A_SHORT_WALK -> DISTANCE_SHORT_WALK
            paces < SOME_WAY -> DISTANCE_SOME_WAY
            else -> DISTANCE_FAR
        }
        return Component.translatable(key)
    }

    private companion object {
        private const val ITEM = "item.agesandtheart.scarab_medallion"

        const val HINT = "$ITEM.hint"
        const val UNWRITTEN = "$ITEM.unwritten"
        const val READING = "$ITEM.reading"
        const val NO_JUNGLE = "$ITEM.no_jungle"
        const val JUNGLE_SOMEWHERE = "$ITEM.jungle_somewhere"
        const val JUNGLE_HERE = "$ITEM.jungle_here"
        const val JUNGLE_TOWARD = "$ITEM.jungle_toward"
        const val JUNGLE_OUT_OF_REACH = "$ITEM.jungle_out_of_reach"
        const val SITE = "$ITEM.site"
        const val NO_MUD = "$ITEM.no_mud"
        const val MUD_WITHOUT_SAND = "$ITEM.mud_without_sand"
        const val MUD_NOT_WARM = "$ITEM.mud_not_warm"
        const val COLONY = "$ITEM.colony"
        const val ANOTHER_HAND = "$ITEM.another_hand"

        /** About the ground sweep's own reach, so a colony it would have walked over is one it names. */
        const val COLONY_REACH = 96

        const val WARM_ENOUGH = "$ITEM.warm_enough"
        const val TOO_COLD = "$ITEM.too_cold"
        const val TOO_HOT = "$ITEM.too_hot"

        const val TORCHFLOWERS_WILD = "$ITEM.torchflowers_wild"
        const val TORCHFLOWERS_AWAY = "$ITEM.torchflowers_away"
        const val NO_TORCHFLOWERS = "$ITEM.no_torchflowers"

        /** Clockwise from north, which is what [bearingFrom] divides the turn into. */
        val WINDS: List<String> = listOf(
            "$ITEM.north",
            "$ITEM.northeast",
            "$ITEM.east",
            "$ITEM.southeast",
            "$ITEM.south",
            "$ITEM.southwest",
            "$ITEM.west",
            "$ITEM.northwest",
        )

        const val DISTANCE_CLOSE = "$ITEM.close_by"
        const val DISTANCE_SHORT_WALK = "$ITEM.a_short_walk"
        const val DISTANCE_SOME_WAY = "$ITEM.some_way_off"
        const val DISTANCE_FAR = "$ITEM.far_off"

        const val CLOSE_BY = 24.0
        const val A_SHORT_WALK = 96.0
        const val SOME_WAY = 400.0

        const val DEGREES_PER_WIND = 45.0

        /** Long enough that a reading is a thing you do, short enough not to be a wait. */
        const val QUIET_AFTER_A_READING = 40

        const val CHIME_VOLUME = 0.8f
        const val BRIGHT = 1.4f
        const val FLAT = 0.6f
    }
}
