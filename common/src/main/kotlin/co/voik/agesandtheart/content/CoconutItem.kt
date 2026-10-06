package co.voik.agesandtheart.content

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/** A coconut, which places a [CoconutBlock] and is what a hanging coconut is picked as. */
class CoconutItem(properties: Properties) : BlockItem(PalmWood.COCONUT, properties) {

    override fun registerBlocks(map: MutableMap<Block, Item>, item: Item) {
        super.registerBlocks(map, item)
        map[PalmWood.HANGING_COCONUT] = item
    }
}
