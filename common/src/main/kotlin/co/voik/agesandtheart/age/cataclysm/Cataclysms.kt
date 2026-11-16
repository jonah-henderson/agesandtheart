package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Spending
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.platform.Services
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.util.Collections
import java.util.IdentityHashMap

/**
 * How an Age ends (design §5.3 "The cataclysm"): a clock, a warning, a strike, an aftermath and a dead link.
 *
 * An end comes one of three ways, and nothing else learns which: **forced** by hand (`/age cataclysm`), **written**
 * by a word naming its phenomenon, or **inflicted** where the Age's instability meets its kind's
 * [CataclysmRule.inflictedWhen]. A written or inflicted end is derived, never stored: its approach begins when the
 * Age was written and lasts its rule's timeline, so whether an Age has ended is a function of its recipe.
 */
object Cataclysms {

    /** Why an end is coming: its [kind], and whether a word asked for it rather than the Age's instability. */
    data class Cause(val kind: CataclysmKind, val isWritten: Boolean)

    /** The end coming to the Age [recipe] describes, or null where none is. */
    fun of(server: MinecraftServer, recipe: AgeRecipe): Doom? {
        recipe.forcedCataclysm?.let { return it }
        val cause = causeOf(server, recipe) ?: return null
        val rule = CataclysmRule.list(server).getValue(cause.kind)
        return Doom(cause.kind, approachBeginsFor(server, recipe), rule.timeline, Doom.bearingFor(recipe.seed))
    }

    /** Whether the Age [recipe] describes has ended by [time], so that every link into it fails. */
    fun hasEnded(server: MinecraftServer, recipe: AgeRecipe, time: Long): Boolean = of(server, recipe)?.hasStruckBy(time) == true

    /** Why an end is coming to the Age [recipe] describes, leaving aside one forced by hand; null where none is. */
    fun causeOf(server: MinecraftServer, recipe: AgeRecipe): Cause? {
        val remembered = causes.of(server)
        synchronized(remembered) {
            if (remembered.containsKey(recipe)) return remembered[recipe]
        }
        val cause = writtenIn(recipe) ?: inflictedOn(server, recipe)
        synchronized(remembered) {
            if (remembered.size > MOST_REMEMBERED) remembered.clear()
            remembered[recipe] = cause
        }
        return cause
    }

    private fun writtenIn(recipe: AgeRecipe): Cause? {
        val composition = recipe.composition ?: return null
        val written = Happenings.claimsIn(composition).map { it.value }.toSet()
        return CataclysmKind.entries.firstOrNull { it.writtenAs.key in written }?.let { Cause(it, isWritten = true) }
    }

    /** The kind the Age's instability calls down, drawn from its seed where several would. */
    private fun inflictedOn(server: MinecraftServer, recipe: AgeRecipe): Cause? {
        if (recipe.generatorVersion < FIRST_GENERATOR_INFLICTING_ENDS) return null
        val age = Unravelling(recipe.instability, Spending.of(server, recipe), recipe.seed)
        val calling = CataclysmRule.list(server).filter { (kind, rule) -> rule.inflictedWhen?.holdsFor(age, kind) == true }.keys
        if (calling.isEmpty()) return null
        val drawn = calling.sortedBy { it.ordinal }[XoroshiroRandomSource(recipe.seed xor KIND_SALT).nextInt(calling.size)]
        return Cause(drawn, isWritten = false)
    }

    /** When the approach begins: at the bind, and for a preview not yet bound, now, so it shows the first instant. */
    private fun approachBeginsFor(server: MinecraftServer, recipe: AgeRecipe): Long =
        if (recipe.writtenAt == AgeRecipe.NOT_YET_WRITTEN) server.overworld().gameTime else recipe.writtenAt

    /** One tick of whatever end is coming to [level], which somebody is standing in. */
    fun befall(level: ServerLevel, recipe: AgeRecipe) {
        val doom = of(level.server, recipe)
        if (level.server.tickCount % TELLING_THE_CLIENTS == 0) tellTheClients(level, doom)
        if (doom == null) {
            Scorch.forget(level)
            return
        }
        when (doom.kind) {
            CataclysmKind.ASTEROID -> Asteroid.befall(level, doom)
        }
    }

    /** Tells everyone in [level] what end is coming, now rather than on the next beat. */
    fun tellTheClients(level: ServerLevel, doom: Doom?) {
        val telling = CataclysmPayload(level.dimension().identifier(), doom)
        for (player in level.players()) Services.NETWORK.sendToPlayer(player, telling)
    }

    /**
     * Each recipe's cause, by identity, per datapack load: the end is asked after every tick and by every creature
     * made, and a recipe retold is a new one.
     */
    private val causes = PerReload { Collections.synchronizedMap(IdentityHashMap<AgeRecipe, Cause?>()) }

    /** Ages written before this are never struck by their own instability, so a collapse already under way is not ended at once. */
    private const val FIRST_GENERATOR_INFLICTING_ENDS = 29

    private const val MOST_REMEMBERED = 1024
    private const val TELLING_THE_CLIENTS = 20
    private const val KIND_SALT = 0x2B8F_47C3_D1E0_96A5L
}
