package com.geofilho.dominog.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoundTest {
    @Test fun deckHas28UniqueTiles() {
        assertEquals(28, Tile.ALL.size)
        assertEquals(28, Tile.ALL.map { it.id }.toSet().size)
    }

    @Test fun firstRoundStarterIsRandomAndFree() {
        val starters = IntArray(2)
        repeat(400) { seed ->
            val r = Round.deal(null, Random(seed))
            starters[r.current]++
            assertEquals(r.hands[r.current], r.playable(r.current))
        }
        assertTrue(starters[0] > 120 && starters[1] > 120)
    }

    @Test fun aiVsAiGamesAlwaysFinishConsistently() {
        repeat(2000) { seed ->
            val r = Round.deal(if (seed % 2 == 0) null else seed % 2, Random(seed))
            var guard = 0
            while (!r.isOver) {
                val p = r.current
                val m = Ai.choose(r, p)
                when {
                    m != null -> assertTrue(r.play(p, m.tile, m.side))
                    r.canDraw(p) -> r.draw(p)
                    else -> assertTrue(r.pass(p))
                }
                // Codificar/decodificar não pode perder estado.
                assertEquals(r.encode(), Round.decode(r.encode()).encode())
                assertTrue(guard++ < 200)
            }
            val total = r.hands[0].size + r.hands[1].size + r.board.size + r.boneyard.size
            assertEquals(28, total)
            // Cada pedra encaixa na vizinha.
            r.board.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left) }
        }
    }
}
