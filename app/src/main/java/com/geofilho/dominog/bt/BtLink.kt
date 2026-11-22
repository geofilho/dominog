package com.geofilho.dominog.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

sealed interface LinkState {
    data object Idle : LinkState
    data object Hosting : LinkState
    data class Connecting(val name: String) : LinkState
    data object Connected : LinkState
    data class Failed(val message: String) : LinkState
}

data class FoundDevice(val name: String, val address: String, val device: BluetoothDevice)

/**
 * Conexão Bluetooth clássica (RFCOMM) entre dois aparelhos com o app.
 * Protocolo de texto, uma mensagem por linha (ver [Proto]).
 * As permissões de Bluetooth são pedidas pela tela antes de chamar estes métodos.
 */
@SuppressLint("MissingPermission")
class BtLink(context: Context) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incoming: SharedFlow<String> = _incoming
    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var ioJob: Job? = null
    private var receiverRegistered = false
    /** Muda a cada nova tentativa (sala/conexão); evita que uma tentativa antiga mexa no estado. */
    @Volatile private var generation = 0
    /** true = este aparelho recebeu a conexão (anfitrião); false = conectou no amigo (convidado). */
    @Volatile var isHost = false; private set

    val available get() = adapter != null

    /** O celular já está visível para outros aparelhos Bluetooth? */
    val isDiscoverable: Boolean
        get() = runCatching { adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)
    val enabled get() = adapter?.isEnabled == true

    // ---------------- Descoberta ----------------
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    else @Suppress("DEPRECATION") (i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return)
                    addFound(d)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            }
        }
    }

    private fun addFound(d: BluetoothDevice) {
        val name = runCatching { d.name }.getOrNull() ?: return
        if (_found.value.any { it.address == d.address }) return
        _found.value = _found.value + FoundDevice(name, d.address, d)
    }

    fun startScan() {
        val a = adapter ?: return
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, f)
            receiverRegistered = true
        }
        _found.value = emptyList()
        runCatching { a.bondedDevices }.getOrNull()?.forEach(::addFound)
        if (a.isDiscovering) a.cancelDiscovery()
        _scanning.value = a.startDiscovery()
    }

    fun stopScan() {
        runCatching { adapter?.cancelDiscovery() }
        _scanning.value = false
        if (receiverRegistered) { runCatching { app.unregisterReceiver(receiver) }; receiverRegistered = false }
    }

    // ---------------- Conexão ----------------
    /** Cria a sala: fica aguardando o amigo conectar. */
    fun host() {
        val a = adapter ?: return fail("Bluetooth indisponível")
        closeSockets()
        val gen = ++generation
        _state.value = LinkState.Hosting
        scope.launch {
            try {
                val srv = a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                if (gen != generation) { runCatching { srv.close() }; return@launch }
                server = srv
                val s = srv.accept()
                runCatching { srv.close() }
                server = null
                if (gen != generation) { runCatching { s.close() }; return@launch }
                attach(s, host = true)
            } catch (e: Exception) {
                // Sala fechada de propósito (ex.: foi conectar no amigo) não é erro.
                if (gen == generation) fail("Sala encerrada")
            }
        }
    }

    /** Entra na sala de um amigo encontrado na busca. */
    fun join(dev: FoundDevice) {
        stopScan()
        val gen = ++generation
        closeSockets()
        _state.value = LinkState.Connecting(dev.name)
        scope.launch {
            try {
                val s = dev.device.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                if (gen != generation) { runCatching { s.close() }; return@launch }
                attach(s, host = false)
            } catch (e: Exception) {
                if (gen == generation) fail("Não foi possível conectar a ${dev.name}. Confirme que ele está na tela do Bluetooth.")
            }
        }
    }

    private fun attach(s: BluetoothSocket, host: Boolean) {
        isHost = host
        socket = s
        out = s.outputStream
        _state.value = LinkState.Connected
        ioJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        _incoming.emit(line)
                    }
                }
            } catch (_: Exception) { }
            if (socket === s) fail("Conexão perdida")
        }
    }

    fun send(line: String) {
        val o = out ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    synchronized(o) { o.write((line + "\n").toByteArray(Charsets.UTF_8)); o.flush() }
                }
            } catch (_: Exception) { fail("Conexão perdida") }
        }
    }

    private fun fail(msg: String) {
        closeSockets()
        _state.value = LinkState.Failed(msg)
    }

    private fun closeSockets() {
        ioJob?.cancel(); ioJob = null
        runCatching { server?.close() }; server = null
        val s = socket; socket = null; out = null
        runCatching { s?.close() }
    }

    fun disconnect() {
        generation++
        stopScan()
        closeSockets()
        _state.value = LinkState.Idle
    }

    companion object {
        const val SERVICE_NAME = "DominogSala"
        val APP_UUID: UUID = UUID.fromString("6f1c2a3e-8d4b-4e0a-9b7a-3d0c5e7f9a21")
    }
}

/** Mensagens trocadas entre os aparelhos. */
object Proto {
    const val HELLO = "HELLO"   // HELLO|nome
    const val PHOTO = "PHOTO"   // PHOTO|jpeg em base64 (foto do jogador)
    const val INVITE = "INVITE" // anfitrião convida para começar
    const val ACCEPT = "ACCEPT"
    const val DECLINE = "DECLINE"
    const val STATE = "STATE"   // STATE|placar0|placar1|rodada codificada  (anfitrião → convidado)
    const val PLAY = "PLAY"     // PLAY|idPedra|lado  (convidado → anfitrião)
    const val DRAW = "DRAW"
    const val PASS = "PASS"
    const val NEXT = "NEXT"     // pedir nova rodada
    const val BYE = "BYE"
}
