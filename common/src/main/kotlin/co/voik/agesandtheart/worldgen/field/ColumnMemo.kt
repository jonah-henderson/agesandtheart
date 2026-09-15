package co.voik.agesandtheart.worldgen.field

/**
 * The spans of the columns of a chunk and the ring around it, remembered per thread.
 *
 * A 32×32 block square of slots indexed straight off the low bits of the block coordinates, so a chunk and
 * the neighbours its biome probes and carvers reach into all sit in distinct slots. Thread-confined, because
 * a field is shared across every chunk worker. [derive] must answer a column the same way every time.
 */
internal class ColumnMemo(private val derive: (Int, Int) -> Spans) {
    private class Slots {
        val keys = LongArray(SLOTS) { EMPTY_KEY }
        val spans = arrayOfNulls<Spans>(SLOTS)
    }

    private val remembered = ThreadLocal.withInitial { Slots() }

    fun spansAt(worldX: Int, worldZ: Int): Spans {
        val slots = remembered.get()
        val key = (worldX.toLong() shl Int.SIZE_BITS) or (worldZ.toLong() and UNSIGNED_INT)
        val slot = ((worldX and SLOT_MASK) shl SLOT_BITS) or (worldZ and SLOT_MASK)
        if (slots.keys[slot] == key) slots.spans[slot]?.let { return it }
        val derived = derive(worldX, worldZ)
        slots.keys[slot] = key
        slots.spans[slot] = derived
        return derived
    }

    private companion object {
        const val SLOT_BITS = 5
        const val SLOTS = 1 shl (SLOT_BITS * 2)
        const val SLOT_MASK = (1 shl SLOT_BITS) - 1
        const val UNSIGNED_INT = 0xFFFF_FFFFL

        /** A packed position no world reaches, since the border stops well short of `Int.MIN_VALUE`. */
        const val EMPTY_KEY = Long.MIN_VALUE
    }
}
