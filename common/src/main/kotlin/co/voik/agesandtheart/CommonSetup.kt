package co.voik.agesandtheart

import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.advancement.Arrival
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.age.consequence.Worsening
import co.voik.agesandtheart.age.consequence.Wounds
import co.voik.agesandtheart.age.phenomena.AgeWeather
import co.voik.agesandtheart.age.phenomena.Happenings
import co.voik.agesandtheart.page.PageLearning
import co.voik.agesandtheart.book.panel.PanelViews
import co.voik.agesandtheart.book.panel.PanelWarming
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.ChargedMetal
import co.voik.agesandtheart.content.DeepWaterLogging
import co.voik.agesandtheart.content.PalmWood
import co.voik.agesandtheart.content.Plasma
import co.voik.agesandtheart.mixin.FireBlockInvoker
import co.voik.agesandtheart.content.ProtectiveSuit
import co.voik.agesandtheart.generation.Skies
import co.voik.agesandtheart.worldgen.fissure.RubbleArrivals
import co.voik.agesandtheart.worldgen.fissure.TheFall
import co.voik.ephemeris.LevelWeather
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * Shared initialisation, and what the server does on each event, invoked by each loader's entrypoint.
 * Common code sees only the vanilla codebase plus our own abstractions; how each loader subscribes to an
 * event stays in the loader entrypoints, which call the matching function here and do nothing else.
 */
object CommonSetup {
    fun init() {
        // An Age keeps weather of its own — the seam is the library's, what an Age asks for is ours.
        LevelWeather.source { level -> AgeWeather.of(level) }
        // And is dressed and aired as it opens, by whichever route opened it.
        Ages.attach()
        Skies.attach()
        // And an Age waiting to be warmed is taken off the queue when it is deleted.
        PanelWarming.attach()
        // Does nothing at all unless one of our own tools started this server — see [LauncherWatch].
        LauncherWatch.attach()
        Constants.LOG.info("Ages and the Art initialising")
    }

    /**
     * Shared work that cannot be done until every block, item and fluid of ours exists.
     *
     * **A second entry point because registration is where the loaders differ most.** Fabric registers
     * during its init call and NeoForge on a mod-bus event, so "after `init`" means nothing that both can
     * honour — each calls this at the point it knows its own content is in.
     */
    fun afterContentRegistered() {
        DeepWaterLogging.settleTheCache()
        // The palm burns as birch does. `FireBlock` keeps its table private, and this is the door into it.
        val fire = Blocks.FIRE as FireBlockInvoker
        for ((block, ignites, burns) in PalmWood.flammable) fire.`agesandtheart$setFlammable`(block, ignites, burns)
        // Contained plasma catches as TNT does, and burning lets it loose — see `ContainedPlasmaBlock`.
        fire.`agesandtheart$setFlammable`(Plasma.CONTAINED, CATCHES_AS_TNT_DOES, BURNS_AS_TNT_DOES)
        // A dead yema sapling burns as the dead bush it otherwise is.
        fire.`agesandtheart$setFlammable`(AgeContent.DEAD_PAPER_TREE_SAPLING_BLOCK, CATCHES_AS_A_DEAD_BUSH_DOES, BURNS_AS_A_DEAD_BUSH_DOES)
        // A spent fuel's empty unit, which the item builder could not name before the unit was registered.
        Plasma.leaveTheUnitBehind()
    }

    /** The end of every server tick. */
    fun serverTick(server: MinecraftServer) {
        // Whatever an Age does, which is more than whatever befalls it.
        AgeTick.tick(server)
        // And what a deretheni suit keeps off its wearer, which is the half of that no attribute can reach.
        ProtectiveSuit.tick(server)
        // And every charged machine anybody is standing near — every level, not only the Ages, since crystal
        // carried home through a book has to work where it is set down.
        ChargedMetal.stir(server)
        // And the linking panel's own beat: an open the pace was holding, and a lectern's panel its viewer has left.
        PanelViews.tick(server)
        // And whoever has fallen through one of an Age's tears and come out of the bottom of the field.
        TheFall.letGo(server)
        // And the rubble the tears have sent home, a little at a time.
        RubbleArrivals.letDown(server)
    }

    /**
     * The server having stopped. An integrated server stops while the game goes on and the next world reuses
     * Age ids, so what belongs to a server rather than to one of its levels is dropped here; what belongs to
     * a level is keyed weakly on it and goes with it.
     */
    fun serverStopped() {
        PanelWarming.serverStopped()
    }

    /**
     * A chunk loading on the server. The client's own chunks are `ClientSetup.chunkLoaded`'s.
     *
     * Where the wounds are: a wound carries no block entity, so the index is filled by reading each chunk as
     * it loads — see `Wounds`, which dismisses a section off its palette before touching a block. And which
     * of them owe the Age some tearing: a chunk that was away while the Age went on coming apart is brought
     * up to date on the tick, never here — see `Worsening.chunkArrived`.
     */
    fun chunkLoaded(level: ServerLevel, chunk: ChunkAccess) {
        Wounds.stocked(level, chunk)
        Worsening.chunkArrived(level, chunk.pos)
    }

    /** A chunk unloading on the server. */
    fun chunkUnloaded(level: ServerLevel, at: ChunkPos) {
        Wounds.emptied(level, at)
        Worsening.chunkLeft(level, at)
    }

    /**
     * A player joining the server.
     *
     * Nothing here about skies: telling a joining client what each level looks like is Ephemeris' own
     * bookkeeping, and it does it from its own entrypoint.
     */
    fun playerJoined(player: ServerPlayer) {
        PageLearning.tellEverything(player)
        // And a fall through a tear resumes, or ends where the tear no longer does.
        TheFall.resumed(player)
    }

    /** A player arriving in another level, however they came: a book, a portal, a fissure or a command. */
    fun playerChangedLevel(player: ServerPlayer, to: ServerLevel) {
        val arrival = Arrival.at(to) ?: return
        AgeTriggers.ENTERED_AGE.trigger(player, arrival)
        AgeSavedData.get(to.server).visit(to.dimension().identifier())
    }

    /** Datapack contents going to [player] — on joining, and to everyone after a `/reload`. */
    fun datapackSynced(player: ServerPlayer) {
        PageLearning.tellTheLexicon(player)
    }

    /**
     * A player leaving the server.
     *
     * Releases their linking panel's chunk ring: a client that crashes with a book open never sends the
     * close, so without this the ring — and the Age holding it — would stay loaded for the life of the server.
     */
    fun playerLeft(server: MinecraftServer, player: ServerPlayer) {
        PanelViews.forget(server, player)
    }

    /** Vanilla's own odds for TNT, which fire takes almost at once. */
    private const val CATCHES_AS_TNT_DOES = 15
    private const val BURNS_AS_TNT_DOES = 100

    /** Vanilla's own odds for a dead bush. */
    private const val CATCHES_AS_A_DEAD_BUSH_DOES = 60
    private const val BURNS_AS_A_DEAD_BUSH_DOES = 100
}
