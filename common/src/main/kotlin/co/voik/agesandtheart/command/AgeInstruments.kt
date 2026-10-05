package co.voik.agesandtheart.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack

/**
 * The instruments hung off `/age` in a development environment — the subcommands that measure or force
 * something, rather than the ones that write an Age.
 *
 * **One file per family, and this is only the list of them.** All twenty-six used to live in a single
 * object of some two and a half thousand lines, sharing one block of seventy-odd constants: the rainbow's
 * readout sat between consequence arithmetic and chunk diffing, and most of the package cycles in the mod
 * ran through that one file. Each family now owns its own constants and its own helpers, and what they
 * genuinely share — the argument names and result codes in `CommandArguments.kt`, the units and readouts
 * in `Measurements.kt` — is named rather than reached for through a command object.
 *
 * **The order here is the families' first appearance in the old chain**, and within each family the
 * subcommands keep the order they had. It cannot be exactly the old order, because that chain interleaved
 * families; Brigadier's child order decides only how suggestions are listed, so nothing about parsing or
 * what a command does depends on it.
 *
 * @see AgeCommand, which adds these only where `Services.PLATFORM.isDevelopment`.
 */
object AgeInstruments {

    fun addTo(age: LiteralArgumentBuilder<CommandSourceStack>) {
        CorpusInstruments.addTo(age)
        SkyInstruments.addTo(age)
        TerrainInstruments.addTo(age)
        PhenomenonInstruments.addTo(age)
        ConsequenceInstruments.addTo(age)
        PlayerInstruments.addTo(age)
        RewardInstruments.addTo(age)
        ReleaseInstruments.addTo(age)
        StructureInstruments.addTo(age)
    }
}
