package co.voik.agesandtheart

/**
 * Marks a spec that needs Minecraft's registries standing up — the only slow thing in the suite, a few
 * seconds paid once per JVM. `./gradlew :common:test -Pfast` skips them.
 *
 * **Written as `@Tags(NEEDS_REGISTRIES)` on the class, never `tags(…)` in the spec body.** Kotest
 * *constructs* a spec to discover its tests, so anything the constructor touches is paid before any
 * filter applies — the annotation is read off the class, so a filtered-out spec is never built. Fixtures
 * inside a spec should be `by lazy` for the same reason.
 */
const val NEEDS_REGISTRIES = "NeedsRegistries"
