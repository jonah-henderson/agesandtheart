package co.voik.agesandtheart.worldgen.ruins

import co.voik.agesandtheart.Constants

/**
 * How the D'ni city's jigsaw pieces are wired, and what is wrong with it: the mistakes the jigsaw block's screen
 * invites, found before a structure is generated. Read from the running game by `/age jigsaw check` and from the
 * shipped files by `RuinsWiringCheck`.
 *
 * **The convention it holds**: every connector says which way it points. An entrance is named `<base>_in` and draws
 * nothing, so a piece is only ever attached by it; an exit is named `<base>_out`, draws from a pool, and looks for
 * an entrance of the same base.
 */
object JigsawWiring {

    /** Where the city's pieces and pools live; nothing outside it is checked. */
    const val SCOPE = "${Constants.MOD_ID}:ruins/"

    const val ENTRANCE_SUFFIX = "_in"
    const val EXIT_SUFFIX = "_out"
    const val NOTHING = "minecraft:empty"

    data class Connector(val name: String, val target: String, val pool: String)

    data class Piece(val id: String, val connectors: List<Connector>)

    data class Pool(val id: String, val pieces: List<String>, val fallback: String)

    /** The connector [base] names an entrance or exit for, as a full id: `walkway` is `agesandtheart:ruins/walkway`. */
    fun connectorId(base: String, suffix: String): String = "$SCOPE$base$suffix"

    /** What is wrong with [pieces] and [pools] within [SCOPE], one sentence a mistake; empty where nothing is. */
    fun problemsIn(pieces: List<Piece>, pools: List<Pool>): List<String> {
        val ours = pieces.filter { it.id.startsWith(SCOPE) }
        val piecesById = pieces.associateBy { it.id }
        val poolsById = pools.associateBy { it.id }
        val connectorProblems = ours.flatMap { piece -> piece.connectors.flatMap { problemsWith(it, piece, piecesById, poolsById) } }
        val poolProblems = pools.filter { it.id.startsWith(SCOPE) }.flatMap { problemsWith(it, piecesById, poolsById) }
        return connectorProblems + poolProblems
    }

    private fun problemsWith(
        connector: Connector,
        piece: Piece,
        piecesById: Map<String, Piece>,
        poolsById: Map<String, Pool>,
    ): List<String> {
        val where = "${piece.id}'s connector ${connector.name}"
        return when {
            connector.name.endsWith(ENTRANCE_SUFFIX) -> listOfNotNull(
                "$where is an entrance but draws from ${connector.pool}".takeIf { connector.pool != NOTHING },
            )
            connector.name.endsWith(EXIT_SUFFIX) -> problemsWithExit(connector, where, piecesById, poolsById)
            else -> listOf("$where is named neither ..._in nor ..._out")
        }
    }

    private fun problemsWithExit(
        exit: Connector,
        where: String,
        piecesById: Map<String, Piece>,
        poolsById: Map<String, Pool>,
    ): List<String> {
        val base = exit.name.removeSuffix(EXIT_SUFFIX)
        val expectedTarget = "$base$ENTRANCE_SUFFIX"
        val pool = poolsById[exit.pool]
        val targetProblem = "$where looks for ${exit.target}, not $expectedTarget".takeIf { exit.target != expectedTarget }
        val poolProblem = when {
            exit.pool == NOTHING -> "$where is an exit but draws nothing"
            pool == null && exit.pool.startsWith(SCOPE) -> "$where draws from ${exit.pool}, which does not exist"
            pool == null -> null
            entrancesReachableFrom(pool, piecesById, poolsById).none { it == exit.target } ->
                "$where draws from ${exit.pool}, where no piece has the entrance ${exit.target}"
            else -> null
        }
        return listOfNotNull(targetProblem, poolProblem)
    }

    /** Every connector name in the pieces [pool] and its fallbacks could place. */
    private fun entrancesReachableFrom(pool: Pool, piecesById: Map<String, Piece>, poolsById: Map<String, Pool>): Set<String> {
        val seen = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        var next: Pool? = pool
        while (next != null && seen.add(next.id)) {
            next.pieces.mapNotNull(piecesById::get).flatMapTo(names) { piece -> piece.connectors.map { it.name } }
            next = poolsById[next.fallback]
        }
        return names
    }

    private fun problemsWith(pool: Pool, piecesById: Map<String, Piece>, poolsById: Map<String, Pool>): List<String> {
        val missingPieces = pool.pieces.filter { it.startsWith(SCOPE) && it !in piecesById }
        val missingFallback = pool.fallback.startsWith(SCOPE) && pool.fallback !in poolsById
        return missingPieces.map { "pool ${pool.id} names the piece $it, which does not exist" } +
            listOfNotNull("pool ${pool.id} falls back on ${pool.fallback}, which does not exist".takeIf { missingFallback })
    }
}
