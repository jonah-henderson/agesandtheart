package co.voik.agesandtheart.client

import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos

/**
 * What is not drawn while somebody falls through a tear: everything under the lid.
 *
 * The veil covers every direction but the hole, so under the lid only what stands between the eye and the
 * opening can be seen — and that is exactly the cave ceiling that should not be. Two cuts remove it: every
 * section wholly under the lid is dropped from the frame (`SectionOcclusionGraphMixin`), and the section the
 * lid cuts through is rebuilt without the blocks under it (`SectionCompilerMixin`). The second is confined
 * to the opening's own columns, since the eye falls inside them and nothing outside them can be seen
 * through it.
 *
 * **Read on the section-building threads**, so the current cut is one volatile reference to an immutable
 * value. Vanilla's own section list and mesh builder only: Sodium replaces both, and under it the fall
 * shows what it did before (`notes/before-release.md`).
 */
object BelowTheLid {

    /** The lid's height and the opening's columns, while a fall is showing the veil. */
    data class Cut(val lidY: Int, val leastX: Int, val mostX: Int, val leastZ: Int, val mostZ: Int) {

        /** Whether a whole section, by its lowest block, lies under the lid. */
        fun dropsSection(sectionBottomY: Int): Boolean = sectionBottomY + SectionPos.SECTION_SIZE <= lidY

        /** Whether a block is left out of the section the lid cuts through. */
        fun hidesBlock(x: Int, y: Int, z: Int): Boolean {
            val isUnderTheLid = y < lidY
            val isUnderTheOpening = x in leastX..mostX && z in leastZ..mostZ
            return isUnderTheLid && isUnderTheOpening
        }
    }

    @Volatile
    var cut: Cut? = null
        private set

    /**
     * Takes up a new cut, rebuilding what the old and new ones touch.
     *
     * The sections the lid cuts through are rebuilt so their blocks are read again, and the section graph is
     * invalidated so the next frame's list is filtered again — it is otherwise rebuilt only as the camera
     * turns or crosses a section.
     */
    fun changeTo(next: Cut?, minecraft: Minecraft) {
        val previous = cut
        if (next == previous) return
        cut = next
        val level = minecraft.level ?: return
        for (touched in listOfNotNull(previous, next)) {
            val sectionY = SectionPos.blockToSectionCoord(touched.lidY - 1)
            level.setSectionRangeDirty(
                SectionPos.blockToSectionCoord(touched.leastX), sectionY, SectionPos.blockToSectionCoord(touched.leastZ),
                SectionPos.blockToSectionCoord(touched.mostX), sectionY, SectionPos.blockToSectionCoord(touched.mostZ),
            )
        }
        minecraft.levelRenderer.sectionOcclusionGraph().invalidate()
    }

    /** Leaving a server; nothing is rebuilt, since the level is going too. */
    fun forget() {
        cut = null
    }
}
