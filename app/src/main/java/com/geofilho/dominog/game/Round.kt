package com.geofilho.dominog.game

import kotlin.random.Random

enum class EndReason { BATIDA, TRANCADO }

data class RoundResult(
    /** Índice do vencedor ou -1 em empate. */
    val winner: Int,
    val reason: EndReason,
    val points: Int,
)

data class Move(val player: Int, val tile: Tile, val side: Side)

/**
 * Uma rodada de dominó para 2 jogadores (regra brasileira comum):
 * 7 pedras para cada, 14 no monte. Quem não tem jogada compra do monte;
 * monte vazio → passa. Bate quem zerar a mão; se ninguém puder jogar, tranca
 * e vence quem tiver menos pontos na mão.
 */
class Round private constructor(
    val hands: List<MutableList<Tile>>,
    val board: ArrayDeque<Placed>,
    val boneyard: MutableList<Tile>,
    current: Int,
    /** Pedra obrigatória na primeira jogada (carroça mais alta), ou null. */
    var openingTile: Tile?,
    passes: Int,
    result: RoundResult?,
) {
    var current: Int = current; private set
    var consecutivePasses: Int = passes; private set
    var result: RoundResult? = result; private set
    var lastMove: Move? = null; private set

    /** Números que cada jogador mostrou não ter (comprou ou passou com essas pontas). Usado pela IA difícil. */
    val lacks: List<MutableSet<Int>> = listOf(HashSet(), HashSet())

    private fun noteLacking(player: Int) {
        leftEnd?.let { lacks[player].add(it) }
        rightEnd?.let { lacks[player].add(it) }
    }

    val isOver get() = result != null
    val leftEnd get() = board.firstOrNull()?.left
    val rightEnd get() = board.lastOrNull()?.right

    fun sidesFor(tile: Tile): List<Side> {
        if (board.isEmpty()) return if (openingTile == null || openingTile === tile) listOf(Side.RIGHT) else emptyList()
        val l = leftEnd!!; val r = rightEnd!!
        val out = ArrayList<Side>(2)
        if (tile.has(l)) out += Side.LEFT
        if (tile.has(r) && (l != r || !tile.has(l))) out += Side.RIGHT
        // Pontas iguais: jogar em qualquer lado dá no mesmo, oferece só um.
        return out
    }

    fun playable(player: Int): List<Tile> = hands[player].filter { sidesFor(it).isNotEmpty() }
    fun canDraw(player: Int) = !isOver && player == current && playable(player).isEmpty() && boneyard.isNotEmpty()
    fun mustPass(player: Int) = !isOver && player == current && playable(player).isEmpty() && boneyard.isEmpty()

    fun play(player: Int, tile: Tile, side: Side): Boolean {
        if (isOver || player != current) return false
        val hand = hands[player]
        if (!hand.contains(tile)) return false
        if (side !in sidesFor(tile) && !(board.isEmpty() && sidesFor(tile).isNotEmpty())) return false
        if (board.isEmpty()) {
            board.addLast(Placed(tile, tile.a, tile.b))
            openingTile = null
        } else if (side == Side.LEFT) {
            val l = leftEnd!!
            board.addFirst(Placed(tile, tile.other(l), l))
        } else {
            val r = rightEnd!!
            board.addLast(Placed(tile, r, tile.other(r)))
        }
        hand.remove(tile)
        lacks[player].remove(tile.a); lacks[player].remove(tile.b)
        lastMove = Move(player, tile, side)
        consecutivePasses = 0
        if (hand.isEmpty()) {
            result = RoundResult(player, EndReason.BATIDA, handPips(1 - player))
        } else {
            current = 1 - current
        }
        return true
    }

    fun draw(player: Int): Tile? {
        if (!canDraw(player)) return null
        noteLacking(player)
        val t = boneyard.removeAt(boneyard.lastIndex)
        hands[player].add(t)
        return t
    }

    fun pass(player: Int): Boolean {
        if (!mustPass(player)) return false
        noteLacking(player)
        consecutivePasses++
        lastMove = null
        if (consecutivePasses >= 2) {
            val p0 = handPips(0); val p1 = handPips(1)
            result = when {
                p0 < p1 -> RoundResult(0, EndReason.TRANCADO, p1)
                p1 < p0 -> RoundResult(1, EndReason.TRANCADO, p0)
                else -> RoundResult(-1, EndReason.TRANCADO, 0)
            }
        } else {
            current = 1 - current
        }
        return true
    }

    fun handPips(player: Int) = hands[player].sumOf { it.pips }

    // ---------- Serialização compacta (usada no Bluetooth) ----------
    fun encode(): String = buildString {
        append(hands[0].joinToString(",") { it.id.toString() }).append(';')
        append(hands[1].joinToString(",") { it.id.toString() }).append(';')
        append(board.joinToString(",") { "${it.tile.id}:${it.left}:${it.right}" }).append(';')
        append(boneyard.joinToString(",") { it.id.toString() }).append(';')
        append(current).append(';')
        append(openingTile?.id ?: -1).append(';')
        append(consecutivePasses).append(';')
        val r = result
        append(if (r == null) "-" else "${r.winner}:${r.reason.ordinal}:${r.points}").append(';')
        val m = lastMove
        append(if (m == null) "-" else "${m.player}:${m.tile.id}:${m.side.ordinal}")
    }

    companion object {
        fun deal(starterOverride: Int?, random: Random = Random.Default): Round {
            val deck = Tile.ALL.shuffled(random).toMutableList()
            val h0 = deck.subList(0, 7).toMutableList()
            val h1 = deck.subList(7, 14).toMutableList()
            val bone = deck.subList(14, 28).toMutableList()
            val hands = listOf(h0, h1)
            // Primeira rodada: quem começa é sorteado e joga a pedra que quiser.
            val starter = starterOverride ?: random.nextInt(2)
            return Round(hands, ArrayDeque(), bone, starter, null, 0, null)
        }

        private fun ids(s: String) = if (s.isEmpty()) mutableListOf() else s.split(',').map { Tile.byId(it.toInt()) }.toMutableList()

        fun decode(s: String): Round {
            val p = s.split(';')
            val board = ArrayDeque<Placed>()
            if (p[2].isNotEmpty()) p[2].split(',').forEach {
                val (id, l, r) = it.split(':').map(String::toInt)
                board.addLast(Placed(Tile.byId(id), l, r))
            }
            val result = if (p[7] == "-") null else p[7].split(':').map(String::toInt)
                .let { RoundResult(it[0], EndReason.entries[it[1]], it[2]) }
            val round = Round(
                listOf(ids(p[0]), ids(p[1])), board, ids(p[3]), p[4].toInt(),
                p[5].toInt().takeIf { it >= 0 }?.let(Tile::byId), p[6].toInt(), result,
            )
            if (p[8] != "-") {
                val (pl, id, sd) = p[8].split(':').map(String::toInt)
                round.lastMove = Move(pl, Tile.byId(id), Side.entries[sd])
            }
            return round
        }
    }
}
