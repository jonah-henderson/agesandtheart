package co.voik.agesandtheart

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * That every Mixin class `common` holds is listed in `agesandtheart.mixins.json`, and nothing listed is missing.
 *
 * **An unlisted Mixin is never applied, and nothing says so**: it compiles, the build passes, the server boots, and
 * the behaviour it carries simply is not there. Two of `incongruous`'s went unlisted and a walk was what found it.
 */
class MixinsRegisteredCheck : FunSpec({

    val sources = File("src/main/java/co/voik/agesandtheart/mixin")
    val config = File("src/main/resources/agesandtheart.mixins.json")

    test("every Mixin class is listed in the config, and every listed one exists") {
        check(sources.isDirectory && config.isFile) { "ran from ${File("").absolutePath}, where the Mixins are not" }
        val written = sources.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.relativeTo(sources).path.removeSuffix(".java").replace(File.separatorChar, '.') }
            .toSet()
        val json = JsonParser.parseString(config.readText()).asJsonObject
        val listed = listOf("mixins", "client", "server")
            .flatMap { json.getAsJsonArray(it)?.map { entry -> entry.asString }.orEmpty() }
            .toSet()
        val unlisted = written - listed
        val missing = listed - written
        check(unlisted.isEmpty()) { "these Mixins are written and never applied, since the config does not list them: $unlisted" }
        check(missing.isEmpty()) { "the config lists Mixins that do not exist: $missing" }
    }
})
