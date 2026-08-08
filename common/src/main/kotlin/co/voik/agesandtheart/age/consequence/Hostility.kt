package co.voik.agesandtheart.age.consequence

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.level.NaturalSpawner
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.AABB
import kotlin.math.sqrt

/**
 * What being near a wound does to what lives there (design §5.1).
 *
 * The corruption is the half you can see; this is the half that can kill you, and they are deliberately the
 * same gradient — the air going wrong and the place growing dangerous are one signal read twice, so what a
 * player learns to fear is *legible* rather than an ambush. Dread with a direction (§5.1) is only worth
 * having if the direction is worth heeding.
 *
 * **Two registers, because "harder" means two things.** A wound makes the things that come nastier
 * ([localDifficultyAt]) and makes more of them come ([draw]). Either alone reads wrong: only-nastier is an
 * Age that is merely unfair, and only-more is a nuisance you outrun.
 *
 * **Not a manifestation of its own.** §5.1 frames hostility as *what a wound is* rather than as something
 * separately bought, so it rides the same purchase — an Age that spent more of its budget on wounds has
 * more dangerous places in it, and the budget stays one dial rather than two that can disagree.
 */
object Hostility {

    /**
     * The difficulty a wound makes of [at], or null where no wound reaches it.
     *
     * **Inhabited time is the lever, and it is vanilla's own.** `DifficultyInstance` is computed from four
     * things — the difficulty setting, the clock, the moon and how long the chunk has been lived in — and
     * the first three belong to the world rather than to the place. The fourth is exactly the idea we want:
     * the game already says *a place people have stayed in grows dangerous*, and a wound is a place
     * something has been coming through for a very long time.
     *
     * So a wound does not invent a difficulty; it ages the ground. Everything that reads local difficulty
     * comes along for free and stays in proportion — mobs arrive armed and enchanted, zombies call
     * reinforcements, husks and drowned convert what they catch — and none of it needed a hook of its own.
     *
     * **A floor, never a setting** ([kotlin.math.max]), the same rule as an Age's weather: a chunk somebody
     * has genuinely lived in for fifty hours is not made safer by a tear opening in it.
     */
    @JvmStatic
    fun localDifficultyAt(level: ServerLevel, at: BlockPos, was: DifficultyInstance): DifficultyInstance? {
        val how = Wounds.corruptionAt(level, at.center)
        if (how <= Wounds.NONE) return null
        val livedIn = level.chunkHolding(at)?.inhabitedTime ?: 0L
        val asIf = asIfLivedIn(how, livedIn)
        return DifficultyInstance(was.difficulty, level.overworldClockTime, asIf, level.getMoonBrightness(at))
    }

    /**
     * How long a place [how] corrupted should read as having been lived in, given it really has for
     * [alreadyHas] — the whole of the arithmetic, kept pure so the ladder can be checked without a world.
     */
    fun asIfLivedIn(how: Double, alreadyHas: Long): Long =
        maxOf(alreadyHas, (how * FULLY_LIVED_IN).toLong())

