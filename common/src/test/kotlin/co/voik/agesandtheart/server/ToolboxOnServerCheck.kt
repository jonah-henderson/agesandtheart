package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That a toolbox set down keeps its spares with the world, as a chest does, and not only on the item it breaks into.
 * `data get block` reads what the block entity saves, so what it says is what a reload would find.
 */
@Tags(NEEDS_SERVER)
class ToolboxOnServerCheck : FunSpec({
    val server = DrivenServer.shared

    test("a toolbox set down saves what is in it") {
        server.run("forceload add $TOOLBOX_X 0")
        server.waitUntil("the chunk at ($TOOLBOX_X, 0) loading") {
            server.run("execute if loaded $TOOLBOX_X 0 0").startsWith("Test passed")
        }
        server.run("setblock $TOOLBOX_X $TOOLBOX_Y 0 agesandtheart:toolbox")
        server.run("item replace block $TOOLBOX_X $TOOLBOX_Y 0 container.0 with minecraft:iron_pickaxe")

        val saved = server.run("data get block $TOOLBOX_X $TOOLBOX_Y 0 Items")
        check("minecraft:iron_pickaxe" in saved) { "a toolbox set down saves none of its spares: $saved" }
    }
}) {
    private companion object {
        const val TOOLBOX_X = 40
        const val TOOLBOX_Y = 250
    }
}
