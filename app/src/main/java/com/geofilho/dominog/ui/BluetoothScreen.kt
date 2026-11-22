package com.geofilho.dominog.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.geofilho.dominog.bt.LinkState

private val BT_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun BluetoothScreen(vm: AppViewModel) {
    val askBack = rememberConfirm("Deseja voltar ao menu?") { vm.backToMenu() }
    BackHandler { askBack() }
    val ctx = LocalContext.current
    val link = vm.link
    val state by link.state.collectAsState()
    val found by link.found.collectAsState()
    val scanning by link.scanning.collectAsState()
    var editingName by rememberSaveable { mutableStateOf(vm.playerName.isBlank()) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var choosePhoto by remember { mutableStateOf(false) }
    var discoverableAsked by rememberSaveable { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.photoFromGallery(uri)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) vm.setPhoto(bmp)
    }

    fun hasPerms() = BT_PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    // O Android devolve como resultado os segundos de visibilidade aceitos (ou "cancelado").
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.discoverableResult(it.resultCode)
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (link.enabled) pendingAction?.invoke() else vm.lobbyMessage = "Ative o Bluetooth para jogar."
        pendingAction = null
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPerms()) {
            if (link.enabled) { pendingAction?.invoke(); pendingAction = null }
            else enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            vm.lobbyMessage = "Permissão de Bluetooth negada. Libere nas configurações do aparelho."
            pendingAction = null
        }
    }

    /** Garante permissões e Bluetooth ligado antes de executar [action]. */
    fun withBluetooth(action: () -> Unit) {
        if (!link.available) { vm.lobbyMessage = "Este aparelho não tem Bluetooth."; return }
        pendingAction = action
        when {
            !hasPerms() -> perms.launch(BT_PERMISSIONS)
            !link.enabled -> enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> { action(); pendingAction = null }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .semantics { isTraversalGroup = true }
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            CornerExitButton("Voltar", askBack)
        }
        Text(
            "Jogar pelo Bluetooth", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        vm.lobbyMessage?.let {
            Text(it, color = Palette.Status, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 10.dp))
        }

        if (editingName) {
            NameEditor(vm.playerName) { vm.saveName(it); editingName = false }
            return@Column
        }

        // Nome + foto
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlayerPhoto(vm.myPhoto, "Sua foto", 72.dp)
            Column {
                Text("Seu nome: ${vm.playerName}", fontSize = 20.sp)
                Row {
                    TextButton(onClick = { editingName = true }, enabled = state !is LinkState.Connected) { Text("Alterar nome") }
                    TextButton(onClick = { choosePhoto = true }) { Text(if (vm.myPhoto == null) "Inserir foto" else "Trocar foto") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (val st = state) {
            LinkState.Connected -> Lobby(vm)
            is LinkState.Connecting -> {
                CircularProgressIndicator()
                Text("Conectando a ${st.name}…", modifier = Modifier.padding(16.dp))
            }
            else -> {
                // A tela já é uma sala: este aparelho fica esperando o amigo enquanto procura.
                Text(
                    if (st == LinkState.Hosting) "Sala aberta. Aguardando seu amigo…" else "Abrindo a sala…",
                    textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp),
                )
                MenuButton(if (scanning) "Buscando…" else "Buscar amigo", Palette.Start) {
                    withBluetooth { link.startScan() }
                }
                if (found.isNotEmpty() || scanning) {
                    Text(
                        "Toque no aparelho do seu amigo:", fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                found.forEach { d ->
                    Text(
                        d.name,
                        fontSize = 19.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(vertical = 4.dp)
                            .background(Palette.AreaBorder, RoundedCornerShape(8.dp))
                            .semantics { role = Role.Button }
                            .clickable { vm.joinMode(); link.join(d) }
                            .padding(16.dp),
                    )
                }
            }
        }
    }

    // Abre a sala automaticamente (e reabre após uma conexão falhar ou terminar).
    LaunchedEffect(state, editingName) {
        if (editingName) return@LaunchedEffect
        if (state == LinkState.Idle || state is LinkState.Failed) {
            if (state is LinkState.Failed) kotlinx.coroutines.delay(1500)
            withBluetooth {
                vm.hostRoom()
                // Só pede para ficar visível se ainda não estiver (vale por 1 hora).
                if (!discoverableAsked && vm.needsDiscoverable()) {
                    discoverableAsked = true
                    discoverable.launch(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 3600),
                    )
                }
            }
        }
    }

    if (choosePhoto) {
        AlertDialog(
            onDismissRequest = { choosePhoto = false },
            title = { Text("Foto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuButton("Escolher da galeria") {
                        choosePhoto = false
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    MenuButton("Tirar foto com a câmera") {
                        choosePhoto = false
                        runCatching { takePhoto.launch(null) }
                            .onFailure { vm.lobbyMessage = "Não foi possível abrir a câmera." }
                    }
                    if (vm.myPhoto != null) MenuButton("Remover foto", Palette.AreaBorder) {
                        choosePhoto = false; vm.setPhoto(null)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosePhoto = false }) { Text("Cancelar") } },
        )
    }

    if (vm.inviteReceived) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Convite") },
            text = { Text("${vm.friendName} quer começar o jogo.") },
            confirmButton = { TextButton(onClick = { vm.answerInvite(true) }) { Text("Aceitar") } },
            dismissButton = { TextButton(onClick = { vm.answerInvite(false) }) { Text("Recusar") } },
        )
    }
}

@Composable
private fun NameEditor(initial: String, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    Text("Digite seu nome para os amigos te encontrarem:", textAlign = TextAlign.Center)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(20) },
        label = { Text("Seu nome") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
    MenuButton("Salvar", Palette.Start) { if (name.isNotBlank()) onSave(name) }
}

@Composable
private fun Lobby(vm: AppViewModel) {
    val friend = vm.friendName.ifBlank { "amigo" }
    PlayerPhoto(vm.friendPhoto, "Foto de $friend", 96.dp)
    Text(
        "Conectado com $friend", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(16.dp))
    if (vm.waitingAccept) {
        CircularProgressIndicator()
        Text("Aguardando $friend aceitar…", modifier = Modifier.padding(12.dp))
    } else {
        MenuButton("Iniciar jogo", Palette.Start) { vm.invite() }
    }
    OutlinedButton(onClick = { vm.leaveRoom() }, modifier = Modifier.padding(top = 20.dp)) { Text("Desconectar") }
}

/** Foto redonda do jogador (ou um círculo com ícone de pessoa, se não tiver foto). */
@Composable
fun PlayerPhoto(photo: android.graphics.Bitmap?, description: String, size: androidx.compose.ui.unit.Dp) {
    val mod = Modifier
        .size(size)
        .clip(CircleShape)
        .background(Palette.AreaBorder)
    if (photo != null) {
        Image(
            bitmap = photo.asImageBitmap(), contentDescription = description,
            contentScale = ContentScale.Crop, modifier = mod,
        )
    } else {
        Box(mod.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text("👤", fontSize = (size.value * 0.45f).sp)
        }
    }
}
