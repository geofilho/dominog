package com.geofilho.dominog.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geofilho.dominog.online.OnlineState

/** Jogar online: cria uma sala (com código) ou entra na sala de um amigo pelo código. */
@Composable
fun OnlineScreen(vm: AppViewModel) {
    val askBack = rememberConfirm("Deseja voltar ao menu?") { vm.backToMenu() }
    BackHandler { askBack() }
    val ctx = LocalContext.current
    val state by vm.online.state.collectAsState()
    var editingName by rememberSaveable { mutableStateOf(vm.playerName.isBlank()) }
    var askCode by rememberSaveable { mutableStateOf(false) }

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
        Text(
            "Jogar online", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        vm.lobbyMessage?.let {
            Text(it, color = Palette.Status, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (editingName) {
            NameEditor(vm.playerName) { vm.saveName(it); editingName = false }
            return@Column
        }
        Text("Seu nome: ${vm.playerName}", fontSize = 20.sp)
        if (vm.session.roster.size <= 1) {
            TextButton(onClick = { editingName = true }) { Text("Alterar nome") }
        }

        @Suppress("UNUSED_VARIABLE") val rv = vm.roomVersion // redesenha quando a sala muda
        when (val st = state) {
            OnlineState.Idle -> {
                Text(
                    "Crie uma sala e mande o código para os amigos, ou entre na sala de um amigo com o código que ele mandou.",
                    textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp),
                )
                MenuButton("Criar sala", Palette.Start) { vm.createOnlineRoom() }
                MenuButton("Entrar na sala de um amigo", Palette.Action) { askCode = true }
            }
            is OnlineState.Working -> {
                CircularProgressIndicator(Modifier.padding(top = 12.dp))
                Text(st.message, modifier = Modifier.padding(12.dp))
            }
            is OnlineState.Hosting -> {
                RoomCode(st.code)
                MenuButton("Compartilhar código", Palette.Action) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                        Intent.EXTRA_TEXT,
                        "Vamos jogar Dominó G online! Abra o jogo, toque em Jogar online, depois em Entrar na sala de um amigo, " +
                            "e digite o código ${st.code}.",
                    )
                    ctx.startActivity(Intent.createChooser(send, "Enviar o código da sala"))
                }
                if (vm.session.roster.size > 1) Lobby(vm)
                else {
                    Text("Aguardando amigos…", modifier = Modifier.padding(12.dp))
                    MenuButton("Fechar a sala", Palette.AreaBorder) { vm.leaveRoom() }
                }
            }
            is OnlineState.Joined -> {
                RoomCode(st.code)
                if (vm.session.roster.size > 1) Lobby(vm)
                else {
                    CircularProgressIndicator(Modifier.padding(top = 12.dp))
                    Text("Entrando na sala…", modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    if (askCode) CodeDialog(onCancel = { askCode = false }) { code -> askCode = false; vm.joinOnlineRoom(code) }
    InviteDialog(vm)
}

/** Código da sala em letras grandes; o TalkBack lê número por número. */
@Composable
private fun RoomCode(code: String) {
    Text(
        "Sala $code",
        fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Status,
        modifier = Modifier
            .padding(vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = "Código da sala: ${code.toList().joinToString(" ")}" },
    )
}

@Composable
private fun CodeDialog(onCancel: () -> Unit, onJoin: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val ok = code.length == 4
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Código da sala") },
        text = {
            Column {
                Text("Digite os 4 números que o seu amigo mandou.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(4) },
                    label = { Text("Código da sala") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onJoin(code) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (ok) onJoin(code) }, enabled = ok) { Text("Entrar") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}
