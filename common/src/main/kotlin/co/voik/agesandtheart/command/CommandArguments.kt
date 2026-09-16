package co.voik.agesandtheart.command

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.Report
import co.voik.agesandtheart.age.ReportFor
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel

/**
 * What every `/age` subcommand needs to be one: the argument names they share, Brigadier's two result
 * codes, the both-ways answer, and the two ways of naming an Age.
 *
 * **Top-level rather than members of [AgeCommand], which is the point.** These were `internal` members of
 * the command object, so every instrument imported the public command tree to reach its helpers and the
 * one object served as both a tree and a grab-bag. Declared here they are simply package-visible: nothing
 * in `command` imports anything to use them.
 */

internal const val NAME_ARGUMENT = "name"
internal const val SEED_ARGUMENT = "seed"
internal const val SPECIFICATION_ARGUMENT = "spec"

// Brigadier command result codes.
internal const val SUCCESS = 1
internal const val FAILURE = 0

/**
 * A subcommand that can answer either way: as prose, or — written `/age <name> json …` — as one
 * structured document (see [Report]).
 *
 * **The literal goes immediately after the subcommand, not at the end**, and `/age write` is why: its
 * sentence is a greedy string, so anything after it is swallowed as another word. One position for all
 * of them beats a rule with an exception in it.
 *
 * [arguments] is called *twice* to build two independent subtrees. That is the point of taking a
 * builder rather than a node: a Brigadier node cannot be hung in two places, and writing the tree out
 * twice is the duplication that would drift.
 *
 * **Only commands that really build a document get one**, so a `json` that parses always means
 * structure exists behind it.
 */
internal fun reporting(
    name: String,
    arguments: (ReportFor) -> ArgumentBuilder<CommandSourceStack, *>,
): LiteralArgumentBuilder<CommandSourceStack> =
    Commands.literal(name)
        .then(arguments { context -> Report.prose(context.source) })
        .then(
            Commands.literal(Report.STRUCTURED_LITERAL)
                .then(arguments { context -> Report.structured(context.source) }),
        )

internal fun ageId(name: String): Identifier =
    Identifier.fromNamespaceAndPath(Constants.MOD_ID, name.lowercase())

/** An Age a command named: its id, and the recipe it was written from. */
internal data class NamedAge(val id: Identifier, val recipe: AgeRecipe)

/** The Age called [name] — or null, having already said there is none. */
internal fun namedAge(source: CommandSourceStack, name: String, report: Report): NamedAge? {
    val id = ageId(name)
    val recipe = AgeSavedData.get(source.server).recipe(id)
    if (recipe == null) {
        report.fail("No Age named '$name' — create it with /age create $name")
        return null
    }
    return NamedAge(id, recipe)
}

/** The Age called [name], opened — or null, having already said why. */
internal fun openNamedAge(source: CommandSourceStack, name: String, report: Report): ServerLevel? {
    val age = namedAge(source, name, report) ?: return null
    return Ages.open(source.server, age.id)
}
