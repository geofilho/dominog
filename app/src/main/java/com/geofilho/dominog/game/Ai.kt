package com.geofilho.dominog.game

/** Jogador do computador: heurística gulosa simples e rápida. */
object Ai {
    fun choose(round: Round, player: Int): Move? {
        val hand = round.hands[player]
        var best: Move? = null
        var bestScore = Int.MIN_VALUE
        for (t in round.playable(player)) {
            for (side in round.sidesFor(t)) {
                val newEnd = when {
                    round.board.isEmpty() -> t.b
                    side == Side.LEFT -> t.other(round.leftEnd!!)
                    else -> t.other(round.rightEnd!!)
                }
                // Livra-se de pedras pesadas e carroças; prefere abrir pontas que ainda tem na mão.
                val keep = hand.count { it !== t && it.has(newEnd) }
                val score = t.pips * 2 + (if (t.isDouble) 6 else 0) + keep * 3
                if (score > bestScore) { bestScore = score; best = Move(player, t, side) }
            }
        }
        return best
    }
}
