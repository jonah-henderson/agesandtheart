package co.voik.agesandtheart.client.settings

import co.voik.agesandtheart.AgeClientLook
import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.Constants
import com.electronwill.nightconfig.core.UnmodifiableConfig
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import net.neoforged.neoforge.common.ModConfigSpec
import java.util.Locale

/**
 * Every setting the settings screens show has an English name, and so does every section and every choice — a screen
 * shows a key or the code's own name for one that has none, which is how a whole section of NeoForge's came out as keys.
 */
class SettingsTranslationsCheck : FunSpec({

    val english: Set<String> by lazy {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("assets/${Constants.MOD_ID}/lang/en_us.json")) {
            "en_us.json is not on the classpath"
        }
        stream.reader().use { JsonParser.parseReader(it).asJsonObject.keySet() }
    }

    /** The keys a spec's sections, settings and choices are named by. */
    fun keysOf(spec: ModConfigSpec): List<String> = spec.values.valueMap().flatMap { (section, held) ->
        val values = (held as UnmodifiableConfig).valueMap().values.filterIsInstance<ModConfigSpec.ConfigValue<*>>()
        // Set in the spec, not left to a screen to guess: NeoForge's falls back to a key of its own making.
        val sectionKey = checkNotNull(spec.getLevelTranslationKey(listOf(section))) { "section $section has no translation key" }
        listOf(sectionKey) + values.flatMap { value ->
            val name = checkNotNull(value.spec.translationKey) { "${value.path} has no translation key in its spec" }
            val choices = (value.default as? Enum<*>)?.declaringJavaClass?.enumConstants.orEmpty()
            listOf(name, "$name.tooltip") + choices.map { "$name.${it.name.lowercase(Locale.ROOT)}" }
        }
    }

    test("every section, setting and choice of both configs is named in English") {
        val missing = (keysOf(AgeConfig.SPEC) + keysOf(AgeClientLook.SPEC)).filterNot(english::contains)
        check(missing.isEmpty()) { "named nowhere in en_us.json: $missing" }
    }

    test("every section and setting has a page of its own, rather than falling under Tweaks unasked") {
        val unplaced = listOf(AgeConfig.SPEC, AgeClientLook.SPEC).flatMap(SettingsReading::sectionsOf).filter { section ->
            section.key !in ClothSettings.PAGES
        }.map { it.key }
        check(unplaced.isEmpty()) { "sections on no page: $unplaced" }
    }

    test("and so is everything the screen says itself") {
        val pages = ClothSettings.Page.entries.map { "page.${it.name.lowercase()}" }
        val said = (listOf("title", "open_a_world", "set_by_the_server") + pages)
            .map { "config.${Constants.MOD_ID}.$it" }
        val missing = said.filterNot(english::contains)
        check(missing.isEmpty()) { "named nowhere in en_us.json: $missing" }
    }
})
