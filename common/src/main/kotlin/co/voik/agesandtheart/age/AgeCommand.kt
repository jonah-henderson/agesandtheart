package co.voik.agesandtheart.age

import co.voik.agesandtheart.Constants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/**
 * The `/age` debug command — the spike's trigger for exercising Age creation and travel.
 * (The player-facing Descriptive/Linking Books come later; commands are the fastest way to
 * drive the mechanic while de-risking it.)
 *
 * Brigadier is vanilla, so the whole command tree lives in `common`; each loader only has to
 * hand us its [CommandDispatcher] through its own command-registration event.
 *
 *   /age create <name>  — author a new Age and persist it
 *   /age tp <name>      — travel to an Age
 *   /age list           — list known Ages
 */
object AgeCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("age")
                .requires { it.hasPermission(2) }
                .then(
                    Commands.literal("create").then(
                        Commands.argument("name", StringArgumentType.word())
                            .executes { ctx -> create(ctx) },
                    ),
                )
                .then(
                    Commands.literal("tp").then(
                        Commands.argument("name", StringArgumentType.word())
                            .executes { ctx -> teleport(ctx) },
                    ),
                )
                .then(
                    Commands.literal("list").executes { ctx -> list(ctx) },
                ),
        )
    }

    private fun ageId(name: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase())

    private fun create(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val name = StringArgumentType.getString(ctx, "name")
        val id = ageId(name)
        if (!AgeManager.isSupported()) {
            src.sendFailure(Component.literal("Runtime Ages aren't supported on this loader yet (NeoForge backend pending)"))
            return 0
        }
        if (id in AgeSavedData.get(src.server).ages) {
            src.sendFailure(Component.literal("Age '$name' already exists"))
            return 0
        }
        val level = AgeManager.createAge(src.server, id)
        if (level == null) {
            src.sendFailure(Component.literal("Could not create Age '$name'"))
            return 0
        }
        src.sendSuccess({ Component.literal("Created Age '$name' ($id). Travel with /age tp $name") }, true)
        return 1
    }

    private fun teleport(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.playerOrException
        val name = StringArgumentType.getString(ctx, "name")
        val id = ageId(name)
        if (id !in AgeSavedData.get(src.server).ages) {
            src.sendFailure(Component.literal("No Age named '$name' — create it with /age create $name"))
            return 0
        }
        val level = AgeManager.openAge(src.server, id)
        if (level == null) {
            src.sendFailure(Component.literal("Could not open Age '$name'"))
            return 0
        }
        AgeManager.teleport(player, level)
        src.sendSuccess({ Component.literal("Travelled to Age '$name'") }, true)
        return 1
    }

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val ages = AgeSavedData.get(src.server).ages
        if (ages.isEmpty()) {
            src.sendSuccess({ Component.literal("No Ages yet — write one with /age create <name>") }, false)
        } else {
            src.sendSuccess({ Component.literal("Ages (${ages.size}): " + ages.joinToString(", ")) }, false)
        }
        return 1
    }
}
