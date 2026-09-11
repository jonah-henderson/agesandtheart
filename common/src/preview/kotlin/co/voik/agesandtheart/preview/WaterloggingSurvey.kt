package co.voik.agesandtheart.preview

import co.voik.agesandtheart.MinecraftRegistries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * What a second waterlogging property would actually cost, measured rather than estimated.
 *
 * Deep water cannot be waterlogged into a block today because `SimpleWaterloggedBlock` gates on the fluid's
 * *identity* — `type == Fluids.WATER` — and because `BlockStateBase.getFluidState()` takes no position, so
 * "waterlogged means deep water here" is unsayable. A second block state property is the only route, and
 * the two questions that decide whether it is worth taking are how many states it doubles and whether one
 * `instanceof` can find every block that needs it.
 *
 * The second is the load-bearing one: the whole plan is a single mixin gating on `SimpleWaterloggedBlock`,
 * so a block carrying `waterlogged` **without** that interface is a hole in it, and a block carrying the
 * interface without the property is a false positive. Both are printed by name if they exist.
 */
fun main(args: Array<String>) {
    MinecraftRegistries.ensureStoodUp()
    // A file of block ids, one a line, to price as a narrower alternative to "every waterloggable block".
    val narrowed = args.firstOrNull()?.let { java.io.File(it).readLines().map(String::trim).filter(String::isNotEmpty) }?.toSet()

    var blocks = 0
    var states = 0
    var loggableBlocks = 0
    var loggableStates = 0
    val widest = mutableListOf<Pair<String, Int>>()
    val interfaceWithoutProperty = mutableListOf<String>()
    val propertyWithoutInterface = mutableListOf<String>()

    for (block in BuiltInRegistries.BLOCK) {
        val name = BuiltInRegistries.BLOCK.getKey(block).toString()
        val here = block.stateDefinition.possibleStates.size
        blocks++
        states += here

        val saysItIsWaterlogged = block is SimpleWaterloggedBlock
        val carriesTheProperty = block.stateDefinition.properties.contains(BlockStateProperties.WATERLOGGED)
        if (saysItIsWaterlogged && !carriesTheProperty) interfaceWithoutProperty += name
        if (carriesTheProperty && !saysItIsWaterlogged) propertyWithoutInterface += name

        if (saysItIsWaterlogged || carriesTheProperty) {
            loggableBlocks++
            loggableStates += here
            widest += name to here
        }
    }

    println("Vanilla, as bootstrapped:")
    println("  $blocks blocks, $states block states")
    println("  $loggableBlocks of them waterloggable, carrying $loggableStates states")
    println("  a second boolean on those adds $loggableStates states — ${percent(loggableStates, states)} more overall")
    println()
    println("The twenty widest, which are what the doubling is actually made of:")
    for ((name, count) in widest.sortedByDescending { it.second }.take(20)) {
        println("  ${"%5d".format(count)}  $name")
    }
    println()
    println("Does one `instanceof SimpleWaterloggedBlock` find them all?")
    say("carry the interface but not the property", interfaceWithoutProperty)
    say("carry the property but not the interface", propertyWithoutInterface)

    // Prices the one step of the plan that costs time rather than memory: the fluid a waterlogged state
    // reports is a field cached at bootstrap, so a mod registering its fluid later has to ask for the
    // cache again. `initCache()` is public, so that is a pass rather than a seam -- but it redoes every
    // state's shapes and occlusion, so it is worth knowing what it costs before proposing it.
    val began = System.nanoTime()
    var recached = 0
    for (block in BuiltInRegistries.BLOCK) {
        if (block !is SimpleWaterloggedBlock) continue
        for (state in block.stateDefinition.possibleStates) {
            state.initCache()
            recached++
        }
    }
    val took = (System.nanoTime() - began) / 1_000_000.0
    println()
    println("Re-caching the waterloggable states costs ${"%.0f".format(took)} ms for $recached states.")

    if (narrowed == null) return
    println()
    println("Narrowed to the ${narrowed.size} ids given:")
    var narrowBlocks = 0
    var narrowStates = 0
    val loggable = mutableListOf<Pair<String, Int>>()
    for (block in BuiltInRegistries.BLOCK) {
        val name = BuiltInRegistries.BLOCK.getKey(block).toString()
        if (name !in narrowed || block !is SimpleWaterloggedBlock) continue
        val here = block.stateDefinition.possibleStates.size
        narrowBlocks++
        narrowStates += here
        loggable += name to here
    }
    println("  $narrowBlocks of them waterloggable, carrying $narrowStates states")
    println("  a second boolean on those adds $narrowStates — ${percent(narrowStates, states)} more overall")
    for ((name, count) in loggable.sortedByDescending { it.second }) println("  ${"%5d".format(count)}  $name")
}

private fun say(what: String, names: List<String>) {
    if (names.isEmpty()) {
        println("  none $what")
        return
    }
    println("  ${names.size} $what — the gate would be wrong for these:")
    for (name in names) println("    $name")
}

private fun percent(part: Int, whole: Int) = "%.1f%%".format(100.0 * part / whole)
