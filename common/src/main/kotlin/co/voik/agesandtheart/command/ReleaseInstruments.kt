package co.voik.agesandtheart.command

import co.voik.agesandtheart.ClientBuildPayload
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.Payloads
import co.voik.agesandtheart.ServerBuildPayload
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Report
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.locale.Language

internal object ReleaseInstruments {

    /** What a release has to keep the same for players and servers to play together. */
    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        age.then(registeredSubcommand())
            .then(unnamedSubcommand())
    }

    /**
     * `/age registered` — every id of ours in a built-in registry, as `<registry> <id>`, every payload, and
     * the generator version. `CompatibilityRecordCheck` compares this against `compatibility.record`.
     */
    private fun registeredSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("registered")
            .executes { context -> runRegistered(Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runRegistered(Report.structured(context.source)) },
            )

    private fun runRegistered(report: Report): Int {
        val content = registeredContent()
        report.fact("generator", AgeRecipe.CURRENT_GENERATOR_VERSION) {
            "Generator version ${AgeRecipe.CURRENT_GENERATOR_VERSION}"
        }
        report.fact("content", content) { content.joinToString("\n") }
        report.finish()
        return SUCCESS
    }

    /**
     * `/age unnamed` — every item, block, entity and effect of ours whose name has no translation in the
     * server's language, which a player would see as a raw key. `NamesOnServerCheck` asserts there are none.
     */
    private fun unnamedSubcommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("unnamed")
            .executes { context -> runUnnamed(Report.prose(context.source)) }
            .then(
                Commands.literal(Report.STRUCTURED_LITERAL)
                    .executes { context -> runUnnamed(Report.structured(context.source)) },
            )

    private fun runUnnamed(report: Report): Int {
        val unnamed = unnamedContent()
        report.fact("unnamed", unnamed) {
            if (unnamed.isEmpty()) "Everything of ours has a name." else unnamed.joinToString("\n")
        }
        report.finish()
        return SUCCESS
    }

    private fun unnamedContent(): List<String> {
        val language = Language.getInstance()
        fun <T : Any> untranslated(kind: String, registry: Registry<T>, key: (T) -> String) =
            registry.entrySet()
                .filter { (resourceKey, _) -> resourceKey.identifier().namespace == Constants.MOD_ID }
                .map { (resourceKey, entry) -> Triple(kind, resourceKey.identifier(), key(entry)) }
                .filterNot { (_, _, translationKey) -> language.has(translationKey) }
                .map { (kind, id, translationKey) -> "$kind $id ($translationKey)" }
        return (
            untranslated("item", BuiltInRegistries.ITEM) { it.descriptionId } +
                untranslated("block", BuiltInRegistries.BLOCK) { it.descriptionId } +
                untranslated("entity_type", BuiltInRegistries.ENTITY_TYPE) { it.descriptionId } +
                untranslated("mob_effect", BuiltInRegistries.MOB_EFFECT) { it.descriptionId }
            ).sorted()
    }

    private fun registeredContent(): List<String> {
        val registered = BuiltInRegistries.REGISTRY.flatMap { registry ->
            val kind = registry.key().identifier().path
            registry.keySet().filter { it.namespace == Constants.MOD_ID }.map { "$kind $it" }
        }
        val payloads = Payloads.ROUTES.map { it.type.id } + ServerBuildPayload.TYPE.id + ClientBuildPayload.TYPE.id
        return (registered + payloads.map { "payload $it" }).sorted()
    }
}
