package co.voik.agesandtheart.server

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * An Age opened from inside another level's tick does not take the server down: a book in a mob's hand writes
 * itself and warms its Age on the overworld's tick, while the server is still walking its levels.
 */
@Tags(NEEDS_SERVER)
class OpenedMidTickOnServerCheck : FunSpec({

    val server = DrivenServer.shared

    fun passes(command: String) = server.run(command).startsWith("Test passed")

    test("a book in an armor stand's hand opens its Age on the overworld's tick, and the server carries on") {
        server.run("forceload add $AT $AT")
        server.waitUntil("the chunk the stand stands in loading") { passes("execute if loaded $AT $HEIGHT $AT") }
        server.run(
            "summon minecraft:armor_stand $AT $HEIGHT $AT " +
                "{NoGravity:1b,Tags:[\"$TAG\"],equipment:{mainhand:{id:\"agesandtheart:descriptive_book\",count:1}}}",
        )
        server.waitUntil("the stand's book minting its Age") {
            passes("execute if items entity @e[type=minecraft:armor_stand,tag=$TAG] weapon.mainhand *[agesandtheart:age_id]")
        }
        server.sprint(TICKS_AFTER)
        check(server.gameTime() > 0) { "the server stopped answering" }
        check("ConcurrentModificationException" !in server.saidSoFar()) { "a level opened mid-tick threw: see the boot log" }
        server.run("kill @e[type=minecraft:armor_stand,tag=$TAG]")
        server.run("forceload remove $AT $AT")
    }
})

private const val AT = 4096
private const val HEIGHT = 200
private const val TAG = "opened_mid_tick"
private const val TICKS_AFTER = 20
