package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.age.aspect.Phenomena
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Recurrence
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.Gale
import co.voik.agesandtheart.age.phenomena.GaleDials
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.Mth
import kotlin.math.abs

/**
 * A point of the compass says which way a canyon or a cliff runs and where the wind blows from; a line has no
 * direction, so the opposite point lays the same one. Before `wind`, a gale, a tornado or a blizzard it says only the
 * wind, and the gale's spells and the blizzard's days blow about it.
 */
@Tags(NEEDS_REGISTRIES)
class CompassWordsCheck : FunSpec({

    fun composed(vararg pages: String): AgeComposition = ShippedCorpus.resolved(SEED, "age", *pages).composition

    fun bearingOf(composition: AgeComposition, landform: Terrain): Double? =
        composition.optionsFor(Aspect.TERRAIN, composition.terrains.indexOf(landform)).steer(Terrain.BEARING, SEED)

    test("a point before a canyon or the cliffs says which way it runs") {
        val axes = mapOf(
            "north" to -1.0, "south" to -1.0, "east" to 0.0, "west" to 0.0,
            "northwest" to -0.5, "southeast" to -0.5, "northeast" to 0.5, "southwest" to 0.5,
        )
        for ((landform, page) in listOf(Terrain.CANYON to "cleft", Terrain.CLIFFS to "sheer")) {
            for ((point, axis) in axes) {
                val composition = composed(point, page, "landmass")
                check(composition.terrains == listOf(landform)) { "'$point $page landmass' seated ${composition.terrains}" }
                val bearing = bearingOf(composition, landform)
                check(bearing == axis) { "'$point $page landmass' ran at $bearing, not $axis" }
            }
        }
    }

    test("a point before wind says where it blows from, and it blows the other way") {
        val towards = mapOf("north" to 90.0f, "east" to 180.0f, "south" to 270.0f, "west" to 0.0f, "northwest" to 45.0f)
        for ((point, degrees) in towards) {
            val composition = composed(point, "wind")
            val from = composition.optionsFor(Aspect.AIR).of(Atmosphere.FROM)
            check(from == point) { "'$point wind' blew from $from" }
            val wind = Atmosphere.windIn(composition, SEED)
            check(!wind.isStill) { "'$point wind' was still" }
            check(wind.bearingDegrees == degrees) { "'$point wind' blew towards ${wind.bearingDegrees}, not $degrees" }
        }
    }

    test("a wind nobody turned keeps the Age's own bearing") {
        val drawn = Atmosphere.windIn(composed("wind"), SEED).bearingDegrees
        check(drawn == Atmosphere.prevailingBearingDegrees(composed("windy"), SEED)) { "an unturned wind moved off the seed's bearing" }
    }

    test("before wind, a point says only where it blows from") {
        val turned = bearingOf(composed("north", "wind", "cleft", "landmass"), Terrain.CANYON)
        val alone = bearingOf(composed("cleft", "landmass"), Terrain.CANYON)
        check(turned == alone) { "'north wind' turned the canyon to $turned, where alone it ran at $alone" }
    }

    test("before a gale, a tornado or a blizzard, a point says where the wind blows from and nothing else") {
        for (phenomenon in listOf(Phenomenon.GALE, Phenomenon.TORNADO, Phenomenon.BLIZZARD)) {
            val word = phenomenon.key
            val composition = composed("north", word, "cleft", "landmass")
            val from = composition.optionsFor(Aspect.AIR).of(Atmosphere.FROM)
            check(from == "north") { "'north $word' blew from $from" }
            val raised = Phenomena.claimFor(composition.optionsFor(Aspect.PHENOMENA, 0), phenomenon) != null
            check(raised) { "'north $word' raised no $word" }
            val canyon = bearingOf(composition, Terrain.CANYON)
            val alone = bearingOf(composed("cleft", "landmass"), Terrain.CANYON)
            check(canyon == alone) { "'north $word' turned the canyon to $canyon" }
        }
    }

    test("a gale's spells and a blizzard's days blow about a written wind") {
        val towardsTheSouth = 90.0
        val fierce = GaleDials(often = 1.0, push = 1.0, gusts = 0.0, force = 0.0, sudden = 0.0)
        val off = (0L..TICKS_LOOKED_AT step TICK_STEP).flatMap { tick ->
            val gale = Gale.blowingAt(SEED, tick, Recurrence.ORDINARY, fierce, written = towardsTheSouth)?.bearingDegrees
            val blizzard = Blizzard.bearingDegreesAt(SEED, tick, written = towardsTheSouth)
            listOfNotNull(gale, blizzard).map { abs(Mth.wrapDegrees(it - towardsTheSouth)) }
        }
        check(off.isNotEmpty()) { "nothing blew" }
        check(off.max() <= FURTHEST_SWING) { "a wind written from the north blew ${off.max()} degrees off it" }
    }
})

private const val SEED = 20261111L
private const val TICKS_LOOKED_AT = 24000L * 20
private const val TICK_STEP = 600L
private const val FURTHEST_SWING = 20.0
