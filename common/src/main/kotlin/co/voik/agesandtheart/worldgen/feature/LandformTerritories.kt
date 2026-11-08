package co.voik.agesandtheart.worldgen.feature

/** A generator that knows which of its landforms' territories a column is in — see [Formation.onlyOn]. */
fun interface LandformTerritories {
    /** The member index of the landform whose territory holds this column. */
    fun territoryAt(blockX: Int, blockZ: Int): Int
}
