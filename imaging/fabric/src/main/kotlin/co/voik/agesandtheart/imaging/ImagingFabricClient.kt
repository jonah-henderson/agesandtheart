package co.voik.agesandtheart.imaging

import co.voik.agesandtheart.imaging.client.ViewfinderView
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

fun initClient() {
    ClientTickEvents.END_CLIENT_TICK.register(ViewfinderView::tick)
}
