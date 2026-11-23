package com.geofilho.dominog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Configurações do jogo: pontos da partida, compra de pedras, sons e histórico. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    BackHandler { vm.backToMenu() }
    var history by remember { mutableStateOf<String?>(null) }

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
            "Configurações", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        // Cada toque alterna 3, 5 e 10 pontos.
        MenuButton("Partida até ${vm.targetChoice} pontos", Palette.Action) { vm.cycleTarget() }
        BuyingSwitch(vm)
        SettingSwitch(
            title = "Silenciar sons do jogo",
            detail = "Cala embaralhar, pedras, bipes, porta, aplausos e os avisos falados do modo parceiro. A fala do leitor de tela continua.",
            checked = vm.soundsMuted,
        ) { vm.changeSoundsMuted(it) }
        MenuButton("Ver histórico dos jogos", Palette.AreaBorder) { history = vm.allHistoryText() }
    }

    history?.let { text ->
        AlertDialog(
            onDismissRequest = { history = null },
            title = { Text("Histórico dos jogos") },
            text = { Text(text, fontSize = 17.sp, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { history = null }) { Text("Fechar") } },
        )
    }
}

/** Chave "Comprar pedras" (também aparece na sala do Bluetooth). */
@Composable
fun BuyingSwitch(vm: AppViewModel) {
    SettingSwitch(
        title = "Comprar pedras",
        detail = "Ligada, quem não tem pedra que encaixe compra do monte. Desligada, as pedras que sobram ficam dormindo e quem não tem jogada passa a vez.",
        checked = vm.buyingChoice,
    ) { vm.changeBuying(it) }
}

/** Linha com título, explicação e chave; o TalkBack lê como "título, explicação, chave, ativada/desativada". */
@Composable
fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp)
            Text(detail, fontSize = 14.sp, color = Palette.Text.copy(alpha = 0.75f))
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
