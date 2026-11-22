package com.geofilho.dominog.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geofilho.dominog.audio.SoundFx
import com.geofilho.dominog.bt.BtLink
import com.geofilho.dominog.bt.LinkState
import com.geofilho.dominog.bt.Proto
import com.geofilho.dominog.game.Ai
import com.geofilho.dominog.game.EndReason
import com.geofilho.dominog.game.Round
import com.geofilho.dominog.game.Side
import com.geofilho.dominog.game.Tile
import com.geofilho.dominog.game.Words
import com.geofilho.dominog.speech.Voice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { MENU, BLUETOOTH, GAME, HELP }
enum class Mode { CPU, LOCAL, BT_HOST, BT_GUEST }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dominog", Context.MODE_PRIVATE)
    val voice = Voice(app)
    val sfx = SoundFx()
    val link = BtLink(app)

    var screen by mutableStateOf(Screen.MENU); private set
    var mode by mutableStateOf(Mode.CPU); private set

    // ---------- Estado do jogo ----------
    var round: Round? = null; private set
    /** Incrementa a cada mudança na rodada para a interface se redesenhar. */
    var version by mutableIntStateOf(0); private set
    val scores = IntArray(2)
    /** Modo mesmo aparelho: as pedras do jogador da vez estão à mostra? */
    var revealed by mutableStateOf(false); private set
    /** Animação das pedras rolando na mesa. */
    var shuffling by mutableStateOf(false); private set
    private var cpuJob: Job? = null

    // ---------- Bluetooth ----------
    var playerName by mutableStateOf(prefs.getString("name", "") ?: ""); private set
    var friendName by mutableStateOf(""); private set
    var inviteReceived by mutableStateOf(false); private set
    var waitingAccept by mutableStateOf(false); private set
    var lobbyMessage by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch { link.incoming.collect(::onMessage) }
        viewModelScope.launch {
            link.state.collect { st ->
                when (st) {
                    LinkState.Connected -> {
                        link.send("${Proto.HELLO}|$playerName")
                        say("Conectado", flush = true)
                    }
                    is LinkState.Failed -> if (mode == Mode.BT_HOST || mode == Mode.BT_GUEST) {
                        friendName = ""; inviteReceived = false; waitingAccept = false
                        if (screen == Screen.GAME) {
                            screen = Screen.BLUETOOTH
                            lobbyMessage = st.message
                            say(st.message, flush = true)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** Índice do jogador cujas pedras este aparelho mostra. */
    val me: Int
        get() = when (mode) {
            Mode.CPU, Mode.BT_HOST -> 0
            Mode.BT_GUEST -> 1
            Mode.LOCAL -> round?.current ?: 0
        }

    fun nameOf(p: Int): String = when (mode) {
        Mode.CPU -> if (p == 0) "Você" else "Computador"
        Mode.LOCAL -> if (p == 0) "Jogador 1" else "Jogador 2"
        Mode.BT_HOST -> if (p == 0) playerName else friendName.ifBlank { "Amigo" }
        Mode.BT_GUEST -> if (p == 1) playerName else friendName.ifBlank { "Amigo" }
    }

    private fun spokenName(p: Int) = when (mode) {
        Mode.LOCAL -> if (p == 0) "Jogador um" else "Jogador dois"
        else -> nameOf(p)
    }

    // =================== Navegação ===================
    fun startCpu() { mode = Mode.CPU; newMatch() }
    fun startLocal() { mode = Mode.LOCAL; newMatch() }
    fun openBluetooth() { screen = Screen.BLUETOOTH; lobbyMessage = null }
    fun openHelp() { screen = Screen.HELP }

    fun backToMenu() {
        cpuJob?.cancel()
        voice.stop()
        if (mode == Mode.BT_HOST || mode == Mode.BT_GUEST || screen == Screen.BLUETOOTH) {
            if (link.state.value == LinkState.Connected) link.send(Proto.BYE)
            link.disconnect()
            friendName = ""; inviteReceived = false; waitingAccept = false
        }
        round = null
        screen = Screen.MENU
    }

    fun saveName(name: String) {
        playerName = name.replace('|', ' ').trim().take(20)
        prefs.edit().putString("name", playerName).apply()
    }

    // =================== Partida ===================
    private fun newMatch() {
        scores.fill(0)
        round = null
        dealRound(null)
        screen = Screen.GAME
    }

    private fun dealRound(starter: Int?) {
        cpuJob?.cancel()
        val r = Round.deal(starter)
        round = r
        revealed = false
        shuffling = true
        sfx.playShuffle()
        say("Embaralhando as pedras", flush = true)
        bump()
        broadcast()
        viewModelScope.launch {
            delay(1700)
            shuffling = false
            announceTurn(r, newRound = true)
            maybeCpu()
        }
    }

    fun nextRound() {
        val r = round ?: return
        if (!r.isOver) return
        when (mode) {
            Mode.BT_GUEST -> link.send(Proto.NEXT)
            else -> {
                val w = r.result!!.winner
                dealRound(if (w >= 0) w else 1 - r.current)
            }
        }
    }

    // ---------- Ações do jogador neste aparelho ----------
    fun reveal() {
        revealed = true
        val r = round
        say("Pedras visíveis." + (if (r != null) hintFor(r, r.current) else ""), flush = true)
        bump()
    }

    fun sidesFor(t: Tile): List<Side> = round?.sidesFor(t) ?: emptyList()

    fun play(t: Tile, side: Side) {
        if (!canAct()) return
        if (mode == Mode.BT_GUEST) { link.send("${Proto.PLAY}|${t.id}|${side.ordinal}"); return }
        act(me) { it.play(me, t, side) }
    }

    fun draw() {
        if (!canAct()) return
        if (mode == Mode.BT_GUEST) { link.send(Proto.DRAW); return }
        act(me) { it.draw(me) != null }
    }

    fun pass() {
        if (!canAct()) return
        if (mode == Mode.BT_GUEST) { link.send(Proto.PASS); return }
        act(me) { it.pass(me) }
    }

    fun canAct(): Boolean {
        val r = round ?: return false
        if (r.isOver || shuffling || r.current != me) return false
        if (mode == Mode.LOCAL && !revealed) return false
        return true
    }

    /** Aplica uma ação na rodada (aparelho com autoridade) e anuncia o resultado. */
    private inline fun act(player: Int, action: (Round) -> Boolean) {
        val r = round ?: return
        if (r.current != player) return
        val before = Snap(r)
        if (!action(r)) return
        afterChange(before, r)
        broadcast()
        maybeCpu()
    }

    private fun maybeCpu() {
        val r = round ?: return
        if (mode != Mode.CPU || r.isOver || r.current != 1 || shuffling) return
        if (cpuJob?.isActive == true) return
        cpuJob = viewModelScope.launch {
            delay(1300)
            while (!r.isOver && r.current == 1) {
                val m = Ai.choose(r, 1)
                when {
                    m != null -> { act(1) { it.play(1, m.tile, m.side) }; break }
                    r.canDraw(1) -> { act(1) { it.draw(1) != null }; delay(650) }
                    else -> { act(1) { it.pass(1) }; break }
                }
            }
        }
    }

    // =================== Anúncios (som + voz) ===================
    private class Snap(r: Round) {
        val board = r.board.size
        val h0 = r.hands[0].size
        val h1 = r.hands[1].size
        val passes = r.consecutivePasses
        val current = r.current
        val over = r.isOver
        fun hand(p: Int) = if (p == 0) h0 else h1
    }

    private fun afterChange(before: Snap, r: Round) {
        val turnChanged = before.current != r.current
        if (mode == Mode.LOCAL && (turnChanged || r.isOver)) revealed = false
        bump()

        val sb = StringBuilder()
        if (r.board.size > before.board) {
            sfx.playTile()
            val m = r.lastMove!!
            val who = if (m.player == me && mode != Mode.LOCAL) "Você jogou" else "${spokenName(m.player)} jogou"
            sb.append("$who ${Words.tile(m.tile)}. ")
            if (!r.isOver) sb.append("Ponta esquerda ${Words.n(r.leftEnd!!)}, ponta direita ${Words.n(r.rightEnd!!)}. ")
        }
        for (p in 0..1) if (r.hands[p].size > before.hand(p)) {
            sfx.playDraw()
            if (p == me && mode != Mode.LOCAL) sb.append("Você comprou ${Words.tile(r.hands[p].last())}. ")
            else sb.append("${spokenName(p)} comprou. ")
        }
        if (r.consecutivePasses > before.passes) sb.append("${spokenName(before.current)} passou. ")
        say(sb.toString(), flush = true)

        if (r.isOver && !before.over) announceResult(r)
        else if (turnChanged) announceTurn(r, newRound = false)
    }

    private fun announceTurn(r: Round, newRound: Boolean) {
        val p = r.current
        val text = when (mode) {
            Mode.LOCAL -> "${spokenName(p)}, agora sua vez"
            Mode.CPU -> if (p == 0) "Sua vez" else if (newRound) "Computador começa" else ""
            else -> if (p == me) "Sua vez" else if (newRound) "${nameOf(p)} começa" else ""
        }
        val mine = mode == Mode.LOCAL || p == me
        if (mine && mode == Mode.LOCAL) {
            say(text) // a dica de compra só é dita depois de mostrar as pedras
        } else say(text + if (mine) "." + hintFor(r, p) else "")
    }

    private fun announceResult(r: Round) {
        val res = r.result!!
        if (res.winner >= 0 && (mode != Mode.BT_GUEST)) scores[res.winner] += res.points
        val how = if (res.reason == EndReason.BATIDA) "bateu" else "venceu no jogo trancado"
        val text = when {
            res.winner < 0 -> "Jogo trancado. Empate."
            mode == Mode.CPU && res.winner == 0 -> "Você $how! Ganhou ${res.points} pontos."
            mode != Mode.LOCAL && res.winner == me -> "Você $how! Ganhou ${res.points} pontos."
            else -> "${spokenName(res.winner)} $how. ${res.points} pontos."
        }
        say(text)
        bump()
    }

    /** Botão "Ouvir mesa": mesa, pontas e quantas pedras o adversário tem. */
    fun describe() {
        val r = round ?: return
        val opp = 1 - me
        val sb = StringBuilder()
        if (r.board.isNotEmpty()) sb.append("Ponta esquerda ${Words.n(r.leftEnd!!)}, ponta direita ${Words.n(r.rightEnd!!)}. ")
        sb.append("${spokenName(opp)} tem ${pedras(r.hands[opp].size)}. ")
        sb.append(if (r.board.isEmpty()) "Mesa vazia. " else "Mesa com ${pedras(r.board.size)}. ")
        // O monte só é informado depois que alguém comprar a primeira pedra.
        if (r.boneyard.size < 14) sb.append("Monte tem ${pedras(r.boneyard.size)}.")
        say(sb.toString(), flush = true)
    }

    fun boardSummary(r: Round): String =
        if (r.board.isEmpty()) "Mesa vazia"
        else "Mesa com ${pedras(r.board.size)}, ponta esquerda ${Words.n(r.leftEnd!!)}, ponta direita ${Words.n(r.rightEnd!!)}"

    fun pedras(n: Int) = if (n == 1) "1 pedra" else "$n pedras"

    private fun bump() { version++ }

    /** Última mensagem anunciada (mostrada na linha de status, como no modelo). */
    var status by mutableStateOf(""); private set

    private fun say(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        status = text.trim()
        voice.speak(text, flush)
    }

    /** Toque numa pedra da mão: joga se só houver um lado, avisa se não encaixa. */
    fun tapTile(t: Tile): List<Side> {
        if (!canAct()) {
            if (round?.isOver == false && round?.current != me) say("Aguarde, não é sua vez.", flush = true)
            return emptyList()
        }
        val sides = sidesFor(t)
        when (sides.size) {
            0 -> say("Essa pedra não encaixa!", flush = true)
            1 -> play(t, sides[0])
        }
        return sides
    }

    private fun hintFor(r: Round, p: Int): String = when {
        r.isOver || r.board.isEmpty() || r.playable(p).isNotEmpty() -> ""
        r.boneyard.isNotEmpty() -> " Você não tem pedras que encaixam. Toque no botão comprar."
        else -> " Monte vazio. Toque no botão passar vez."
    }

    // =================== Bluetooth ===================
    fun hostRoom() { mode = Mode.BT_HOST; lobbyMessage = null; link.host() }
    fun joinMode() { mode = Mode.BT_GUEST; lobbyMessage = null }

    fun invite() {
        waitingAccept = true
        link.send(Proto.INVITE)
        say("Convite enviado. Aguardando ${friendName.ifBlank { "amigo" }} aceitar.", flush = true)
    }

    fun answerInvite(accept: Boolean) {
        inviteReceived = false
        link.send(if (accept) Proto.ACCEPT else Proto.DECLINE)
    }

    private fun broadcast() {
        if (mode != Mode.BT_HOST) return
        val r = round ?: return
        link.send("${Proto.STATE}|${scores[0]}|${scores[1]}|${if (shuffling) 1 else 0}|${r.encode()}")
    }

    private fun onMessage(line: String) {
        val parts = line.split('|')
        when (parts[0]) {
            Proto.HELLO -> {
                friendName = parts.getOrNull(1).orEmpty().ifBlank { "Amigo" }
                say("$friendName entrou na sala")
            }
            Proto.INVITE -> {
                inviteReceived = true
                say("$friendName quer começar o jogo. Aceitar?", flush = true)
            }
            Proto.ACCEPT -> if (mode == Mode.BT_HOST) {
                waitingAccept = false
                newMatch()
            }
            Proto.DECLINE -> {
                waitingAccept = false
                lobbyMessage = "$friendName recusou o convite"
                say(lobbyMessage!!, flush = true)
            }
            Proto.PLAY -> if (mode == Mode.BT_HOST) {
                val t = Tile.byId(parts[1].toInt()); val s = Side.entries[parts[2].toInt()]
                act(1) { it.play(1, t, s) }
            }
            Proto.DRAW -> if (mode == Mode.BT_HOST) act(1) { it.draw(1) != null }
            Proto.PASS -> if (mode == Mode.BT_HOST) act(1) { it.pass(1) }
            Proto.NEXT -> if (mode == Mode.BT_HOST) nextRound()
            Proto.STATE -> if (mode == Mode.BT_GUEST) onState(parts)
            Proto.BYE -> {
                lobbyMessage = "$friendName saiu do jogo"
                say(lobbyMessage!!, flush = true)
                link.disconnect()
                friendName = ""
                if (screen == Screen.GAME) { round = null; screen = Screen.BLUETOOTH }
            }
        }
    }

    private fun onState(parts: List<String>) {
        scores[0] = parts[1].toInt(); scores[1] = parts[2].toInt()
        val shuffle = parts[3] == "1"
        val newR = Round.decode(parts.subList(4, parts.size).joinToString("|"))
        val old = round
        round = newR
        if (screen != Screen.GAME) screen = Screen.GAME
        if (shuffle) {
            shuffling = true
            sfx.playShuffle()
            say("Embaralhando as pedras", flush = true)
            bump()
            viewModelScope.launch {
                delay(1700); shuffling = false
                announceTurn(newR, newRound = true); bump()
            }
            return
        }
        if (old == null || old.board.size > newR.board.size) { shuffling = false; bump(); return }
        afterChange(Snap(old), newR)
    }

    override fun onCleared() {
        cpuJob?.cancel()
        link.disconnect()
        voice.shutdown()
        sfx.release()
    }
}
