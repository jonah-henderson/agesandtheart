package co.voik.agesandtheart.worldgen.field

/**
 * What a field answers for the columns of a chunk and the ring around it, remembered per thread — usually their
 * spans.
 *
 * A 32×32 block square of slots indexed straight off the low bits of the block coordinates, so a chunk and
 * the neighbours its biome probes and carvers reach into all sit in distinct slots. Thread-confined, because
 * a field is shared across every chunk worker. [derive] must answer a column the same way every time.
 */
internal class ColumnMemo<T : Any>(private val derive: (Int, Int) -> T) {
    private class Slots {
        val keys = LongArray(SLOTS) { EMPTY_KEY }
        val values = arrayOfNulls<Any>(SLOTS)
    }

    private val remembered = ThreadLocal.withInitial { Slots() }

    fun at(worldX: Int, worldZ: Int): T {
        val slots = remembered.get()
        val key = (worldX.toLong() shl Int.SIZE_BITS) or (worldZ.toLong() and UNSIGNED_INT)
        val slot = ((worldX and SLOT_MASK) shl SLOT_BITS) or (worldZ and SLOT_MASK)
        if (slots.keys[slot] == key) {
            // Only [derive]'s answers are ever stored in a slot.
            @Suppress("UNCHECKED_CAST")
            (slots.values[slot] as T?)?.let { return it }
        }
        val derived = derive(worldX, worldZ)
        slots.keys[slot] = key
        slots.values[slot] = derived
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
