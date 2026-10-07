package co.voik.agesandtheart.content

import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitDouble

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
    RED("red", 0xFF2030, 1),
    ORANGE("orange", 0xFF7A14, 2),
    YELLOW("yellow", 0xFFE01C, 3),
    GREEN("green", 0x2CE04E, 4),
    CYAN("cyan", 0x22D4FF, 5),
    BLUE("blue", 0x4A3CFF, 6),
    MAGENTA("magenta", 0xF52CE0, 7),
    WHITE("white", 0xF2F7FF, 8),
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
            val cell = mix64(mix64(mix64(seed) + x.toLong() / CELL) + z.toLong() / CELL)
            return entries[(unitDouble(cell) * entries.size).toInt()]
        }

        /** How wide a patch of one colour is, in blocks. */
        private const val CELL = 24L
    }
}
