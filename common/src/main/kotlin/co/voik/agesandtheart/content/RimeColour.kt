package co.voik.agesandtheart.content

/**
 * The eight colours a rime crystal grows in (design §7.1.2).
 *
 * **Eight because the games used eight across their remakes** (Jonah, 2026-09-07), and the order is the
 * spectrum with white on the end — which is what makes [signal] learnable. A player who notices that red
 * reads 1 and orange reads 2 has the whole table without being told it, because the rest is just the
 * rainbow.
 *
 * **Contiguous from one, which is vanilla's own scheme for this.** A jukebox maps each disc to a signal
 * the same way — `13` is 1, `cat` is 2, `blocks` is 3 — and a composter returns its level raw. There is no
 * spreading across the range and no gaps; the reader is a comparator, which does not attenuate.
 */
enum class RimeColour(val key: String, val tint: Int, val signal: Int) {
    RED("red", 0xE0575B, 1),
    ORANGE("orange", 0xE89B4B, 2),
    YELLOW("yellow", 0xE8D24B, 3),
    GREEN("green", 0x63C860, 4),
    CYAN("cyan", 0x7FC8F0, 5),
    INDIGO("indigo", 0x6A6ADF, 6),
    MAGENTA("magenta", 0xD164D8, 7),
    WHITE("white", 0xE8F0F5, 8),
    ;

    /** `cyan_rime_crystal`, on vanilla's own colour-first naming — `red_wool`, `blue_candle`. */
    val id: String get() = "${key}_rime_crystal"

    companion object {
        /**
         * Which colour grows around here.
         *
         * **A coarse cell rather than true cluster detection**, and worth being honest about: the feature
         * grows a crystal per column and never learns which columns are one face, so this hashes the
         * position down to a [CELL]-block grid instead. Neighbouring crystals on one outcrop agree; a long
         * range changes colour every so often, which reads as different outcrops rather than as confetti.
         *
         * Salted with the seed, so two Ages do not lay their colours in the same places.
         */
        fun around(seed: Long, x: Int, z: Int): RimeColour {
            var mixed = (x.toLong() / CELL) * PRIME_ONE + (z.toLong() / CELL) * PRIME_TWO + seed
            mixed = mixed xor (mixed ushr 33)
            mixed *= PRIME_THREE
            mixed = mixed xor (mixed ushr 29)
            return entries[((mixed ushr SPARE_BITS) % entries.size).toInt()]
        }

        /** How wide a patch of one colour is, in blocks. */
        private const val CELL = 24L

        private const val SPARE_BITS = 11
        private val PRIME_ONE = 0x9E3779B97F4A7C15uL.toLong()
        private val PRIME_TWO = 0xBF58476D1CE4E5B9uL.toLong()
        private val PRIME_THREE = 0xFF51AFD7ED558CCDuL.toLong()
    }
}
