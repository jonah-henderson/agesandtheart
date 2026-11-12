package co.voik.agesandtheart.client.settings

import co.voik.agesandtheart.AgeClientLook
import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.platform.Services
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import me.shedaniel.clothconfig2.api.Requirement
import me.shedaniel.clothconfig2.impl.builders.FieldBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.neoforged.neoforge.common.ModConfigSpec

/**
 * Whether Cloth Config is installed, under either loader's name for it — **apart from [ClothSettings]**, so asking never
 * loads a class that names one of Cloth's.
 */
object ClothPresence {
    fun isLoaded(): Boolean = Services.PLATFORM.isModLoaded(FABRIC_ID) || Services.PLATFORM.isModLoaded(NEOFORGE_ID)

    private const val FABRIC_ID = "cloth-config"
    private const val NEOFORGE_ID = "cloth_config"
}

/**
 * The settings screen where Cloth Config is installed (Jonah, 2026-11-12): Cloth's own, as most mods have it — a tab a
 * section, a search across them, a row a setting with its reset — built off the specs ([SettingsReading]), so a setting
 * added to either is here with nothing written. A loader opens its own config screen where Cloth is not installed.
 *
 * **What this machine draws is always its own to change; what the server decides is not.** The server's settings are
 * changed here only where this machine is the server — singleplayer, or a world opened to LAN — as NeoForge's own screen
 * allows; on a server elsewhere they show what they are set to and cannot be changed, and on the title screen, where no
 * world has read them, the tab says so.
 */
object ClothSettings {

    fun screen(parent: Screen?): Screen {
        val builder = ConfigBuilder.create().setParentScreen(parent).setTitle(TITLE)
        val entries = builder.entryBuilder()
        val changed = mutableSetOf<ModConfigSpec>()
        val headings = mutableMapOf<Page, MutableList<AbstractConfigListEntry<*>>>()
        for ((spec, locked) in specs()) {
            for (section in SettingsReading.sectionsOf(spec)) {
                val byPage = section.settings.groupBy { pageOf(section, it) }
                for ((page, settings) in byPage) {
                    val shown = if (spec.isLoaded) {
                        settings.mapNotNull { entryFor(entries, it, locked) { changed += spec } }
                    } else {
                        listOf(entries.startTextDescription(OPEN_A_WORLD).build())
                    }
                    val heading = entries.startSubCategory(section.title, shown).setExpanded(true).build()
                    headings.getOrPut(page, ::mutableListOf) += heading
                }
            }
        }
        // In the pages' own order, and a page with nothing on it left out rather than shown empty.
        for (page in Page.entries) {
            val shown = headings[page] ?: continue
            val category = builder.getOrCreateCategory(page.title)
            shown.forEach(category::addEntry)
        }
        builder.setSavingRunnable { changed.forEach(ModConfigSpec::save) }
        return builder.build()
    }

    /**
     * The pages the settings are shown on (Jonah, 2026-11-12), each setting under a heading for the section its config
     * keeps it in. The pages are the screen's alone: the configs' own sections, and so every file, stay as they are.
     */
    enum class Page(key: String) {
        PERFORMANCE("performance"),
        BALANCE("balance"),
        STORAGE("storage"),
        TWEAKS("tweaks"),
        ;

        val title: Component = Component.translatable("config.${Constants.MOD_ID}.page.$key")
    }

    /** The page [setting] is shown on, by its section and its name there; a setting no page names goes under Tweaks. */
    fun pageOf(section: SettingsSection, setting: Setting): Page =
        PAGES["${section.key}.${setting.value.path.last()}"] ?: PAGES[section.key] ?: Page.TWEAKS

    /** Where each section goes, and each setting that goes elsewhere than its section. */
    val PAGES: Map<String, Page> = mapOf(
        "rendering" to Page.PERFORMANCE,
        "linking" to Page.PERFORMANCE,
        "phenomena.maxPhenomena" to Page.PERFORMANCE,
        "phenomena.phenomenaLimitScope" to Page.PERFORMANCE,
        "phenomena" to Page.BALANCE,
        "villagers" to Page.BALANCE,
        "fusion_compounder" to Page.BALANCE,
        "drying_rack" to Page.BALANCE,
        "plasma" to Page.BALANCE,
        "age_save_data" to Page.STORAGE,
        "easter_eggs" to Page.TWEAKS,
    )

