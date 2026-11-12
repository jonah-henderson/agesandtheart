package co.voik.agesandtheart.client.settings

import co.voik.agesandtheart.Constants
import com.electronwill.nightconfig.core.UnmodifiableConfig
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.ModConfigSpec
import java.util.Locale

/** One section of a config, as a settings screen shows it: its title, and its settings in the order the spec declares them. */
data class SettingsSection(val spec: ModConfigSpec, val key: String, val title: Component, val settings: List<Setting>)

/** One setting: the spec's value, what it is called, and what it does. */
data class Setting(val value: ModConfigSpec.ConfigValue<*>, val name: Component, val description: Component) {

    /** What the setting is set to, read only where its config is loaded. */
    fun current(): Any? = value.get()

    fun default(): Any? = value.default

    /** Sets it to [chosen], which the entry offering it drew from the setting's own type and range. */
    fun set(chosen: Any) {
        @Suppress("UNCHECKED_CAST") // The value's type is the one its entry was built for (`ClothSettings`).
        (value as ModConfigSpec.ConfigValue<Any>).set(chosen)
    }
}

/** How a spec is read into sections and named, so a screen knows nothing of which config it is showing. */
object SettingsReading {

    /** Every section of [spec], top-level sections in order and their values in order. */
    fun sectionsOf(spec: ModConfigSpec): List<SettingsSection> = spec.values.valueMap().mapNotNull { (key, held) ->
        val section = held as? UnmodifiableConfig ?: return@mapNotNull null
        val settings = section.valueMap().values.filterIsInstance<ModConfigSpec.ConfigValue<*>>().map(::settingOf)
        SettingsSection(spec, key, titleOf(spec, key), settings)
    }

    /** What an enum value is called: its own translation, under its setting's name, or its constant made readable. */
    fun nameOf(setting: Setting, choice: Enum<*>): Component {
        val key = setting.value.spec.translationKey?.let { "$it.${choice.name.lowercase(Locale.ROOT)}" }
        return key?.let(::translatedOrNull) ?: Component.literal(readable(choice.name))
    }

    private fun settingOf(value: ModConfigSpec.ConfigValue<*>): Setting {
        val spec = value.spec
        val name = spec.translationKey?.let(::translatedOrNull) ?: Component.literal(readable(value.path.last()))
        // `<name>.tooltip`, as NeoForge's own screen looks for, and the spec's comment (the file's, in English) failing it.
        val description = spec.translationKey?.let { translatedOrNull("$it.tooltip") }
            ?: Component.literal(spec.comment.orEmpty().lines().joinToString(" ", transform = String::trim))
        return Setting(value, name, description)
    }

    private fun titleOf(spec: ModConfigSpec, section: String): Component {
        val named = spec.getLevelTranslationKey(listOf(section)) ?: "config.${Constants.MOD_ID}.$section"
        return translatedOrNull(named) ?: Component.literal(readable(section))
    }

    private fun translatedOrNull(key: String): Component? =
        if (Language.getInstance().has(key)) Component.translatable(key) else null

    /** `searchForSolidGround` or `EACH_AGE` as words: "Search for solid ground", "Each age". */
    private fun readable(code: String): String {
        val spaced = code.replace('_', ' ').replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase(Locale.ROOT)
        return spaced.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
