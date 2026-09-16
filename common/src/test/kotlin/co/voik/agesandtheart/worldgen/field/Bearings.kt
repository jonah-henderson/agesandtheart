package co.voik.agesandtheart.worldgen.field

/**
 * Two more points on the bearing axis beside [co.voik.agesandtheart.age.aspect.NORTH_SOUTH], for the checks
 * that walk a line at an angle.
 *
 * Axis values as a writer's word leaves them, not angles: a field takes radians now, so a check passes
 * these through [co.voik.agesandtheart.age.aspect.bearingAt] exactly as `Terrain` does.
 */
const val DIAGONAL = -0.5
const val EAST_WEST = 0.0
