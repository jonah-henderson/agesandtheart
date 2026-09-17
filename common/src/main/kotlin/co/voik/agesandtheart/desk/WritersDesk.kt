package co.voik.agesandtheart.desk

import co.voik.agesandtheart.datapack.ResourceParsing
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.tags.TagKey
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/** A thing the desk can do because something in the room lets it. */
enum class DeskCapability(val key: String) : StringRepresentable {
    /** Contradictions are pointed out before the ink is spent — the visibility currency (design §7.3). */
    REVEAL_CONFLICTS("reveal_conflicts"),

    /** The readout gains its inferable particles, so a token row reads as a sentence (§4.3.1). */
    READABLE_GRAMMAR("readable_grammar"),

    /** A finished Descriptive Book can be taken apart and rewritten (Phase 8, gated here). */
    EDIT_BOOKS("edit_books"),

    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<DeskCapability> = StringRepresentable.fromEnum(DeskCapability::values)
    }
}

/**
 * One implement, which is one `art/implement/<name>.json`.
 *
 * Named by block or by block tag so a modded orrery counts without us knowing it exists — the tag form is
 * the one another pack should prefer, since it needs no agreement with us about ids.
 */
data class DeskImplement(
    val id: Identifier,
    private val blocks: Set<Identifier>,
    private val tag: TagKey<Block>?,
    val grants: Set<DeskCapability>,
) {
    fun matches(state: BlockState): Boolean =
        (tag != null && state.`is`(tag)) ||
            (blocks.isNotEmpty() && BuiltInRegistries.BLOCK.getKey(state.block) in blocks)

    companion object {
        fun codec(id: Identifier): Codec<DeskImplement> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.listOf().optionalFieldOf("blocks", emptyList())
                    .forGetter { it.blocks.toList() },
                Identifier.CODEC.optionalFieldOf("tag").forGetter { Optional.ofNullable(it.tag?.location()) },
                DeskCapability.CODEC.listOf().optionalFieldOf("grants", emptyList())
                    .forGetter { it.grants.toList() },
            ).apply(instance) { blocks, tag, grants ->
                DeskImplement(
                    id = id,
                    blocks = blocks.toSet(),
                    tag = tag.orElse(null)?.let { TagKey.create(Registries.BLOCK, it) },
                    grants = grants.toSet(),
                )
            }
        }
    }
}

/** What a desk can do, given what is standing around it. */
data class DeskState(
    val capabilities: Set<DeskCapability>,
    /** Pages one book may hold; null meaning no limit. */
    val pageLimit: Int?,
) {
    companion object {
        /** A desk with nothing around it. */
        fun bare(pageLimit: Int?) = DeskState(emptySet(), pageLimit)
    }
}

/** One rung of the desk's furnishing, reached by owning [implements] distinct kinds. */
data class DeskTier(val implements: Int, val pages: Int?) {
    companion object {
        val CODEC: Codec<DeskTier> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("implements").forGetter(DeskTier::implements),
                // Absent means no limit, which is what the top rung is for.
                Codec.INT.optionalFieldOf("pages").forGetter { Optional.ofNullable(it.pages) },
            ).apply(instance) { implements, pages -> DeskTier(implements, pages.orElse(null)) }
        }
    }
}

/**
 * Reading a writer's study off the room it is in (design §7.3, §7.4).
 *
 * Two answers from one survey. **Which** abilities the desk has is per-implement and qualitative — one
 * astrolabe is one astrolabe, and a second changes nothing. **How much** it can say at once is the tier,
 * picked by how many *distinct* implements are present.
 */
