package co.voik.agesandtheart

import net.minecraft.resources.Identifier

/**
 * @return [Identifier] from the String using the mod id specified in [Constants]
 */
fun String.location() = Identifier.fromNamespaceAndPath(Constants.MOD_ID, this)

/**
 * @return [Identifier] from the string using the passed namespace
 */
fun String.location(namespace: String) = Identifier.fromNamespaceAndPath(namespace, this)

/**
 * @return [Identifier] from the string using the vanilla namespace
 */
fun String.vanillaLocation() = Identifier.withDefaultNamespace(this)