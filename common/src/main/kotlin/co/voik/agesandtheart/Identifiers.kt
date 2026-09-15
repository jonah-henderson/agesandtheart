package co.voik.agesandtheart

import net.minecraft.resources.Identifier

/**
 * @return [Identifier] from the String using the mod id specified in [Constants]
 */
fun String.location() = Identifier.fromNamespaceAndPath(Constants.MOD_ID, this)
