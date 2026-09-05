package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec

/**
 * That a toolbox is a kit and not a cargo.
 *
 * **The fence itself cannot be asked here, and the reason is worth writing down.** What may go in is
 * "anything carrying `MAX_DAMAGE`", which needs an `ItemStack` — and no stack can be built offline:
 * `Bootstrap.bootStrap()` stands the registries up but does not bind item *components*, so both
 * `ItemStack(item)` and `Item.components()` throw "Components not bound yet". A companion `init` does not
 * rescue it either; that was the first attempt.
 *
 * So the fence is a walk (visual backlog item G) and what is pinned here is the number it fences, which is
 * the half that can rot silently — twenty-seven slots is right for spares only while what fills them is
 * restricted, and somebody widening one without the other is the mistake worth catching.
 */
class ToolboxCheck : FunSpec({

    test("it is a chest's worth of room") {
        check(ToolboxMenu.COMPARTMENTS == A_CHEST) {
            "a toolbox holds ${ToolboxMenu.COMPARTMENTS}, not a chest's $A_CHEST"
        }
    }

    test("its grid is the shape the screen draws") {
        check(ToolboxMenu.ROWS * ToolboxMenu.COLUMNS == ToolboxMenu.COMPARTMENTS) {
            "the grid is ${ToolboxMenu.ROWS}x${ToolboxMenu.COLUMNS} against ${ToolboxMenu.COMPARTMENTS} slots"
        }
    }
}) {
    companion object {
        private const val A_CHEST = 27
    }
}
