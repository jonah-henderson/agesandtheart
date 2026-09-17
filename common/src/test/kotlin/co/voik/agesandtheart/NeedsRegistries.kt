package co.voik.agesandtheart

/**
 * Marks a spec that needs Minecraft's registries standing up — the only slow thing in the suite, a few
 * seconds paid once per JVM. `./gradlew :common:test -Pfast` skips them.
 *
 * **The tag is the fixture**: `io.kotest.provided.ProjectConfig` stands the registries up before any spec
 * carrying it runs, so a tagged spec never calls the bootstrap itself. What it cannot reach is a spec's
 * *construction* — a companion's eager properties, or anything the spec body evaluates outside a test —
 * which is paid at discovery, before any listener; those still bootstrap for themselves or stay `by lazy`.
 *
 * **Written as `@Tags(NEEDS_REGISTRIES)` on the class, never `tags(…)` in the spec body.** Kotest
 * *constructs* a spec to discover its tests, so anything the constructor touches is paid before any
 * filter applies — the annotation is read off the class, so a filtered-out spec is never built. Fixtures
 * inside a spec should be `by lazy` for the same reason.
 */
const val NEEDS_REGISTRIES = "NeedsRegistries"
