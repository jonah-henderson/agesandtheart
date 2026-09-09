package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * **What a drifting body actually occupies**, for the two things that ask: what you can stand on, and what
 * an arrow can hit.
 *
 * Both had been the body's bounding box, which is the cube it weathered *from* — and a weathered rock is
 * better than a third gaps, so standing on nothing and shooting nothing both worked (Jonah, 2026-09-09).
 * Neither path has a hook, so both are reached by a Mixin and this is what those Mixins call: the rule
 * lives in `common` in Kotlin, and the Java is two lines of plumbing each.
 */
object OreColliders {

    /**
     * The shaped colliders of every body overlapping [box] — **what vanilla would have added as cubes**.
     *
     * `EntityGetter.getEntityCollisions` hard-codes `Shapes.create(entity.getBoundingBox())` with no
     * per-entity say in it, so a body answers `canBeCollidedWith` with **no** and is added here instead.
     * That is the whole of why the two halves are split this way round: there is no seam that lets an
     * entity offer a shape, only one that lets a shape be added beside them.
     *
     * Asked through the entity-type index rather than by walking everything nearby, which is the same call
     * `DriftingOreSpawner` makes and costs a section lookup where there are none.
     */
    fun standingIn(source: Entity?, level: Level, box: AABB): List<VoxelShape> {
        val bodies = level.getEntities(AgeContent.DRIFTING_ORE, box) { it !== source }
        return if (bodies.isEmpty()) emptyList() else bodies.map { it.collider() }
    }

    /**
     * Whether [projectile] passes *through* [target] rather than hitting it — true only for a body whose
     * rock the shot actually missed.
     *
     * **Asked at `Projectile.canHitEntity` rather than in the raycast**, which is the smaller seam by a
     * long way: the raycast tests `getBoundingBox().inflate(pickRadius)` for every candidate inline and
     * would have to be rewritten, where this is one question about one entity that vanilla already asks.
     *
     * The segment is where the projectile is and where it is going this tick, which is what the raycast
     * itself uses. A shot fast enough to cross the whole rock inside one tick tunnels through it — the
     * same tunnelling vanilla's own blocks have, and not worth a sweep to fix.
     */
    fun passesThrough(projectile: Projectile, target: Entity): Boolean {
        if (target !is DriftingOre) return false
        val from = projectile.position()
        val to = from.add(projectile.deltaMovement)
        return target.collider().clip(from, to, BlockPos.ZERO) == null
    }
}
