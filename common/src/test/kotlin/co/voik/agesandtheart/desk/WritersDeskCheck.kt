package co.voik.agesandtheart.desk

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** Whether a study can actually be furnished with what we ship. */
@Tags(NEEDS_REGISTRIES)
class WritersDeskCheck : FunSpec({

    val problems = mutableListOf<String>()
    val desk by lazy { WritersDesk.load(MinecraftRegistries.shippedData(), problems) }

    test("the writer's desk data loads cleanly") {
        desk
        check(problems.isEmpty()) { "WritersDesk data did not load:\n  ${problems.joinToString("\n  ")}" }
    }

    /**
     * The top rung must be reachable with what we ship, or the page cap never lifts. This is the check
     * that would catch someone adding a tier threshold without adding implements to meet it.
     */
    test("every tier is reachable with the implements we ship") {
        val shipped = desk.let { it.survey(EmptyRoom, net.minecraft.core.BlockPos.ZERO) }
        check(shipped.capabilities.isEmpty()) { "An empty room should furnish nothing, found ${shipped.capabilities}" }

        val highest = (0..MANY).mapNotNull { count -> desk.pageLimitFor(count) }
        check(highest.isNotEmpty()) { "No tier declares a page limit at all" }
        check(desk.pageLimitFor(MANY) == null) {
            "With $MANY implements the page limit should be lifted entirely, got ${desk.pageLimitFor(MANY)}"
        }
    }

    /** More furniture must never mean fewer pages. */
    test("page limits never decrease as the study grows") {
        val limits = (0..MANY).map { desk.pageLimitFor(it) }
        val bounded = limits.takeWhile { it != null }.map { it!! }
        check(bounded == bounded.sorted()) { "Page limits go down as implements are added: $bounded" }
    }
}) {
    private companion object {
        /** More implements than any pack would plausibly ship, for probing the top rung. */
        const val MANY = 64
    }
}

/** A level with nothing in it, for surveying against. */
private object EmptyRoom : net.minecraft.world.level.BlockGetter {
    override fun getBlockState(pos: net.minecraft.core.BlockPos): net.minecraft.world.level.block.state.BlockState =
        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()

    override fun getFluidState(pos: net.minecraft.core.BlockPos): net.minecraft.world.level.material.FluidState =
        net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState()

    override fun getBlockEntity(pos: net.minecraft.core.BlockPos): net.minecraft.world.level.block.entity.BlockEntity? = null

    override fun getHeight(): Int = 384

    override fun getMinY(): Int = -64
}