    /** Each spec, and why it cannot be changed here, where it cannot. */
    private fun specs(): List<Pair<ModConfigSpec, Component?>> {
        val isTheServer = Minecraft.getInstance().singleplayerServer != null
        return listOf(AgeClientLook.SPEC to null, AgeConfig.SPEC to (if (isTheServer) null else SET_BY_THE_SERVER))
    }

    /**
     * The entry [setting] is changed with, chosen by what its spec holds — a toggle, a choice of its values, a slider
     * across its range, a field for a number with none — or null for a kind no setting of ours holds.
     */
    private fun entryFor(entries: ConfigEntryBuilder, setting: Setting, locked: Component?, changed: () -> Unit): AbstractConfigListEntry<*>? {
        val tooltip = (wrapped(setting.description) + listOfNotNull(locked).flatMap(::wrapped)).toTypedArray()
        fun <T> saving(chosen: T) {
            setting.set(chosen as Any)
            changed()
        }
        val range = setting.value.spec.getRange<Comparable<Any>>()
        val built: FieldBuilder<*, *, *>? = when (val now = setting.current()) {
            is Boolean -> entries.startBooleanToggle(setting.name, now)
                .setDefaultValue(setting.default() as Boolean).setTooltip(*tooltip).setSaveConsumer(::saving)
            is Enum<*> -> enumEntry(entries, setting, now, tooltip, ::saving)
            is Int -> if (range != null) {
                entries.startIntSlider(setting.name, now, range.min as Int, range.max as Int)
                    .setDefaultValue(setting.default() as Int).setTooltip(*tooltip).setSaveConsumer(::saving)
            } else {
                entries.startIntField(setting.name, now)
                    .setDefaultValue(setting.default() as Int).setTooltip(*tooltip).setSaveConsumer(::saving)
            }
            is Double -> entries.startDoubleField(setting.name, now)
                .setMin((range?.min as? Double) ?: Double.NEGATIVE_INFINITY)
                .setMax((range?.max as? Double) ?: Double.POSITIVE_INFINITY)
                .setDefaultValue(setting.default() as Double).setTooltip(*tooltip).setSaveConsumer(::saving)
            else -> null
        }
        if (locked != null) built?.setRequirement(NEVER)
        return built?.build()
    }

    /** [text] as the lines a tooltip shows, since Cloth's draws each line as it is given. */
    private fun wrapped(text: Component): List<Component> =
        Minecraft.getInstance().font.splitter.splitLines(text, TOOLTIP_WIDTH, Style.EMPTY).map { Component.literal(it.string) }

    private fun enumEntry(
        entries: ConfigEntryBuilder,
        setting: Setting,
        now: Enum<*>,
        tooltip: Array<Component>,
        saving: (Any) -> Unit,
    ): FieldBuilder<*, *, *> {
        @Suppress("UNCHECKED_CAST") // An enum's class is the class of its values, which is all Cloth asks of it.
        val kind = now.declaringJavaClass as Class<Enum<*>>
        return entries.startEnumSelector(setting.name, kind, now)
            .setEnumNameProvider { SettingsReading.nameOf(setting, it) }
            .setDefaultValue(setting.default() as Enum<*>)
            .setTooltip(*tooltip)
            .setSaveConsumer(saving)
    }

    /** How wide a tooltip's lines run, in pixels: about vanilla's own. */
    private const val TOOLTIP_WIDTH = 220

    /** What a setting that cannot be changed here is held to: shown, and greyed. */
    private val NEVER = Requirement { false }

    private val TITLE: Component = Component.translatable("config.${Constants.MOD_ID}.title")
    private val OPEN_A_WORLD: Component = Component.translatable("config.${Constants.MOD_ID}.open_a_world")
    private val SET_BY_THE_SERVER: Component = Component.translatable("config.${Constants.MOD_ID}.set_by_the_server")
}
