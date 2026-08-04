package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * What the ground wears — the skin over whatever the rock is made of (design §3.1, vanilla's
 * `surface_rule`).
 *
 * **An aspect with no presets and one knob**, because there is nothing to choose between: a surface is a
 * material, or it is whatever the biome would have laid. That is why naming one *replaces* the skin rather
 * than switching it off, which is the whole of what this aspect adds — a granite body under a blackstone
 * skin was unsayable while `bare` lived on [Biomes] as a two-state dial.
 *
 * Three answers, and the middle one is the default:
 *
 * - **Nothing said** — vanilla's own tree, biome for biome, over our terrain.
 * - **A block** — that block laid on the top of the ground, several of them mingled.
 * - **Air** — no skin at all: the fill is the surface, and what [TerrainFill] laid is what you stand on.
 */
object Surface {

    /**
     * The block the ground is dressed in. Open, so §8's derived vocabulary reaches it with no work: a
     * writer aims a block word at the surface section and that is the whole mechanism.
     */
    val MATERIAL = Parameter.material("material")

    /**
     * The rule this Age's ground wears, over the [terrain] that has to say which blocks are the top of the
     * ground and which are the floor of a cave.
     *
     * A name this pack does not have leaves the biome's own skin rather than stripping it: an Age must
     * still open, and a missing block is a pack problem rather than an instruction to bare the world.
     */
    fun ruleFor(options: Options, terrain: TerrainField): SurfaceRules.RuleSource {
        val blocks = options.materialsOf(MATERIAL)
        if (blocks.isEmpty()) return SurfacingStrategy.delegatedToBiomes(terrain)
        // Air is how a writer says "no skin", the same way `open` says "no sea" — and it is only bare when
        // *everything* named is air, since air mingled with a rock is a skin full of holes and a fine thing
        // for a book to ask for.
        if (blocks.all { it.isAir }) return SurfacingStrategy.SUPPRESSED
        return SurfacingStrategy.laidOn(terrain, blocks)
    }
}
