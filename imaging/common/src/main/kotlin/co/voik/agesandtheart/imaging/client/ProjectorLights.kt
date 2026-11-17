package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.Imaging
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * What the light behind a projector does to its throw, read from resource packs: `assets/<namespace>/projector_lights/
 * <name>.json`, each naming blocks or block tags and any of a tint, a brightness, a flicker and an overlay. Without a
 * brightness the throw follows the light's own level, so a cluster of candles brightens with each candle and a rime
 * crystal with its power. A light in the `perfect_projector_lights` tag throws the picture as it is; one named in no
 * file throws it untinted at its own light level. With `tint_from_block`, a light recoloured where it stands (algae an
 * Age has coloured, say) takes the colour it is drawn in there, and the file's tint where it is its ordinary self.
 *
 * ```
 * { "blocks": ["minecraft:redstone_torch", "#minecraft:example"], "tint": "FF5A4A", "brightness": 0.45,
 *   "flicker": 0.3, "overlay": "minecraft:textures/misc/pumpkinblur.png" }
 * ```
 * The overlay's alpha is what it holds back: light passes where it is clear.
 */
object ProjectorLights : ResourceManagerReloadListener {

    /** [brightness] nought to one, or null for the light's own level. */
    data class Effect(
        val tint: Int,
        val brightness: Float?,
        val flicker: Float,
        val overlay: Identifier?,
        val tintFromBlock: Boolean = false,
        val isPerfect: Boolean = false,
    )

    /** How bright [light] throws under [effect]. */
    fun brightnessOf(effect: Effect, light: BlockState): Float = effect.brightness ?: (light.lightEmission / FULL_LIGHT)

    val PERFECT_LIGHTS: TagKey<Block> = TagKey.create(Registries.BLOCK, Imaging.id("perfect_projector_lights"))

    val ID: Identifier = Imaging.id("projector_lights")

    private const val FOLDER = "projector_lights"
    private const val WHITE = 0xFFFFFF
    private const val FULL_LIGHT = 15.0f
    private val PERFECT = Effect(WHITE, 1.0f, 0.0f, null, isPerfect = true)

    private var byBlock: Map<Block, Effect> = emptyMap()
    private var byTag: List<Pair<TagKey<Block>, Effect>> = emptyList()

    fun effectOf(level: BlockAndTintGetter, at: BlockPos, light: BlockState): Effect {
        if (light.`is`(PERFECT_LIGHTS)) return PERFECT
        val listed = byBlock[light.block] ?: byTag.firstOrNull { (tag, _) -> light.`is`(tag) }?.second ?: return Effect(WHITE, null, 0.0f, null)
        return if (listed.tintFromBlock) listed.copy(tint = recolouredOr(listed.tint, level, at, light)) else listed
    }

    /** The colour [light] is drawn in where it stands, where that differs from its ordinary colour, or else [tint]. */
    private fun recolouredOr(tint: Int, level: BlockAndTintGetter, at: BlockPos, light: BlockState): Int {
        val source = Minecraft.getInstance().blockColors.getTintSource(light, 0) ?: return tint
        val whereItStands = source.colorInWorld(light, level, at) and WHITE
        val ordinarily = source.color(light) and WHITE
        return if (whereItStands != ordinarily) whereItStands else tint
    }

    override fun onResourceManagerReload(manager: ResourceManager) {
        val blocks = HashMap<Block, Effect>()
        val tags = ArrayList<Pair<TagKey<Block>, Effect>>()
        manager.listResources(FOLDER) { it.path.endsWith(".json") }.forEach { (file, resource) ->
            runCatching {
                val json = resource.openAsReader().use { JsonParser.parseReader(it).asJsonObject }
                val effect = effectFrom(json)
                json.getAsJsonArray("blocks").forEach { named ->
                    val name = named.asString
                    if (name.startsWith("#")) {
                        tags += TagKey.create(Registries.BLOCK, Identifier.parse(name.drop(1))) to effect
                    } else {
                        BuiltInRegistries.BLOCK.getOptional(Identifier.parse(name)).ifPresent { blocks[it] = effect }
                    }
                }
            }.onFailure { Imaging.LOG.warn("Projector light {} could not be read: {}", file, it.message) }
        }
        byBlock = blocks
        byTag = tags
        Imaging.LOG.info("Read {} projector lights and {} tags of them", blocks.size, tags.size)
    }

    private fun effectFrom(json: JsonObject) = Effect(
        tint = json.get("tint")?.asString?.toInt(radix = 16) ?: WHITE,
        brightness = json.get("brightness")?.asFloat,
        flicker = json.get("flicker")?.asFloat ?: 0.0f,
        overlay = json.get("overlay")?.asString?.let(Identifier::parse),
        tintFromBlock = json.get("tint_from_block")?.asBoolean ?: false,
    )
}
