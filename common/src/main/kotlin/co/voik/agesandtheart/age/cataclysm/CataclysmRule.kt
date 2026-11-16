package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.datapack.PerReload
import co.voik.agesandtheart.datapack.ResourceParsing
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.resources.ResourceManager
import java.util.Optional

/**
 * How one kind of end comes — **datapack content** (`art/cataclysm/<kind>.json`): how long each stage lasts once
 * it is bought, and when an Age's instability buys it unasked ([inflictedWhen]; null where only a word can).
 */
data class CataclysmRule(val timeline: ImpactTimeline, val inflictedWhen: EndCondition?) {

    companion object {
        /** Three in-game days of warning, then the prototype's stages. */
        val ORDINARY = CataclysmRule(
            ImpactTimeline.PROTOTYPE.copy(approach = 3 * TICKS_PER_DAY),
            inflictedWhen = null,
        )

        val CODEC: Codec<CataclysmRule> = RecordCodecBuilder.create { instance ->
            instance.group(
                ImpactTimeline.CODEC.optionalFieldOf("timeline", ORDINARY.timeline).forGetter(CataclysmRule::timeline),
                EndCondition.CODEC.optionalFieldOf("inflicted_when").forGetter { Optional.ofNullable(it.inflictedWhen) },
            ).apply(instance) { timeline, inflictedWhen -> CataclysmRule(timeline, inflictedWhen.orElse(null)) }
        }

        const val DIRECTORY = "art/cataclysm"

        /** The rules this server is running, read once per datapack load. */
        fun list(server: MinecraftServer): Map<CataclysmKind, CataclysmRule> = current.of(server)

        private val current = PerReload { server -> load(server.resourceManager) }

        /** The rules in [resources], a kind with no file taking [ORDINARY]. */
        fun load(resources: ResourceManager): Map<CataclysmKind, CataclysmRule> {
            val problems = mutableListOf<String>()
            val rules = buildMap {
                for (kind in CataclysmKind.entries) put(kind, ORDINARY)
                for ((file, resource) in resources.listResources(DIRECTORY, ResourceParsing::isJson)) {
                    val key = ResourceParsing.nameUnder(file, DIRECTORY)
                    val kind = CataclysmKind.entries.firstOrNull { it.key == key }
                    if (kind == null) {
                        problems += "$file names no cataclysm, so nothing reads it"
                        continue
                    }
                    ResourceParsing.parse(resource, file, CODEC, problems)?.let { put(kind, it) }
                }
            }
            for (problem in problems) Constants.LOG.warn("Cataclysm rules: {}", problem)
            return rules
        }

        private const val TICKS_PER_DAY = 24_000
    }
}
