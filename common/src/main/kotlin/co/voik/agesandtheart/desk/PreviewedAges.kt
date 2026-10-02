package co.voik.agesandtheart.desk

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.WarmAgesWhen
import co.voik.agesandtheart.age.AgeSavedData
import co.voik.agesandtheart.book.DescriptiveBookRecipe
import co.voik.agesandtheart.book.panel.PanelWarming
import co.voik.agesandtheart.generation.Ages
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.UUID

/**
 * The Age each writer's crystal viewer shows: a real Age, built through [DescriptiveBookRecipe] from the
 * words and the writer's seed, which the book binding that sentence takes over ([claim]).
 *
 * One per writer. A different sentence or seed deletes the old one, as does binding anything else. Its id
 * is numbered until [claim] renames it after the book's title. Server thread only, as [Ages] is.
 */
object PreviewedAges {

    /**
     * The Age [words] make at [writer]'s seed, made now where this sentence at this seed has none — or null
     * where it would not open.
     */
    fun ageFor(writer: ServerPlayer, words: List<Identifier>): ServerLevel? {
        val server = writer.level().server
        val previews = Previews.of(server)
        val seed = writer.writingSeed
        val held = previews.of(writer.uuid)
        if (held != null && held.isOf(words, seed) && exists(server, held.age)) return Ages.open(server, held.age)
        held?.let { discard(server, previews, writer.uuid, it) }

        val id = Ages.allocateId(server)
        val recipe = DescriptiveBookRecipe.of(words, seed, authored = true, server = server, ageId = id)
        val level = Ages.create(server, id, recipe) ?: return null
        previews.keep(writer.uuid, Preview(id, words, seed))
        Constants.LOG.info("{} is previewing {} at a crystal viewer", writer.gameProfile.name, id)
        return level
    }

    /** The Age [writer]'s viewer last made, opened — or null where they have none standing. */
    fun showing(writer: ServerPlayer): ServerLevel? {
        val server = writer.level().server
        val held = Previews.of(server).of(writer.uuid) ?: return null
        return if (exists(server, held.age)) Ages.open(server, held.age) else null
    }

    /**
     * Makes and warms the Age for [writer]'s sentence as they leave [desk], where a crystal viewer stands in
     * its room and warming is not turned off.
     */
    fun whenTheDeskCloses(writer: ServerPlayer, desk: WritersDeskBlockEntity) {
        if (AgeConfig.warmAgesWhen.get() == WarmAgesWhen.OPENED) return
        val level = writer.level()
        // A menu closes as its player leaves and as the server stops, and neither is a time to make a world.
        if (writer.hasDisconnected() || !level.server.isRunning) return
        if (!CrystalViewerBlock.standsNear(level, desk.blockPos, WritersDesk.of(level.server).radius)) return
        val previewable = Previewable.of(writer, DeskTemplates.read(writer, desk.templateFor(writer.uuid)))
        if (previewable.finding != ViewerFinding.PREVIEWING) return
        val age = ageFor(writer, previewable.words) ?: return
        PanelWarming.warmLevel(level.server, age)
    }

    /**
     * At the bind: the previewed Age's id after [title], where it is the Age of exactly [words] at [seed] —
     * and otherwise null, with any preview the writer had deleted. An Age whose ring is still generating is
     * renamed once it finishes, since renaming closes the level; its new id is held for it until then.
     */
    fun claim(writer: ServerPlayer, words: List<Identifier>, seed: Long, title: String): Identifier? {
        val server = writer.level().server
        val previews = Previews.of(server)
        val held = previews.of(writer.uuid) ?: return null
        if (held.isOf(words, seed) && exists(server, held.age)) {
            previews.forget(writer.uuid)
            Constants.LOG.info("{} bound the Age they previewed, {}", writer.gameProfile.name, held.age)
            return named(server, held.age, title)
        }
        discard(server, previews, writer.uuid, held)
        return null
    }

    /** The id [age] goes by after [title]: renamed now, or held for it until its warming finishes. */
    private fun named(server: MinecraftServer, age: Identifier, title: String): Identifier {
        val called = Ages.idCalled(server, title) ?: return age
        val open = server.getLevel(ResourceKey.create(Registries.DIMENSION, age))
        if (open != null && PanelWarming.isUnderway(open)) {
            Ages.renameLater(server, age, called)
            return called
        }
        return if (Ages.rename(server, age, called)) called else age
    }

    private fun discard(server: MinecraftServer, previews: Previews, writer: UUID, held: Preview) {
        previews.forget(writer)
        if (Ages.delete(server, held.age)) Constants.LOG.info("Deleted the preview {}, which nobody bound", held.age)
    }

    private fun exists(server: MinecraftServer, id: Identifier) = id in AgeSavedData.get(server).ages
}

/** One writer's previewed Age, and the sentence and seed it was made from. */
private data class Preview(val age: Identifier, val words: List<Identifier>, val seed: Long) {

    fun isOf(words: List<Identifier>, seed: Long) = this.words == words && this.seed == seed
}

/** One row of the save: a writer and their preview. */
private data class PreviewRow(val writer: UUID, val preview: Preview) {
    companion object {
        val LIST_CODEC: Codec<List<PreviewRow>> = RecordCodecBuilder.create { instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("writer").forGetter(PreviewRow::writer),
                Identifier.CODEC.fieldOf("age").forGetter { row: PreviewRow -> row.preview.age },
                Identifier.CODEC.listOf().fieldOf("words").forGetter { row: PreviewRow -> row.preview.words },
                Codec.LONG.fieldOf("seed").forGetter { row: PreviewRow -> row.preview.seed },
            ).apply(instance) { writer, age, words, seed -> PreviewRow(writer, Preview(age, words, seed)) }
        }.listOf()
    }
}

/** [PreviewedAges]' record, under the overworld's data storage beside the Ages themselves. */
private class Previews private constructor(rows: List<PreviewRow>) : SavedData() {

    constructor() : this(emptyList())

    private val byWriter: MutableMap<UUID, Preview> = rows.associateTo(linkedMapOf()) { it.writer to it.preview }

    fun of(writer: UUID): Preview? = byWriter[writer]

    fun keep(writer: UUID, preview: Preview) {
        byWriter[writer] = preview
        setDirty()
    }

    fun forget(writer: UUID) {
        if (byWriter.remove(writer) != null) setDirty()
    }

    companion object {
        private const val NAME = "agesandtheart_previewed_ages"

        private val CODEC: Codec<Previews> = PreviewRow.LIST_CODEC.fieldOf("previews").codec().xmap(
            ::Previews,
        ) { saved -> saved.byWriter.map { (writer, preview) -> PreviewRow(writer, preview) } }

        /** [DataFixTypes.LEVEL] for the reason `AgeSavedData` names it: the record demands one. */
        private val TYPE: SavedDataType<Previews> =
            SavedDataType(Identifier.withDefaultNamespace(NAME), ::Previews, CODEC, DataFixTypes.LEVEL)

        fun of(server: MinecraftServer): Previews = server.overworld().dataStorage.computeIfAbsent(TYPE)
    }
}
