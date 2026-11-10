package co.voik.agesandtheart.client

import co.voik.agesandtheart.BuildMatch
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.Release
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.Holder
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.packs.resources.ResourceManager
import java.io.File
import java.io.IOException

/**
 * For the beta: once per installation after an update, a line in chat saying which version this is, its "changelog"
 * opening, in one of the game's own dialogs, the notes of every release since the one last shown, newest first —
 * each written by hand (`assets/agesandtheart/changelog/<version>.txt`). A first installation is shown this release's
 * alone. A build made between releases speaks for the release it was built on, so a development client shows it too;
 * with no notes for it, it says nothing.
 */
object ChangelogNotice {

    /** Whether this run of the game has looked yet, so the file is read once, at the first world joined. */
    private var hasLooked = false

    fun tick(minecraft: Minecraft) {
        if (hasLooked) return
        val player = minecraft.player ?: return
        hasLooked = true
        val current = Release.of(BuildMatch.BUILD) ?: return
        val seenFile = File(minecraft.gameDirectory, SEEN_FILE)
        val lastSeen = lastSeen(seenFile)?.let(Release::of)
        if (lastSeen != null && lastSeen >= current) return
        val notes = notesBetween(minecraft.resourceManager, lastSeen, current)
        if (notes.none { it.release == current }) return
        ChangelogScreen.register()
        player.sendSystemMessage(notice(current, notes))
        remember(seenFile, current)
    }

    /** The notes of every release after [lastSeen] up to [current], newest first; only [current]'s where none was seen. */
    private fun notesBetween(resources: ResourceManager, lastSeen: Release?, current: Release): List<ReleaseNotes> {
        val written = resources.listResources(DIRECTORY) { it.namespace == Constants.MOD_ID && it.path.endsWith(EXTENSION) }
            .mapNotNull { (file, resource) ->
                val release = Release.of(file.path.removePrefix("$DIRECTORY/").removeSuffix(EXTENSION)) ?: return@mapNotNull null
                val text = try {
                    resource.openAsReader().use { it.readText().trim() }
                } catch (unreadable: IOException) {
                    Constants.LOG.warn("Could not read the notes in {}", file, unreadable)
                    return@mapNotNull null
                }
                ReleaseNotes(release, text).takeIf { text.isNotEmpty() }
            }
        fun isMissed(notes: ReleaseNotes): Boolean {
            val isNewerThanSeen = lastSeen == null || notes.release > lastSeen
            val isUpToCurrent = notes.release <= current
            val isShownAtAll = lastSeen != null || notes.release == current
            return isNewerThanSeen && isUpToCurrent && isShownAtAll
        }
        return written.filter(::isMissed).sortedByDescending { it.release }
    }

    private fun notice(current: Release, notes: List<ReleaseNotes>): Component {
        val link = Component.translatable("$KEY.link").withStyle { style ->
            style.withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(ClickEvent.ShowDialog(Holder.direct(ChangelogDialog(Component.translatable("$KEY.title", current.name), notes))))
                .withHoverEvent(HoverEvent.ShowText(Component.translatable("$KEY.hover")))
        }
        return Component.translatable(KEY, current.name, link)
    }

    private fun lastSeen(file: File): String? = try {
        file.takeIf(File::isFile)?.readText()?.trim()
    } catch (unreadable: IOException) {
        Constants.LOG.warn("Could not read {}", file, unreadable)
        null
    }

    private fun remember(file: File, release: Release) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(release.name)
        } catch (unwritable: IOException) {
            Constants.LOG.warn("Could not write {}", file, unwritable)
        }
    }

    private const val KEY = "chat.${Constants.MOD_ID}.changelog"
    private const val SEEN_FILE = "${Constants.MOD_ID}/changelog-seen.txt"
    private const val DIRECTORY = "changelog"
    private const val EXTENSION = ".txt"

}
