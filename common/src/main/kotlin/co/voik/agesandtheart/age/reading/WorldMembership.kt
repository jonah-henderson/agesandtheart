package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.resolution.Effect
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
 * a `#tag` with no namespace is one of the tag layer's, carried by a member. A tag of blocks asked for as a material
 * (`#red`) is in [MAKES_A_WORLD] and [MAKES_A_ROCK]: which of its blocks is drawn at resolution, among those the setting
 * may be made of.
 */
class WorldMembership(private val vocabulary: Vocabulary) : Membership {

    private val membersByKey: Map<String, Taggable> by lazy {
        Aspect.entries.flatMap(vocabulary::candidatesFor).associateBy { it.key }
    }

    private val membersOf = mutableMapOf<Aspect, Set<String>>()

    override fun isIn(value: String, keyOrTag: String): Boolean {
        if (!keyOrTag.startsWith(TAG_MARK)) return value == keyOrTag
        val tag = keyOrTag.drop(TAG_MARK.length)
        val isATagOfBlocks = value.startsWith(TAG_MARK)
        if (isATagOfBlocks) return tag in DRAWN_WITHIN && (Materials.isQuery(value) || everyBlockOf(value, answers = tag))
        MATERIAL_TAGS[tag]?.let { answers -> return answers(value) }
        val asABlockTag = Identifier.tryParse(tag)?.takeIf { NAMESPACE_MARK in tag }
        if (asABlockTag != null) return isInBlockTag(value, asABlockTag)
        val member = membersByKey[value] ?: return false
        return (vocabulary.tagsOf(member)[tag] ?: 0.0) > 0.0
    }

    /** One of the curated pool, or any entry of the part's registry, which a word may name outright. */
    override fun isMemberOf(part: Aspect, member: String): Boolean =
        member in membersOf.getOrPut(part) { vocabulary.candidatesFor(part).map { it.key }.toSet() } || vocabulary.isAnEntryOf(part, member)

    override fun clears(member: String, bars: Map<String, Double>): Boolean {
        val taggable = membersByKey[member] ?: return false
        return Bars.cleared(vocabulary.tagsOf(taggable), bars)
    }

    override fun isThereByDefault(part: Aspect, member: String): Boolean = vocabulary.isThereByDefault(part, member)

    override fun doesAnythingWith(part: Aspect, member: String, effect: Effect): Boolean {
        // One of the part's registry that no pool holds — a golem — is still that entry, and answers as one.
        val outsideThePools = member.takeIf { vocabulary.isAnEntryOf(part, it) }?.let(part::presetFor)
        val taggable = membersByKey[member] ?: outsideThePools ?: return true
        return when (effect) {
            is Effect.Abundance -> taggable !is Phenomenon || taggable.countsInSpace
            is Effect.Sets -> honoursItsOwn(part, taggable, effect.parameter.name)
            is Effect.Bends -> honoursItsOwn(part, taggable, effect.parameter.name)
            else -> true
        }
    }

    override fun aMemberClearingDoesAnythingWith(part: Aspect, bars: Map<String, Double>, effect: Effect): Boolean =
        groupAnswers.computeIfAbsent(Triple(part, bars, effect)) {
            val parameter = (effect as? Effect.Sets)?.parameter?.name ?: (effect as? Effect.Bends)?.parameter?.name
            val isHeldByThePartAsAWhole = parameter != null && !part.ownsMemberParameterNamed(parameter)
            val members = vocabulary.candidatesFor(part).filter { Bars.cleared(vocabulary.tagsOf(it), bars) }
            !isHeldByThePartAsAWhole && members.any { doesAnythingWith(part, it.key, effect) }
        }

    // Asked of every group for every offer that reaches it, and the answer is the vocabulary's alone.
    private val groupAnswers = java.util.concurrent.ConcurrentHashMap<Triple<Aspect, Map<String, Double>, Effect>, Boolean>()

    /** Whether [member] does anything with the parameter named [name], where [part] holds it member by member. */
    private fun honoursItsOwn(part: Aspect, member: Taggable, name: String): Boolean {
        val heldByEachMember = part.understood.firstOrNull { it.name == name && it.ownedByEachMember } ?: return true
        return vocabulary.honours(member, heldByEachMember)
    }

    /**
     * Whether every block of the block tag [value] is in the shape tag [answers]: a query's block is drawn later from those
     * that are, but a block tag's are all used — `#minecraft:beds` heaps every bed, and makes no world.
     */
    private fun everyBlockOf(value: String, answers: String): Boolean {
        val tag = Identifier.tryParse(value.drop(TAG_MARK.length)) ?: return false
        val shape = MATERIAL_TAGS[answers] ?: return false
        val blocks = BuiltInRegistries.BLOCK.getTagOrEmpty(TagKey.create(Registries.BLOCK, tag)).toList()
        return blocks.isNotEmpty() && blocks.all { shape(BuiltInRegistries.BLOCK.getKey(it.value()).toString()) }
    }

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

        /** The shape tags a tag of blocks passes, its block being drawn later among the setting's own materials. */
        private val DRAWN_WITHIN: Set<String> = setOf(MAKES_A_WORLD.toString(), MAKES_A_ROCK.toString())

        /** The tags a block is in by its shape, answered here rather than listed in a pack. */
        private val MATERIAL_TAGS: Map<String, (String) -> Boolean> = mapOf(
            MAKES_A_WORLD.toString() to Materials::makesAWorld,
            MAKES_A_ROCK.toString() to Materials::makesARock,
            MAKES_A_SEA.toString() to Materials::makesASea,
        )
    }
}
