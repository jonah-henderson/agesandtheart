package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.location
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey

/**
 * What the reader asks of the world, answered by [vocabulary]: the members each part has and what they are like, and
 * what a block is in. A block's [MATERIAL_TAGS] are worked out from its shape, so every block in the game answers them,
 * a mod's as much as vanilla's; any other namespaced `#tag` is the game's own block tag, which only binds on a server;
 * a `#tag` with no namespace is one of the tag layer's, carried by a member.
 */
class WorldMembership(private val vocabulary: Vocabulary) : Membership {

    private val membersByKey: Map<String, Taggable> by lazy {
        Aspect.entries.flatMap(vocabulary::candidatesFor).associateBy { it.key }
    }

    private val membersOf = mutableMapOf<Aspect, Set<String>>()

    override fun isIn(value: String, keyOrTag: String): Boolean {
        if (!keyOrTag.startsWith(TAG_MARK)) return value == keyOrTag
        val tag = keyOrTag.drop(TAG_MARK.length)
        MATERIAL_TAGS[tag]?.let { answers -> return answers(value) }
        val asABlockTag = Identifier.tryParse(tag)?.takeIf { NAMESPACE_MARK in tag }
        if (asABlockTag != null) return isInBlockTag(value, asABlockTag)
        val member = membersByKey[value] ?: return false
        return (vocabulary.tagsOf(member)[tag] ?: 0.0) > 0.0
    }

    override fun isMemberOf(part: Aspect, member: String): Boolean =
        member in membersOf.getOrPut(part) { vocabulary.candidatesFor(part).map { it.key }.toSet() }

    override fun clears(member: String, bars: Map<String, Double>): Boolean {
        val taggable = membersByKey[member] ?: return false
        return Bars.cleared(vocabulary.tagsOf(taggable), bars)
    }

    override fun isThereByDefault(part: Aspect, member: String): Boolean = vocabulary.isThereByDefault(part, member)

    private fun isInBlockTag(value: String, tag: Identifier): Boolean {
        val id = Identifier.tryParse(value) ?: return false
        val block = BuiltInRegistries.BLOCK.get(id).orElse(null) ?: return false
        return block.`is`(TagKey.create(Registries.BLOCK, tag))
    }

    companion object {
        private const val TAG_MARK = "#"
        private const val NAMESPACE_MARK = ':'

        /** A block a world can stand on: a whole cube that stays put ([Materials.makesAWorld]) — the ground, a structure, a tree's leaves. */
        val MAKES_A_WORLD: Identifier = "makes_a_world".location()

        /** A world that does not fall, which a rock may be made of ([Materials.makesARock]). */
        val MAKES_A_ROCK: Identifier = "makes_a_rock".location()

        /** What a sea may be made of ([Materials.makesASea]). */
        val MAKES_A_SEA: Identifier = "makes_a_sea".location()

        /** The tags a block is in by its shape, answered here rather than listed in a pack. */
        private val MATERIAL_TAGS: Map<String, (String) -> Boolean> = mapOf(
            MAKES_A_WORLD.toString() to Materials::makesAWorld,
            MAKES_A_ROCK.toString() to Materials::makesARock,
            MAKES_A_SEA.toString() to Materials::makesASea,
        )
    }
}
