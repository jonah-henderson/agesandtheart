package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.worldgen.AgeRock
import co.voik.agesandtheart.worldgen.field.SurfacingStrategy
import co.voik.agesandtheart.worldgen.field.TerrainFill
import net.minecraft.world.level.biome.Biome
import net.minecraft.core.HolderGetter
import net.minecraft.resources.Identifier
import net.minecraft.world.level.levelgen.material.MaterialRules
import net.minecraft.world.level.levelgen.material.rule.MaterialRule

/**
 * What the ground wears — the skin over whatever the rock is made of (design §3.1, vanilla's
 * `surface_rule`).
 *
 * **An aspect with no presets and one parameter**, because there is nothing to choose between: a surface is a
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
    val MATERIAL = Parameter.material("material", help = "The block the ground is dressed in: its top layer.")

    /**
     * The rule this Age's ground wears, over the rock that has to say which blocks are the top of the
     * ground and which are the floor of a cave.
     *
     * **The whole rock rather than one field out of it**, so a dressing can never be hung from a ceiling.
     * A sealed Age's [AgeRock.Ours.field] reaches the top of the world, and [SurfacingStrategy]'s gate is
     * built by measuring the field it is given — so handing it that one put every surface rule in the top
     * eight blocks of the sky and left the ground in undressed fill (Jonah, 2026-08-15, walked: a warped
     * forest of bare netherrack, and no vegetation, nylium being what it grows on).
     * [AgeRock.Ours.landform] is the reader that means ground; taking the rock is what stops the other one
     * being reachable from here at all.
     *
     * A name this pack does not have leaves the biome's own skin rather than stripping it: an Age must
     * still open, and a missing block is a pack problem rather than an instruction to bare the world.
     */
    fun ruleFor(
        options: Options,
        rock: AgeRock.Ours,
        template: AgeTemplate,
        rules: HolderGetter<MaterialRule>,
        biomes: HolderGetter<Biome>? = null,
        /** The sea's surface, or null where there is none — our biomes' skins are laid against it. */
        waterline: Int? = null,
        /** The biomes the book asked for, which says which of ours this Age has a skin to lay for. */
        grown: Set<Identifier> = emptySet(),
    ): MaterialRule {
        // What the world paints deep in its rock goes on whatever the skin is: a book naming its ground's
        // top layer has said nothing about a sulfur cave's walls.
        val beneath = biomes?.let { template.beneathTheSkin(rules, it) }
        val blocks = options.materialsOf(MATERIAL)
        if (blocks.isEmpty()) {
            // Our biomes before the template's, whose tree does not know them. Only with a sea, since the one
            // that has a skin of its own is a coast.
            val ours = if (biomes != null && waterline != null) SurfacingStrategy.ofOurBiomes(biomes, waterline, grown) else null
            val skin = ours?.let { MaterialRules.sequence(it, template.skin(rules)) } ?: template.skin(rules)
            return SurfacingStrategy.delegatedToBiomes(rock.landform, skin, beneath)
        }
        // Air is how a writer says "no skin", the same way `open` says "no sea" — and it is only bare when
        // *everything* named is air, since air mingled with a rock is a skin full of holes and a fine thing
        // for a book to ask for.
        if (blocks.all { it.isAir }) return SurfacingStrategy.NO_SKIN
        return SurfacingStrategy.laidOn(rock.landform, blocks, beneath)
    }
}
