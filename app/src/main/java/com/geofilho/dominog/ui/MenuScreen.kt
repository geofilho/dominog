package com.geofilho.dominog.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.geofilho.dominog.game.Level
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MenuScreen(vm: AppViewModel) {
    var askLevel by remember { mutableStateOf(false) }
    if (askLevel) {
        // Diálogo: o TalkBack leva o foco direto para ele.
        AlertDialog(
            onDismissRequest = { askLevel = false },
            title = { Text("Escolha o nível") },
            text = {
                Column {
                    Level.entries.forEach { l ->
                        MenuButton(l.label, Palette.Action) { askLevel = false; vm.startCpu(l) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askLevel = false }) { Text("Cancelar") } },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DominoTile(6, 6, vertical = true, unit = 30.dp)
            DominoTile(6, 3, vertical = true, unit = 30.dp)
            DominoTile(3, 1, vertical = true, unit = 30.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Dominó G", fontSize = 40.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(36.dp))
        MenuButton("Jogar contra o computador", Palette.Start) { askLevel = true }
        MenuButton("Jogar pelo Bluetooth", Palette.Action) { vm.openBluetooth() }
        MenuButton("Jogar com um parceiro no mesmo aparelho", Palette.Action) { vm.startLocal() }
        MenuButton("Como usar", Palette.AreaBorder) { vm.openHelp() }
    }
}

@Composable
fun MenuButton(label: String, color: Color = Palette.Action, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(min = 64.dp),
    ) { Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
}
