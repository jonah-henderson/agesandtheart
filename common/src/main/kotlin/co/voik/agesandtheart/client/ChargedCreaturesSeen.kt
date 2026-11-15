package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.consequence.ChargedCreaturePayload
import net.minecraft.world.entity.Entity
import java.util.UUID

/**
 * The creatures this client has been told are charged ([co.voik.agesandtheart.age.consequence.ChargedCreatures]), which
 * it draws in a charged creeper's swirl. By id rather than entity number, since a number is used again once its
 * creature is gone.
 */
object ChargedCreaturesSeen {

    private val charged = HashSet<UUID>()

    fun heard(payload: ChargedCreaturePayload) {
        charged += payload.creature
    }

    fun isCharged(entity: Entity): Boolean = entity.uuid in charged

    fun forget() {
        charged.clear()
    }
}
