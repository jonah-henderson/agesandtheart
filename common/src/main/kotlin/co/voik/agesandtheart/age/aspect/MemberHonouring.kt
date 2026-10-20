package co.voik.agesandtheart.age.aspect

import net.minecraft.core.HolderLookup

/**
 * Which members of a weighted set a parameter held member by member does anything to, answered by the code that
 * implements it: only villages have abandoned pieces, and only some features can be made bigger.
 */
fun interface MemberHonouring {
    fun honours(member: Taggable, registries: HolderLookup.Provider): Boolean

    companion object {
        val EVERY = MemberHonouring { _, _ -> true }
    }
}

/**
 * Whether this member does anything with [parameter]: a design of ours by its own parameters, a registry entry as
 * the parameter's code answers. Without [registries] an entry cannot be looked at, so it is taken to.
 */
fun Taggable.honoursDial(parameter: Parameter, registries: HolderLookup.Provider?): Boolean = when {
    this is AuthoredPreset -> honours(parameter)
    registries == null -> true
    else -> parameter.honouredBy.honours(this, registries)
}
