package co.voik.agesandtheart.math

import net.minecraft.world.phys.Vec2

/**
 * Ergonomic, Godot-style arithmetic operators for Minecraft's immutable [Vec2] (a float 2D
 * vector), mirroring [Vec3Extensions]. Built directly from the public `x`/`y` fields so the
 * operators are independent of Vec2's (fairly sparse) method set.
 */
operator fun Vec2.plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
operator fun Vec2.minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
operator fun Vec2.times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)
operator fun Vec2.div(scalar: Float): Vec2 = Vec2(x / scalar, y / scalar)
operator fun Vec2.unaryMinus(): Vec2 = Vec2(-x, -y)
