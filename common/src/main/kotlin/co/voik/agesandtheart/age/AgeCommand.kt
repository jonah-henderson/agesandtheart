package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * The `/age` debug command — the spike's trigger for exercising Age creation and travel. (The
 * player-facing Descriptive/Linking Books are the real interface; commands are the fast way to
 * drive the mechanic.)
 *
 * Brigadier is vanilla, so the whole command tree lives in `common`; each loader only has to hand
 * us its [CommandDispatcher] through its own command-registration event.
 *
 *   /age create <name>  — author a new Age and persist it
 *   /age tp <name>      — travel to an Age
 *   /age list           — list known Ages
 */
object AgeCommand {
    private const val OPERATOR_PERMISSION_LEVEL = 2
    private const val NAME_ARGUMENT = "name"

    // Brigadier command result codes.
    private const val SUCCESS = 1
    private const val FAILURE = 0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("age")
                .requires { source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL) }
                .then(createSubcommand())
                .then(teleportSubcommand())
                .then(listSubcommand()),
        )
    }

    private fun createSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("create").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runCreate),
        )

    private fun teleportSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tp").then(
            Commands.argument(NAME_ARGUMENT, StringArgumentType.word()).executes(::runTeleport),
        )

    private fun listSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("list").executes(::runList)

    private fun ageId(name: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase())

    private fun runCreate(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (!Ages.isSupported()) {
            source.sendFailure(Component.literal("Runtime Ages aren't supported on this loader yet (NeoForge backend pending)"))
            return FAILURE
        }
        if (id in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("Age '$name' already exists"))
            return FAILURE
        }
        val level = Ages.create(source.server, id)
        if (level == null) {
            source.sendFailure(Component.literal("Could not create Age '$name'"))
            return FAILURE
        }
        source.sendSuccess({ Component.literal("Created Age '$name' ($id). Travel with /age tp $name") }, true)
        return SUCCESS
    }

    private fun runTeleport(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val player = source.playerOrException
        val name = StringArgumentType.getString(context, NAME_ARGUMENT)
        val id = ageId(name)
        if (id !in AgeSavedData.get(source.server).ages) {
            source.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return FAILURE
        }
        val level = Ages.open(source.server, id)
        if (level == null) {
            source.sendFailure(Component.literal("Could not open Age '$name'"))
            return FAILURE
        }
        Ages.teleport(player, level)
        source.sendSuccess({ Component.literal("Travelled to Age '$name'") }, true)
        return SUCCESS
    }

    private fun runList(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val ages = AgeSavedData.get(source.server).ages
        if (ages.isEmpty()) {
            source.sendSuccess({ Component.literal("No Ages yet — write one with /age create <name>") }, false)
        } else {
            source.sendSuccess({ Component.literal("Ages (${ages.size}): " + ages.joinToString(", ")) }, false)
        }
        return SUCCESS
    }
}
