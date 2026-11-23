package com.geofilho.dominog.online

import android.content.SharedPreferences
import com.geofilho.dominog.bt.LinkEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface OnlineState {
    /** Fora de sala. */
    data object Idle : OnlineState
    /** Criando ou entrando numa sala. */
    data class Working(val message: String) : OnlineState
    /** Sala criada por este aparelho, com o código para os amigos. */
    data class Hosting(val code: String) : OnlineState
    /** Dentro da sala de um amigo. */
    data class Joined(val code: String) : OnlineState
}

/**
 * Jogo pela internet, usando o Realtime Database do Firebase (plano gratuito) como "correio".
 *
 * Funciona como o [com.geofilho.dominog.bt.BtLink]: cada amigo é uma conexão (com um número) e as
 * linhas de texto do jogo passam por ela; a lógica da sala é a mesma do Bluetooth (BtSession).
 * Quem cria a sala comanda o jogo; quem entra conversa só com quem criou.
 *
 * No banco, cada sala fica em /salas/{código}: "host" diz quem criou, e "caixa/{id}" é a caixa de
 * entrada de cada aparelho (mensagens {de, l}). Cada aparelho escuta a própria caixa (streaming do
 * Firebase) e apaga o que já leu. A cada 15 s os aparelhos mandam um "sinal de vida"; quem some por
 * 50 s é dado como desconectado.
 *
 * O aparelho entra no Firebase como usuário anônimo (sem conta nem senha).
 */
class OnlineLink(private val prefs: SharedPreferences) {
    /** Id deste aparelho (o mesmo da sala do Bluetooth). */
    var myId: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<OnlineState>(OnlineState.Idle)
    val state: StateFlow<OnlineState> = _state
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<LinkEvent> = _events
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    @Volatile private var code: String? = null
    @Volatile private var isHost = false
    /** Amigos: id do aparelho -> número da conexão (e o contrário). */
    private val linkOf = ConcurrentHashMap<String, Int>()
    private val peerOf = ConcurrentHashMap<Int, String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val ids = AtomicInteger(ID_BASE)
    private val done = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var jobs = mutableListOf<Job>()

