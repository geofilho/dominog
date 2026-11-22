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
import com.geofilho.dominog.game.Level
import com.geofilho.dominog.game.Round
import com.geofilho.dominog.game.Side
import com.geofilho.dominog.game.Tile
import com.geofilho.dominog.game.Words
import com.geofilho.dominog.profile.Avatar
import com.geofilho.dominog.speech.Voice
import com.geofilho.dominog.update.UpdateState
import com.geofilho.dominog.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { MENU, BLUETOOTH, GAME, HELP, DIAGNOSTIC }

/** Rodadas vencidas para ganhar a partida (melhor de 5). */
const val WINS_TO_MATCH = 3
enum class Mode { CPU, LOCAL, BT_HOST, BT_GUEST }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dominog", Context.MODE_PRIVATE)
    val voice = Voice(app)
    val history = History(prefs)
    val updater = Updater(app)

    /** Chave "Silenciar sons do jogo" (Diagnóstico): cala os efeitos, mantém as falas. */
    var soundsMuted by mutableStateOf(prefs.getBoolean("sons_mudos", false)); private set

    fun changeSoundsMuted(m: Boolean) {
        soundsMuted = m
        sfx.muted = m
        prefs.edit().putBoolean("sons_mudos", m).apply()
        say(if (m) "Sons do jogo silenciados" else "Sons do jogo ativados", flush = true)
    }
    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle); private set
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
    var myPhoto by mutableStateOf(Avatar.load(app)); private set
    var friendPhoto by mutableStateOf<android.graphics.Bitmap?>(null); private set

    fun setPhoto(b: android.graphics.Bitmap?) {
        val app = getApplication<Application>()
        if (b == null) { Avatar.delete(app); myPhoto = null; say("Foto removida", flush = true); return }
        myPhoto = Avatar.save(app, b)
        say("Foto salva", flush = true)
    }

    fun photoFromGallery(uri: android.net.Uri) {
        viewModelScope.launch {
            val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Avatar.decode(getApplication(), uri) }
            if (b != null) setPhoto(b) else say("Não foi possível abrir a foto", flush = true)
        }
    }
    var inviteReceived by mutableStateOf(false); private set
    var waitingAccept by mutableStateOf(false); private set
    var lobbyMessage by mutableStateOf<String?>(null)

    init {
        sfx.muted = soundsMuted
        viewModelScope.launch { link.incoming.collect(::onMessage) }
        viewModelScope.launch {
            link.state.collect { st ->
                when (st) {
                    LinkState.Connected -> {
                        // O papel vem da própria conexão: quem recebeu é o anfitrião (comanda o jogo).
                        mode = if (link.isHost) Mode.BT_HOST else Mode.BT_GUEST
                        link.stopScan()
                        link.send("${Proto.HELLO}|$playerName")
                        myPhoto?.let { link.send("${Proto.PHOTO}|${Avatar.encode(it)}") }
                        say("Conectado", flush = true)
                    }
                    is LinkState.Failed -> if (mode == Mode.BT_HOST || mode == Mode.BT_GUEST) {
                        friendName = ""; friendPhoto = null; inviteReceived = false; waitingAccept = false
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
    /** Nível do computador escolhido no menu. */
    var level by mutableStateOf(Level.MEDIO); private set

    fun startCpu(l: Level) { level = l; mode = Mode.CPU; newMatch() }
    fun startLocal() { mode = Mode.LOCAL; newMatch() }
    fun openBluetooth() { screen = Screen.BLUETOOTH; lobbyMessage = null }
    fun openHelp() { screen = Screen.HELP }
    fun openDiagnostic() { screen = Screen.DIAGNOSTIC }

    fun backToMenu() {
        cpuJob?.cancel()
        voice.stop()
        if (mode == Mode.BT_HOST || mode == Mode.BT_GUEST || screen == Screen.BLUETOOTH) {
            if (link.state.value == LinkState.Connected) link.send(Proto.BYE)
            link.disconnect()
            friendName = ""; friendPhoto = null; inviteReceived = false; waitingAccept = false
        }
        round = null
        screen = Screen.MENU
    }

    fun saveName(name: String) {
        playerName = name.replace('|', ' ').trim().take(20)
            .split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        prefs.edit().putString("name", playerName).apply()
    }

    // =================== Partida ===================
    private fun newMatch() {
        scores.fill(0)
        round = null
        // Parceiro no mesmo aparelho: o Jogador 1 sempre começa (com as pedras ocultas até
        // tocar em Mostrar pedras). Nos outros modos, quem começa é sorteado.
        if (mode == Mode.LOCAL) dealRound(0) else dealRound(null)
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
        when {
            mode == Mode.BT_GUEST -> link.send(Proto.NEXT)
            matchOver -> newMatch() // fim da melhor de 5: começa outra partida
            else -> {
                val res = r.result!!
                val starter = if (res.winner >= 0) res.winner else 1 - r.current
                dealRound(starter)
            }
        }
    }

    // ---------- Ações do jogador neste aparelho ----------
    fun reveal() {
        revealed = true
        val r = round
        voice.stop()
        sfx.playOpen()
        bump()
        val hint = if (r != null) hintFor(r, r.current) else ""
        status = "Mostrando pedras.$hint"
        // A voz entra logo depois do som de abrir.
        viewModelScope.launch {
            delay(550)
            if (partnerVoiceOn) voice.speak("Mostrando pedras.", flush = true)
            if (hint.isNotEmpty()) focusActionAfterSpeech()
        }
    }

    /**
     * Avisos do modo parceiro ("Mostrando pedras", "Passe para o jogador"): falam mesmo sem leitor de tela,
     * a não ser que os sons do jogo estejam silenciados (aí só falam para quem usa leitor de tela).
     */
    private val partnerVoiceOn get() = screenReaderOn || !soundsMuted

    /** Informado pela tela: leitor de tela (TalkBack) ligado. */
    var screenReaderOn = false

    /** Incrementa quando o foco do TalkBack deve ir para o aviso "Passe para o jogador…". */
    var curtainFocus by mutableIntStateOf(0); private set

    fun sidesFor(t: Tile): List<Side> = round?.sidesFor(t) ?: emptyList()

    fun play(t: Tile, side: Side) {
        if (!canAct()) return
        if (mode == Mode.BT_GUEST) { link.send("${Proto.PLAY}|${t.id}|${side.ordinal}"); return }
        act(me) { it.play(me, t, side) }
    }

    fun draw() {
        askDraw = false
        if (!canAct()) return
        if (mode == Mode.BT_GUEST) { link.send(Proto.DRAW); return }
        act(me) { it.draw(me) != null }
    }

    fun pass() {
        askDraw = false
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
                val m = Ai.choose(r, 1, level)
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
        // Comprou e continua sem pedra que encaixe: avisa de novo e leva o foco ao botão.
        val myTurnNow = !r.isOver && r.current == me && (mode != Mode.LOCAL || revealed)
        val hint = if (!turnChanged && myTurnNow) hintFor(r, me) else ""
        say(sb.toString(), flush = true)
        if (hint.isNotEmpty()) { status = (status + hint).trim(); focusActionAfterSpeech() }

        if (r.isOver && !before.over) announceResult(r)
        else if (turnChanged) announceTurn(r, newRound = false)
    }

    private fun announceTurn(r: Round, newRound: Boolean) {
        val p = r.current
        val text = when (mode) {
            Mode.LOCAL -> ""
            Mode.CPU -> if (p == 0) "Sua vez" else if (newRound) "Computador começa" else ""
            else -> if (p == me) "Sua vez" else if (newRound) "${nameOf(p)} começa" else ""
        }
        if (mode == Mode.LOCAL) {
            val pass = if (newRound) "${spokenName(p)} começa" else "Passe para o ${spokenName(p).lowercase()}"
            status = pass
            // Depois das informações da jogada: bipes (1 = Jogador 1, 2 = Jogador 2) e o aviso.
            // Com TalkBack, o próprio leitor fala o aviso quando o foco chega nele;
            // sem TalkBack, fala a voz do app.
            voice.afterQueue {
                sfx.playBeeps(p + 1)
                viewModelScope.launch {
                    delay(if (p == 0) 250 else 480)
                    if (screenReaderOn) curtainFocus++ else if (partnerVoiceOn) voice.speak(pass)
                }
            }
            return
        }
        val mine = p == me
        val hint = if (mine) hintFor(r, p) else ""
        say(text + if (mine) "." else "")
        if (hint.isNotEmpty()) { status = (status + hint).trim(); focusActionAfterSpeech() }
    }

    /** Partida = melhor de 5: vence quem ganhar [WINS_TO_MATCH] rodadas primeiro. */
    val matchOver: Boolean get() = scores.any { it >= WINS_TO_MATCH }
    val matchWinner: Int get() = scores.indexOfFirst { it >= WINS_TO_MATCH }

    private fun announceResult(r: Round) {
        val res = r.result!!
        // No convidado do Bluetooth o placar já chega pronto do anfitrião.
        if (res.winner >= 0 && mode != Mode.BT_GUEST) scores[res.winner]++
        if (matchOver) recordMatch()
        if (res.winner >= 0) sfx.playApplause()
        val youWon = res.winner >= 0 && mode != Mode.LOCAL && res.winner == me
        val how = if (res.reason == EndReason.BATIDA) "bateu" else "venceu no jogo trancado"
        val sb = StringBuilder()
        sb.append(
            when {
                res.winner < 0 -> "Jogo trancado. Empate, a rodada não conta. "
                youWon -> "Você $how! "
                else -> "${spokenName(res.winner)} $how. "
            },
        )
        val hi = maxOf(scores[0], scores[1]); val lo = minOf(scores[0], scores[1])
        if (matchOver) {
            val w = matchWinner
            sb.append(if (mode != Mode.LOCAL && w == me) "Você venceu a partida por $hi a $lo! " else "${spokenName(w)} venceu a partida por $hi a $lo. ")
        }
        // Sempre termina com o placar.
        sb.append("${if (matchOver) "Placar final" else "Placar"}: ${placarText()}.")
        say(sb.toString())
        bump()
    }

    private fun recordMatch() {
        val w = matchWinner
        when (mode) {
            Mode.CPU -> history.recordCpu(level, won = w == 0)
            Mode.LOCAL -> history.recordLocal(w)
            Mode.BT_HOST, Mode.BT_GUEST -> history.recordBt(friendName.ifBlank { "Amigo" }, won = w == me)
        }
    }

    /** Texto do histórico do modo atual (tocar no placar). */
    fun historyText(): String = when (mode) {
        Mode.CPU -> history.cpuText()
        Mode.LOCAL -> history.localText()
        else -> history.btText(friendName.ifBlank { null })
    }

    fun speakHistory() = say(historyText(), flush = true)

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

    // =================== Atualização ===================
    fun checkUpdate() {
        updateState = UpdateState.Checking
        say("Verificando atualização", flush = true)
        viewModelScope.launch {
            val latest = updater.latestVersion()
            updateState = when {
                latest == null -> UpdateState.Failed("Não foi possível verificar. Confira a internet e tente de novo.")
                latest > updater.installedVersion -> UpdateState.Available(latest, updater.latestNotes())
                else -> UpdateState.UpToDate
            }
            say(
                when (val s = updateState) {
                    is UpdateState.Available -> "Nova versão 1.0.${s.version} disponível."
                    UpdateState.UpToDate -> "Você já tem a versão mais recente."
                    is UpdateState.Failed -> s.message
                    else -> ""
                },
                flush = true,
            )
        }
    }

    fun downloadUpdate() {
        updateState = UpdateState.Downloading(0)
        say("Baixando atualização", flush = true)
        viewModelScope.launch {
            try {
                val file = updater.download { updateState = UpdateState.Downloading(it) }
                installUpdate(file)
            } catch (e: Exception) {
                updateState = UpdateState.Failed("Falha ao baixar. Confira a internet e tente de novo.")
                say((updateState as UpdateState.Failed).message, flush = true)
            }
        }
    }

    fun installUpdate(file: java.io.File) {
        if (!updater.canInstall()) {
            updateState = UpdateState.NeedPermission(file)
            say("Para instalar, permita que o Dominó G instale aplicativos. Ative a opção e volte, que a instalação continua sozinha.", flush = true)
            return
        }
        updateState = UpdateState.Idle
        say("Abrindo o instalador", flush = true)
        updater.install(file)
    }

    fun openInstallPermission() = updater.openInstallPermissionSettings()

    /** Ao voltar das configurações: se a permissão foi dada, a instalação continua sozinha. */
    fun resumeInstallIfAllowed() {
        val s = updateState
        if (s is UpdateState.NeedPermission && updater.canInstall()) installUpdate(s.file)
    }
    fun dismissUpdate() { updateState = UpdateState.Idle }

    /** Última mensagem anunciada (mostrada na linha de status, como no modelo). */
    var status by mutableStateOf(""); private set

    /**
     * Avisos falados: só com leitor de tela ligado. Sem leitor, aparecem só na linha de status.
     * (No modo parceiro, "Mostrando pedras" e "Passe para o jogador" falam sempre; eles usam voice.speak direto.)
     */
    private fun say(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        status = text.trim()
        if (screenReaderOn) voice.speak(text, flush)
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

    /** "Você 2, Computador 1" (falado/mostrado no fim da rodada). */
    fun placarText(): String = "${spokenName(0)} ${scores[0]}, ${spokenName(1)} ${scores[1]}"

    /** Incrementa quando o foco do TalkBack deve ir para o botão Comprar pedra / Passar vez. */
    var actionFocus by mutableIntStateOf(0); private set

    /**
     * Sem pedra que encaixe: depois que a voz terminar, com leitor de tela abre o diálogo de comprar/passar
     * (o foco do TalkBack vai direto para o botão); sem leitor, os botões ficam na tela.
     */
    var askDraw by mutableStateOf(false); private set

    private fun focusActionAfterSpeech() = voice.afterQueue {
        actionFocus++
        if (screenReaderOn) askDraw = true
    }

    fun dismissDraw() { askDraw = false }

    private fun hintFor(r: Round, p: Int): String = when {
        r.isOver || r.board.isEmpty() || r.playable(p).isNotEmpty() -> ""
        r.boneyard.isNotEmpty() -> " Você não tem pedras que encaixam. Toque no botão comprar."
        else -> " Monte vazio. Toque no botão passar vez."
    }

    // =================== Bluetooth ===================
    /** Precisa pedir ao Android para deixar o celular visível? (não pede de novo enquanto ainda vale) */
    fun needsDiscoverable(): Boolean =
        !link.isDiscoverable && System.currentTimeMillis() > prefs.getLong("visivel_ate", 0L)

    /** Resultado do pedido de visibilidade: [seconds] > 0 quando a pessoa aceitou. */
    fun discoverableResult(seconds: Int) {
        if (seconds > 0) prefs.edit().putLong("visivel_ate", System.currentTimeMillis() + seconds * 1000L - 60_000L).apply()
    }

    /** A tela do Bluetooth já é uma sala: fica aguardando conexão (anfitrião). */
    fun hostRoom() { mode = Mode.BT_HOST; link.host() }

    /** Desconecta do amigo e volta a deixar a sala aberta. */
    fun leaveRoom() {
        if (link.state.value == LinkState.Connected) link.send(Proto.BYE)
        link.disconnect()
        friendName = ""; friendPhoto = null; inviteReceived = false; waitingAccept = false
    }
    fun joinMode() { mode = Mode.BT_GUEST; lobbyMessage = null }

    fun invite() {
        waitingAccept = true
        link.send(Proto.INVITE)
        say("Convite enviado. Aguardando ${friendName.ifBlank { "amigo" }} aceitar.", flush = true)
    }

    fun answerInvite(accept: Boolean) {
        inviteReceived = false
        link.send(if (accept) Proto.ACCEPT else Proto.DECLINE)
        // Quem recebeu a conexão (anfitrião) comanda o jogo: se ele aceitou, já distribui.
        if (accept && mode == Mode.BT_HOST) newMatch()
    }

    private fun broadcast() {
        if (mode != Mode.BT_HOST) return
        val r = round ?: return
        link.send("${Proto.STATE}|${scores[0]}|${scores[1]}|${if (shuffling) 1 else 0}|${r.encode()}")
    }

    private fun onMessage(line: String) {
        val parts = line.split('|')
        when (parts[0]) {
            Proto.PHOTO -> friendPhoto = parts.getOrNull(1)?.let(Avatar::decodeBase64)
            Proto.HELLO -> {
                friendName = parts.getOrNull(1).orEmpty().ifBlank { "Amigo" }
                say("$friendName entrou na sala")
            }
            Proto.INVITE -> {
                inviteReceived = true
                say("$friendName quer começar o jogo. Aceitar?", flush = true)
            }
            Proto.ACCEPT -> {
                waitingAccept = false
                // Se o convidado convidou e o anfitrião aceitou, o estado chega em seguida.
                if (mode == Mode.BT_HOST) newMatch()
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
                friendName = ""; friendPhoto = null
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
