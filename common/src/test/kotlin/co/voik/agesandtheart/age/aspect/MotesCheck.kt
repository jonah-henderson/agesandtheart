package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That every mote a writer may name is a particle the client can actually hang in the air.
 *
 * The vocabulary and the particle are deliberately far apart — a name is read while the corpus is built and
 * the particle is not resolved until an Age is painted — so nothing else would notice a mote that resolves
 * to nothing until a world came up wrong.
 */
@Tags(NEEDS_REGISTRIES)
class MotesCheck : FunSpec({

    test("every named mote resolves to a particle") {
        MinecraftRegistries.ensureStoodUp()
        val unresolved = Motes.ALL.filter { Motes.named(it) == null }
        check(unresolved.isEmpty()) { "these motes name no particle: $unresolved" }
    }
})
