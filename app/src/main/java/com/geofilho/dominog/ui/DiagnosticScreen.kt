package com.geofilho.dominog.ui

import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun DiagnosticScreen(vm: AppViewModel) {
    BackHandler { vm.backToMenu() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }

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
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            CornerExitButton("Voltar") { vm.backToMenu() }
        }
        Text(
            "Diagnóstico", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        MenuButton("Verificar atualização (versão ${vm.updater.installedVersionName})", Palette.Start) { vm.checkUpdate() }
        MenuButton("Compartilhar jogo", Palette.Action) {
            scope.launch {
                // Usa o número da última versão publicada (sem internet, o da instalada).
                val latest = withTimeoutOrNull(4000) { vm.updater.latestVersion() }
                val version = latest?.let { "1.0.$it" } ?: vm.updater.installedVersionName
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Dominó G")
                    .putExtra(Intent.EXTRA_TEXT, helpPlainText(version))
                ctx.startActivity(Intent.createChooser(send, "Compartilhar o Dominó G"))
            }
        }
        MenuButton("Ver relatório", Palette.AreaBorder) { report = buildReport(ctx, vm) }
        UpdateDialog(vm)
    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Relatório") },
            text = {
                Text(text, fontSize = 15.sp, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Relatório Dominó G", text))
                        vm.voice.speak("Relatório copiado", flush = true)
                    }) { Text("Copiar") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Relatório do Dominó G")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        ctx.startActivity(Intent.createChooser(send, "Compartilhar relatório"))
                    }) { Text("Compartilhar") }
                }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Fechar") } },
        )
    }
}

/** Dados do aparelho, do sistema e do app, para ajudar a resolver problemas. */
private fun buildReport(ctx: Context, vm: AppViewModel): String {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    val readers = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        .joinToString { it.resolveInfo.serviceInfo.packageName }.ifEmpty { "nenhum" }
    val tts = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull() ?: "?"
    return buildString {
        appendLine("Dominó G versão ${vm.updater.installedVersionName} (${vm.updater.installedVersion})")
        appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Leitor de tela ligado: ${if (am.isTouchExplorationEnabled) "sim" else "não"}")
        appendLine("Serviços com fala: $readers")
        appendLine("Motor de voz padrão: $tts")
        appendLine("Voz do app: ${if (vm.voice.isReady) "pronta" else "não iniciou"}")
        appendLine("Bluetooth: ${when { bt == null -> "não existe"; bt.isEnabled -> "ligado"; else -> "desligado" }}")
        appendLine("Estado da conexão: ${vm.link.state.value}")
        appendLine("Nome salvo: ${vm.playerName.ifBlank { "(nenhum)" }}")
        appendLine("Foto salva: ${if (vm.myPhoto != null) "sim" else "não"}")
        append("Idioma do aparelho: ${java.util.Locale.getDefault()}")
    }
}