class WritersDesk(
    private val implements: List<DeskImplement>,
    private val tiers: List<DeskTier>,
    val radius: Int,
) {
    /**
     * What the desk at [pos] can do — **read fresh on every ask, and deliberately not cached**.
     *
     * A cached survey has to be invalidated, and there is no event that says "something changed anywhere
     * in an 11×11×11 room": `neighborChanged` reaches the blocks touching the desk and nothing else, which
     * is where the desk spent a walk insisting it was bare with an enchanting table beside it.
     *
     * Vanilla settles the cost question, and only that one. `EnchantmentMenu.slotsChanged` walks
     * `EnchantingTableBlock.BOOKSHELF_OFFSETS` from scratch every time its input slot changes and keeps
     * nothing — and this is asked only when a writer does something, never per tick, so it is the same
     * pattern paid less often. What is *searched* is ours and much more generous; see [DEFAULT_RADIUS].
     */
    fun survey(level: BlockGetter, pos: BlockPos): DeskState {
        val present = mutableSetOf<Identifier>()
        val cursor = BlockPos.MutableBlockPos()
        for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
            cursor.setWithOffset(pos, x, y, z)
            val state = level.getBlockState(cursor)
            if (state.isAir) continue
            // A block may answer more than one implement; each still counts once.
            for (implement in implements) {
                if (implement.id !in present && implement.matches(state)) present += implement.id
            }
        }
        val granted = implements.filter { it.id in present }.flatMap { it.grants }.toSet()
        return DeskState(granted, pageLimitFor(present.size))
    }

    /**
     * The pages allowed at [count] distinct implements — the highest rung reached.
     *
     * The rung is chosen *before* its pages are read, because null means two different things here: a
     * rung with no limit, and no rung at all. Reading `?.pages` first collapses them and drops a complete
     * desk back to the basic cap.
     */
    fun pageLimitFor(count: Int): Int? {
        val reached = tiers.filter { it.implements <= count }.maxByOrNull { it.implements }
            ?: tiers.minByOrNull { it.implements }
        return reached?.pages
    }

    companion object {
        const val IMPLEMENT_DIRECTORY = "art/implement"
        const val TIERS_FILE = "art/writers_desk.json"

        /**
         * Half-width of the cube searched, in every direction — so 5 means an 11×11×11 room.
         *
         * A cube rather than a flat disc because people shelve things high, and a study with its rarities
         * on the top shelf should read as furnished.
         *
         * **Deliberately more generous than the enchanting table** (Jonah, 2026-08-06), which is the
         * obvious thing to copy and the wrong one. Vanilla counts only a *shell* at distance two, and only
         * where the block halfway there transmits — so a bookshelf tucked behind another one is worth
         * nothing. Anywhere in this room counts, at any distance up to the radius and through anything: a
         * study is furnished by what is in it, not by what has line of sight to the desk.
         */
        private const val DEFAULT_RADIUS = 5

        fun load(resources: ResourceManager, problems: MutableList<String>): WritersDesk {
            val implements = mutableListOf<DeskImplement>()
            for ((file, resource) in resources.listResources(IMPLEMENT_DIRECTORY, ResourceParsing::isJson)) {
                val id = Identifier.fromNamespaceAndPath(file.namespace, ResourceParsing.nameUnder(file, IMPLEMENT_DIRECTORY))
                implements += ResourceParsing.parse(resource, file, DeskImplement.codec(id), problems)
                    ?: continue
            }
            val settings = resources.getResource(TIERS_FILE.location()).orElse(null)?.let {
                ResourceParsing.parse(it, TIERS_FILE.location(), Settings.CODEC, problems)
            }
            val tiers = settings?.tiers?.sortedBy { it.implements }.orEmpty().ifEmpty {
                problems += "$TIERS_FILE lists no tiers, so every desk is basic"
                listOf(DeskTier(0, null))
            }
            return WritersDesk(implements, tiers, settings?.radius ?: DEFAULT_RADIUS)
        }

        private data class Settings(val tiers: List<DeskTier>, val radius: Int) {
            companion object {
                val CODEC: Codec<Settings> = RecordCodecBuilder.create { instance ->
                    instance.group(
                        DeskTier.CODEC.listOf().fieldOf("tiers").forGetter(Settings::tiers),
                        Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(Settings::radius),
                    ).apply(instance, ::Settings)
                }
            }
        }
    }
}
