package co.voik.agesandtheart.math

/**
 * [value] mixed until adjacent inputs give unrelated outputs — SplitMix64's finalizer.
 *
 * **Handing a seed straight to `Random` is not enough**, and this is the second time that has bitten.
 * Two Ages written a seed apart differ in a handful of low bits; xoring in a word's name and a
 * parameter's shifts those bits but does not spread them, and `nextInt(3)` over such seeds came back
 * with the same answer every time — fourteen scorching Ages, fourteen embers. An avalanche step makes
 * one bit of input change half the output, which is the property a draw needed all along.
 *
 * Several values are hashed together by mixing each into the last: `mix64(mix64(first) + second)`.
 */
fun mix64(value: Long): Long {
    var mixed = value + GOLDEN
    mixed = (mixed xor (mixed ushr 30)) * FIRST_MIX
    mixed = (mixed xor (mixed ushr 27)) * SECOND_MIX
    return mixed xor (mixed ushr 31)
}

/** [bits] as a number in `0..<1`, from its top 53 bits, which is as many as a `Double` holds exactly. */
fun unitDouble(bits: Long): Double = (bits ushr DOUBLE_SPARE_BITS).toDouble() / DOUBLE_STEPS

/** [bits] as a number in `0..<1`, from its top 24 bits, which is as many as a `Float` holds exactly. */
fun unitFloat(bits: Long): Float = (bits ushr FLOAT_SPARE_BITS).toFloat() / FLOAT_STEPS

// SplitMix64's finalizer, unchanged: the odd increment walks the whole 64-bit space and the two
// multipliers are what spread one changed bit across all of them.
private const val GOLDEN = -0x61c8864680b583ebL
private const val FIRST_MIX = -0x40a7b892e31b1a47L
private const val SECOND_MIX = -0x6b2fb644ecceee15L

private const val DOUBLE_SPARE_BITS = 11
private const val DOUBLE_STEPS = (1L shl 53).toDouble()
private const val FLOAT_SPARE_BITS = 40
private const val FLOAT_STEPS = (1 shl 24).toFloat()
