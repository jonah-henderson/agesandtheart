package co.voik.agesandtheart

import co.voik.agesandtheart.client.ClientPayloads
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * The one pairing the payload tables keep by hand: every clientbound route in [Payloads] has exactly one
 * receiver in [ClientPayloads], and nothing else has one. The two cannot be one list, because a receiver is
 * client code and a dedicated server loads the routes.
 */
@Tags(NEEDS_REGISTRIES)
class PayloadsCheck : FunSpec({

    test("every clientbound payload has one client receiver, and only those do") {
        val clientbound = Payloads.ROUTES.filterIsInstance<Payloads.Clientbound<*>>().map { it.type.id.toString() }
        val received = ClientPayloads.RECEIVERS.map { it.type.id.toString() }
        check(received.sorted() == clientbound.sorted()) {
            "clientbound routes $clientbound and client receivers $received disagree"
        }
    }
})
