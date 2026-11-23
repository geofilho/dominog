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

    @Test fun threeAndFourPlayerGamesFinishConsistently() {
        for (n in 3..4) repeat(2000) { seed ->
            val r = Round.deal(null, Random(seed), n)
            assertEquals(n, r.n)
            assertEquals(if (n == 3) 7 else 0, r.boneyard.size)
            var guard = 0
            while (!r.isOver) {
                val p = r.current
                val m = Ai.choose(r, p)
                when {
                    m != null -> assertTrue(r.play(p, m.tile, m.side))
                    r.canDraw(p) -> { assertTrue(n == 3); r.draw(p) }
                    else -> assertTrue(r.pass(p))
                }
                assertEquals(r.encode(), Round.decode(r.encode()).encode())
                assertTrue(guard++ < 300)
            }
            assertEquals(28, (0 until n).sumOf { r.hands[it].size } + r.board.size + r.boneyard.size)
            r.board.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left) }
            val res = r.result!!
            if (res.winner >= 0) {
                // Pontos = mãos dos outros lados (com 4, a dupla do vencedor não conta).
                val expected = (0 until n).filter { r.sideOf(it) != r.sideOf(res.winner) }.sumOf { r.handPips(it) }
                assertEquals(expected, res.points)
            }
        }
    }

    @Test fun fourPlayersAlternateTeams() {
        val r = Round.deal(0, Random(1), 4)
        assertEquals(2, r.sides)
        assertEquals(listOf(0, 1, 0, 1), (0 until 4).map(r::sideOf))
        assertEquals(1, r.next(0)); assertEquals(0, r.next(3))
    }

    /** Joga [games] rodadas entre dois níveis (alternando quem começa) e devolve as vitórias de [a]. */
    private fun duel(a: Level, b: Level, games: Int): Int {
        var winsA = 0
        repeat(games) { g ->
            val rnd = Random(g)
            val r = Round.deal(g % 2, rnd)
            val lv = arrayOf(a, b)
            while (!r.isOver) {
                val p = r.current
                val m = Ai.choose(r, p, lv[p], rnd)
                when {
                    m != null -> r.play(p, m.tile, m.side)
                    r.canDraw(p) -> r.draw(p)
                    else -> r.pass(p)
                }
            }
            if (r.result!!.winner == 0) winsA++
        }
        return winsA
    }

    @Test fun levelsAreOrdered() {
        val n = 4000
        val hardVsEasy = duel(Level.DIFICIL, Level.FACIL, n)
        val hardVsMedium = duel(Level.DIFICIL, Level.MEDIO, n)
        val mediumVsEasy = duel(Level.MEDIO, Level.FACIL, n)
        println("difícil×fácil=$hardVsEasy difícil×médio=$hardVsMedium médio×fácil=$mediumVsEasy (de $n)")
        assertTrue(hardVsEasy > n * 0.55)
        assertTrue(mediumVsEasy > n * 0.52)
        assertTrue(hardVsMedium > n * 0.5)
    }

    @Test fun batidaKindsAndScores() {
        assertEquals(BatidaKind.SIMPLES, BatidaKind.of(Tile.of(3, 5), 3, 1))
        assertEquals(BatidaKind.CARROCA, BatidaKind.of(Tile.of(3, 3), 3, 1))
        assertEquals(BatidaKind.LA_E_LO, BatidaKind.of(Tile.of(3, 1), 3, 1))
        assertEquals(BatidaKind.LA_E_LO, BatidaKind.of(Tile.of(3, 1), 1, 3))
        assertEquals(BatidaKind.CRUZADA, BatidaKind.of(Tile.of(4, 4), 4, 4))
        // Pontas iguais e pedra comum: só encaixa numa ponta.
        assertEquals(BatidaKind.SIMPLES, BatidaKind.of(Tile.of(4, 2), 4, 4))
        assertEquals(listOf(1, 2, 3, 4), BatidaKind.entries.map { it.score })
        assertEquals(3, RoundResult(0, EndReason.BATIDA, 10, BatidaKind.LA_E_LO).score)
        assertEquals(1, RoundResult(1, EndReason.TRANCADO, 10).score)
        assertEquals(0, RoundResult(-1, EndReason.TRANCADO, 0).score)
        // O tipo da batida sobrevive à codificação usada no Bluetooth.
        val r = Round.decode("2;;5;10:3:1;;0;-1;0;0:0:12:2;-")
        assertEquals(BatidaKind.LA_E_LO, r.result!!.kind)
        assertEquals(r.encode(), Round.decode(r.encode()).encode())
    }

    @Test fun withoutBuyingPlayersPassAndGamesFinish() {
        repeat(500) { seed ->
            val r = Round.deal(null, Random(seed), buying = false)
            assertEquals(r.encode(), Round.decode(r.encode()).encode())
            assertTrue(!Round.decode(r.encode()).buying)
            var guard = 0
            while (!r.isOver) {
                val p = r.current
                assertTrue(!r.canDraw(p))
                val m = Ai.choose(r, p)
                if (m != null) assertTrue(r.play(p, m.tile, m.side)) else assertTrue(r.pass(p))
                assertTrue(guard++ < 100)
            }
            assertEquals(14, r.boneyard.size)
        }
    }
}
