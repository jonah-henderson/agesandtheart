package co.voik.agesandtheart.math

import net.minecraft.world.phys.Vec3

/**
 * Ergonomic, Godot-style arithmetic operators for Minecraft's immutable [Vec3].
 *
 * Vec3 already offers `dot`, `cross`, `normalize`, `length`, `distanceTo`, and `lerp`; these add
 * the operators so vector math reads naturally (`a + b * scale` instead of `a.add(b.scale(scale))`).
 * We build on Vec3 — rather than a custom type or mutable JOML `Vector3f` — because it is immutable
 * (value semantics) and is the type the engine both hands us and accepts back.
 */
operator fun Vec3.plus(other: Vec3): Vec3 = add(other)
operator fun Vec3.minus(other: Vec3): Vec3 = subtract(other)
operator fun Vec3.times(scalar: Double): Vec3 = scale(scalar)
operator fun Vec3.times(scalar: Float): Vec3 = scale(scalar.toDouble())
operator fun Vec3.div(scalar: Double): Vec3 = scale(1.0 / scalar)
operator fun Vec3.unaryMinus(): Vec3 = reverse()