    /**
     * Draws whatever wants to come through the open wounds [level] is holding.
     *
     * **Anchored at the wound rather than at the player**, which is the whole of why this is a gradient and
     * not a difficulty setting: things come *out of* the tear and have to cross the ground between, so the
     * corruption tells you which way the danger is and it is telling the truth. Spawning around the player
     * would be the same number of monsters saying nothing about where to go.
     *
     * **Vanilla's own spawner does the deciding.** `spawnCategoryForPosition` reads the biome's spawn list,
     * checks the light and the space, and refuses what will not fit — so a wound in a mushroom field draws
     * what a mushroom field has and a wound at noon on open grass draws nothing at all. What is ours is
     * *how often it is asked*, which is the only knob a gradient needs.
     */
    fun draw(level: ServerLevel, at: BlockPos, how: Double) {
        if (level.random.nextDouble() >= how * DRAWS_AT_THE_THROAT) return
        NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, at)
    }

    /**
     * Every open wound near enough to somebody to be worth drawing from, this tick.
     *
     * **Nobody in reach means nothing happens**, which is not an optimisation but the rule: mobs spawned
     * into an empty Age would be despawned unseen and would spend the whole tick budget of every wound ever
     * written doing it.
     */
    fun stir(level: ServerLevel) {
        if (level.gameTime % BETWEEN_DRAWS != 0L) return
        for (player in level.players()) {
            if (player.isSpectator) continue
            val eye = player.position()
            // **The nearest few only.** An Age at the top of the register can put a hundred wounds within
            // reach of one player, and stirring every one of them would be hundreds of monsters a second
            // rather than a dangerous place. Nearest-first, so what answers is what the player walked up to,
            // and a few more are considered than are drawn from so a stocked one does not block the rest.
            var drawn = 0
            for (wound in Wounds.openNear(level, eye).take(CONSIDERED)) {
                if (drawn >= STIRRED_AT_ONCE) break
                if (alreadyAbout(level, wound) >= KEEPS_ABOUT) continue
                drawn++
                // How near the *player* is to this wound, not how corrupted the wound is — which is always
                // total. A far wound stirs slowly and a near one hard, which is what "intensifying with
                // proximity" (§5.1) has to mean once wounds are point sources.
                draw(level, wound, Wounds.corruptionAtRange(sqrt(eye.distanceToSqr(wound.center))))
            }
        }
    }

    /**
     * How many monsters are already about **this wound**, so it keeps its own ground stocked rather than
     * pumping.
     *
     * `spawnCategoryForPosition` is the "try to spawn here" call and consults no mob cap at all — which is
     * what makes it usable as a dial, and what makes a ceiling ours to impose.
     *
     * **Asked around the wound rather than around the player, which is the whole of why nothing spawned**
     * (Jonah, 2026-08-08, walked). A box around a *player* counts every monster the Age made on its own, and
     * a dark world at midnight is past a dozen of them before a single wound has done anything — so the
     * ceiling silenced the register it was meant to bound. Around the wound it means what it was supposed
     * to: this tear has put enough through for now, ask again later.
     */
    private fun alreadyAbout(level: ServerLevel, wound: BlockPos): Int =
        level.getEntitiesOfClass(Monster::class.java, AABB.ofSize(wound.center, ABOUT, ABOUT, ABOUT)).size

    /** The chunk [at] is in if it is already loaded — never one that has to be generated to answer. */
    private fun ServerLevel.chunkHolding(at: BlockPos): ChunkAccess? = getChunk(
        SectionPos.blockToSectionCoord(at.x),
        SectionPos.blockToSectionCoord(at.z),
        ChunkStatus.FULL,
        false,
    )

    /**
     * The inhabited time at which vanilla's own difficulty curve saturates, in ticks — fifty hours.
     *
     * Read off `DifficultyInstance` rather than chosen: past this the game stops counting, so it is the
     * most a wound could ask for and asking for more would say nothing.
     */
    private const val FULLY_LIVED_IN = 3_600_000.0

    /** How often a wound is asked whether anything is coming through, in ticks. */
    private const val BETWEEN_DRAWS = 20L

    /** The chance of one draw at the very throat of a wound, where the corruption is total. */
    private const val DRAWS_AT_THE_THROAT = 0.5

    /** How many of the wounds in reach of one person answer at a time, nearest first. */
    private const val STIRRED_AT_ONCE = 4

    /** How many are looked at to find those, so a stocked wound does not shut out the ones behind it. */
    private const val CONSIDERED = 8

    /** How many monsters about **one wound** is enough, past which that wound goes quiet. */
    private const val KEEPS_ABOUT = 4

    /** The ground a single wound is answerable for, as a box around it. */
    private const val ABOUT = 24.0
}
