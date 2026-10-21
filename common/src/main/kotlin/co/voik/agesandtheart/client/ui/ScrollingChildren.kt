package co.voik.agesandtheart.client.ui

import net.minecraft.client.gui.components.events.ContainerEventHandler

/**
 * The wheel handed to the widget under the pointer, as any screen does — for a container screen, whose own
 * `mouseScrolled` in 26.3 offers it to the hovered slot and answers false, so no list or text box in one ever scrolled.
 */
fun ContainerEventHandler.scrollChildUnder(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
    getChildAt(mouseX, mouseY).filter { it.mouseScrolled(mouseX, mouseY, scrollX, scrollY) }.isPresent