    /** Envios em ordem (um de cada vez), para as mensagens chegarem na ordem certa. */
    private val outbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (task in outbox) runCatching { task() } }
    }

    fun isLink(id: Int) = id >= ID_BASE

    // ================= Sala =================

    /** Cria uma sala nova, com um código de 4 números. */
    fun create() {
        if (_state.value != OnlineState.Idle) return
        _state.value = OnlineState.Working("Criando a sala…")
        scope.launch {
            try {
                ensureAuth()
                var chosen: String? = null
                for (i in 0 until 15) {
                    val c = (1000..9999).random().toString()
                    val host = getJson("/salas/$c/host")
                    if (host == null || isStale(host)) { chosen = c; break }
                }
                val c = chosen ?: throw Exception("Não foi possível criar a sala. Tente de novo.")
                put("/salas/$c", JSONObject().put("host", JSONObject().put("id", myId).put("t", serverTime())))
                code = c; isHost = true
                startRoomJobs(c)
                _state.value = OnlineState.Hosting(c)
            } catch (e: Exception) {
                fail(e.message ?: "Não foi possível criar a sala. Confira a internet.")
            }
        }
    }

    /** Entra na sala do amigo pelo código. */
    fun join(roomCode: String) {
        if (_state.value != OnlineState.Idle) return
        val c = roomCode.filter { it.isDigit() }
        if (c.length !in 4..6) { _errors.tryEmit("O código da sala tem 4 números."); return }
        _state.value = OnlineState.Working("Entrando na sala $c…")
        scope.launch {
            try {
                ensureAuth()
                val host = getJson("/salas/$c/host")
                if (host == null || isStale(host)) throw Exception("Sala $c não encontrada. Confira o código com quem criou.")
                val hostId = host.optString("id")
                if (hostId.isEmpty() || hostId == myId) throw Exception("Sala $c não encontrada. Confira o código com quem criou.")
                code = c; isHost = false
                startRoomJobs(c)
                val id = ids.incrementAndGet()
                linkOf[hostId] = id; peerOf[id] = hostId; lastSeen[hostId] = now()
                sendRaw(hostId, OPEN)
                _state.value = OnlineState.Joined(c)
                _events.emit(LinkEvent.Opened(id, accepted = false, peerName = "Amigo"))
            } catch (e: Exception) {
                fail(e.message ?: "Não foi possível entrar na sala. Confira a internet.")
            }
        }
    }

    fun send(linkId: Int, line: String) {
        val peer = peerOf[linkId] ?: return
        sendRaw(peer, line)
    }

    /** Fecha a conexão com um amigo (ele recebe o aviso). */
    fun close(linkId: Int) {
        val peer = peerOf.remove(linkId) ?: return
        linkOf.remove(peer); lastSeen.remove(peer)
        sendRaw(peer, CLOSE)
        _events.tryEmit(LinkEvent.Closed(linkId))
        // Quem entrou e perdeu a ligação com quem criou: a sala acabou para ele.
        if (!isHost) leaveQuietly()
    }

    /** Sai da sala: avisa os amigos e, se criou a sala, apaga ela do banco. */
    fun closeAll() {
        val c = code ?: run { _state.value = OnlineState.Idle; return }
        val host = isHost
        peerOf.keys.toList().forEach { id ->
            val peer = peerOf.remove(id) ?: return@forEach
            linkOf.remove(peer); lastSeen.remove(peer)
            sendRaw(peer, CLOSE)
            _events.tryEmit(LinkEvent.Closed(id))
        }
        if (host) outbox.trySend { delete("/salas/$c") }
        else outbox.trySend { delete("/salas/$c/caixa/$myId") }
        stopRoom()
    }

    private fun leaveQuietly() {
        val c = code
        if (c != null) outbox.trySend { delete("/salas/$c/caixa/$myId") }
        stopRoom()
    }

    private fun stopRoom() {
        jobs.forEach { it.cancel() }; jobs = mutableListOf()
        code = null; isHost = false
        done.clear()
        _state.value = OnlineState.Idle
    }

    private fun fail(msg: String) {
        stopRoom()
        _errors.tryEmit(msg)
    }

    // ================= Escuta, sinal de vida e vigia =================

    private fun startRoomJobs(c: String) {
        jobs += scope.launch { listenLoop(c) }
        jobs += scope.launch {
            while (isActive) {
                delay(PING_MS)
                linkOf.keys.forEach { sendRaw(it, PING) }
                if (isHost) outbox.trySend { put("/salas/$c/host/t", serverTime()) }
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(5000)
                val t = now()
                lastSeen.entries.filter { t - it.value > TIMEOUT_MS }.forEach { (peer, _) ->
                    val id = linkOf.remove(peer) ?: return@forEach
                    peerOf.remove(id); lastSeen.remove(peer)
                    _events.tryEmit(LinkEvent.Closed(id))
                    if (!isHost) {
                        _errors.tryEmit("A conexão com a sala caiu.")
                        leaveQuietly()
                    }
                }
            }
        }
    }

    private suspend fun listenLoop(c: String) {
        var wait = 1000L
        while (scope.isActive && code == c) {
            try {
                val token = ensureAuth()
                val conn = (URL("$DB/salas/$c/caixa/$myId.json?auth=$token").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = 15000; readTimeout = 70000 // o Firebase manda "keep-alive" a cada 30 s
                    instanceFollowRedirects = true
                }
                if (conn.responseCode != 200) throw Exception("escuta ${conn.responseCode}")
                wait = 1000L
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    var event = ""
                    while (code == c) {
                        val line = r.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") -> {
                                val data = line.substringAfter(':').trim()
                                when (event) {
                                    "put", "patch" -> onData(c, JSONObject(data))
                                    "auth_revoked" -> { tokenExpires = 0; break }
                                    "cancel" -> throw Exception("escuta cancelada")
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                if (code != c) return
                delay(wait)
                wait = (wait * 2).coerceAtMost(15000)
            }
        }
    }

    /** Mensagens novas na minha caixa: entrega em ordem e apaga do banco. */
    private suspend fun onData(c: String, ev: JSONObject) {
        val path = ev.optString("path")
        val data = ev.opt("data")
        val msgs = ArrayList<Pair<String, JSONObject>>()
        if (path == "/" && data is JSONObject) {
            data.keys().forEach { k -> (data.opt(k) as? JSONObject)?.let { msgs += k to it } }
        } else if (path.count { it == '/' } == 1 && data is JSONObject) {
            msgs += path.removePrefix("/") to data
        }
        if (msgs.isEmpty()) return
        msgs.sortBy { it.first }
        val clear = JSONObject()
        for ((key, m) in msgs) {
            clear.put(key, JSONObject.NULL)
            if (!done.add(key)) continue
            handle(m.optString("de"), m.optString("l"))
        }
        if (done.size > 2000) done.clear()
        outbox.trySend { patch("/salas/$c/caixa/$myId", clear) }
    }

    private suspend fun handle(from: String, line: String) {
        if (from.isEmpty()) return
        lastSeen.computeIfPresent(from) { _, _ -> now() }
        when (line) {
            OPEN -> if (isHost) {
                // Amigo entrando (ou voltando): conexão nova.
                linkOf.remove(from)?.let { old -> peerOf.remove(old); _events.emit(LinkEvent.Closed(old)) }
                val id = ids.incrementAndGet()
                linkOf[from] = id; peerOf[id] = from; lastSeen[from] = now()
                _events.emit(LinkEvent.Opened(id, accepted = true, peerName = "Amigo"))
            }
            CLOSE -> {
                val id = linkOf.remove(from) ?: return
                peerOf.remove(id); lastSeen.remove(from)
                _events.emit(LinkEvent.Closed(id))
                if (!isHost) {
                    _errors.tryEmit("Quem criou a sala saiu.")
                    leaveQuietly()
                }
            }
            PING -> {}
            else -> linkOf[from]?.let { _events.emit(LinkEvent.Line(it, line)) }
        }
    }

    private fun sendRaw(to: String, line: String) {
        val c = code ?: return
        val body = JSONObject().put("de", myId).put("l", line)
        outbox.trySend { post("/salas/$c/caixa/$to", body) }
    }

    // ================= Login anônimo =================

    @Volatile private var idToken: String? = null
    @Volatile private var tokenExpires = 0L

    /** Devolve um token válido (entra como anônimo na primeira vez; renova quando vence). */
    private fun ensureAuth(): String {
        idToken?.let { if (now() < tokenExpires) return it }
        synchronized(this) {
            idToken?.let { if (now() < tokenExpires) return it }
            val refresh = prefs.getString(PREF_REFRESH, null)
            val json = if (refresh != null) {
                runCatching {
                    request(
                        "POST", "https://securetoken.googleapis.com/v1/token?key=$API_KEY",
                        "grant_type=refresh_token&refresh_token=$refresh", "application/x-www-form-urlencoded",
                    )
                }.getOrNull()?.let { JSONObject(it) }?.let {
                    JSONObject().put("idToken", it.getString("id_token"))
                        .put("refreshToken", it.getString("refresh_token"))
                        .put("expiresIn", it.optString("expires_in", "3600"))
                }
            } else null
            val ok = json ?: JSONObject(
                request(
                    "POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY",
                    "{\"returnSecureToken\":true}", "application/json",
                ) ?: throw Exception("Sem conexão com a internet."),
            )
            val token = ok.getString("idToken")
            prefs.edit().putString(PREF_REFRESH, ok.getString("refreshToken")).apply()
            idToken = token
            tokenExpires = now() + (ok.optString("expiresIn", "3600").toLongOrNull() ?: 3600L) * 1000L - 120_000L
            return token
        }
    }

    // ================= Banco (REST) =================

    private fun getJson(path: String): JSONObject? {
        val text = request("GET", "$DB$path.json?auth=${ensureAuth()}") ?: throw Exception("Sem conexão com a internet.")
        return if (text.trim() == "null") null else runCatching { JSONObject(text) }.getOrNull()
    }

    private fun put(path: String, body: Any) { request("PUT", "$DB$path.json?auth=${ensureAuth()}", body.toString()) }
    private fun post(path: String, body: JSONObject) { request("POST", "$DB$path.json?auth=${ensureAuth()}", body.toString()) }
    private fun patch(path: String, body: JSONObject) { request("PATCH", "$DB$path.json?auth=${ensureAuth()}", body.toString()) }
    private fun delete(path: String) { request("DELETE", "$DB$path.json?auth=${ensureAuth()}") }

    /** Requisição HTTP simples; devolve o corpo da resposta, ou null se falhar. */
    private fun request(method: String, url: String, body: String? = null, type: String = "application/json"): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            // O HttpURLConnection não conhece PATCH: o Firebase aceita POST com este cabeçalho.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else conn.requestMethod = method
            conn.connectTimeout = 15000; conn.readTimeout = 20000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "$type; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (code == 401) tokenExpires = 0
            return if (code in 200..299) text else null
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun serverTime() = JSONObject().put(".sv", "timestamp")
    private fun isStale(host: JSONObject): Boolean {
        val t = host.optLong("t", 0L)
        return t == 0L || System.currentTimeMillis() - t > STALE_MS
    }
    private fun now() = System.currentTimeMillis()

    companion object {
        /** Banco e chave do projeto "dominog" no Firebase (não são senhas: quem protege o banco são as regras). */
        const val DB = "https://dominog-default-rtdb.firebaseio.com"
        const val API_KEY = "AIzaSyDDWX-FTAsEQtXbb75HAkWAht4OWTqjgM4"
        /** Números das conexões online começam aqui (os do Bluetooth são menores). */
        const val ID_BASE = 100_000
        private const val PREF_REFRESH = "online_refresh"
        private const val OPEN = "#OPEN"
        private const val CLOSE = "#CLOSE"
        private const val PING = "#PING"
        private const val PING_MS = 15_000L
        private const val TIMEOUT_MS = 50_000L
        /** Sala sem sinal de quem criou há mais de 2 minutos é considerada abandonada. */
        private const val STALE_MS = 120_000L
    }
}
