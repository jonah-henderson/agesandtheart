package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.Constants
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import net.minecraft.resources.Identifier

/** A concept, kind or structure word as a file names it: bare for ours (`colour`), namespaced for another pack's. */
object PackNames {
    val CODEC: Codec<Identifier> = Codec.STRING.comapFlatMap(::read, ::spell)

    fun read(spelled: String): DataResult<Identifier> {
        val namespaced = if (NAMESPACE_MARK in spelled) spelled else "${Constants.MOD_ID}$NAMESPACE_MARK$spelled"
        return Identifier.tryParse(namespaced)?.let { DataResult.success(it) } ?: DataResult.error { "'$spelled' is no name" }
    }

    fun spell(id: Identifier): String = if (id.namespace == Constants.MOD_ID) id.path else id.toString()

    private const val NAMESPACE_MARK = ':'
}
