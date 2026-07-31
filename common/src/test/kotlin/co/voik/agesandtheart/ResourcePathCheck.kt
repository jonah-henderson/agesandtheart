package co.voik.agesandtheart

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Whether every shipped resource can be *named*.
 *
 * A resource location path is `[a-z0-9/._-]` and nothing else. A capital letter does not fail loudly at
 * the file — it fails when something tries to reference it, and the reference is usually in a JSON file
 * that then fails to parse *entirely*, taking its siblings with it. That is exactly how one capitalised
 * font filename cost the whole script font: the provider was rejected, so the definition was rejected,
 * so the font id never existed and every glyph came out as tofu.
 *
 * Cheap to check, and it catches the whole class rather than the one instance.
 */
class ResourcePathCheck : FunSpec({

    val roots = listOf(
        File("src/main/resources"),
        File("../fabric/src/main/resources"),
        File("../neoforge/src/main/resources"),
    ).filter(File::isDirectory)

    /** `pack.mcmeta` and `META-INF` are read by path, never referenced by identifier. */
    val exempt = Regex("(^|/)(META-INF|pack\\.mcmeta)(/|$)")

    val legal = Regex("[a-z0-9/._-]+")

    test("every referenceable resource path is a legal resource location") {
        check(roots.isNotEmpty()) { "No resource roots found from ${File("").absolutePath}" }

        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter(File::isFile).mapNotNull { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                when {
                    exempt.containsMatchIn(relative) -> null
                    legal.matches(relative) -> null
                    else -> relative
                }
            }
        }
        check(offenders.isEmpty()) {
            "These cannot be named by a resource location:\n  " + offenders.joinToString("\n  ")
        }
    }
})
