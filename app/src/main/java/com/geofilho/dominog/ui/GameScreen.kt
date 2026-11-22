package com.geofilho.dominog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geofilho.dominog.game.EndReason
import com.geofilho.dominog.game.Round
import com.geofilho.dominog.game.Side
import com.geofilho.dominog.game.Tile
import com.geofilho.dominog.game.Words
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameScreen(vm: AppViewModel) {
    val askExit = rememberConfirm("Deseja sair do jogo?") { vm.backToMenu() }
    val screenReader = rememberScreenReaderOn()
    // Voltar do Android (botão, gesto padrão ou gesto de voltar do TalkBack): pergunta antes de sair.
    BackHandler { askExit() }
    // A rodada é um objeto mutável: [v] muda a cada jogada e é passado às partes da tela
    // para que o Compose as redesenhe (senão ele reaproveita o desenho antigo).
    val v = vm.version
    val r = vm.round ?: return
    val me = vm.me
    val opp = 1 - me
    var choosing by remember { mutableStateOf<Tile?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Histórico") },
            text = { Text(vm.historyText(), fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Fechar") } },
        )
    }
    if (choosing != null && (!vm.canAct() || choosing !in r.hands[me])) choosing = null

    // Diálogo de comprar/passar (com leitor de tela): o foco do TalkBack vai direto para o botão.
    if (vm.askDraw && r.result == null && vm.canAct() && (r.canDraw(me) || r.mustPass(me))) {
        DrawDialog(vm, r, me)
    }

    // A tela cabe inteira, sem rolar: mesa e mão ficam em áreas próprias.
    // Só a área de baixo (suas pedras / fim da rodada) rola, se precisar.
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .safeDrawingPadding()
            .semantics { isTraversalGroup = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1) Placar (tocar mostra e fala o histórico). Sem botão de sair: use o Voltar do Android.
        Text(
            "${vm.nameOf(0)} ${vm.scores[0]}  ×  ${vm.scores[1]} ${vm.nameOf(1)}",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "ouvir o histórico") { showHistory = true; vm.speakHistory() }
                .padding(4.dp)
                .semantics {
                    contentDescription = "Placar, melhor de 5: ${vm.nameOf(0)} ${vm.scores[0]}, ${vm.nameOf(1)} ${vm.scores[1]}"
                    role = Role.Button
                },
        )
        // Linha de status amarela (só visual: a voz do app já fala tudo, o TalkBack não repete).
        Text(
            vm.status,
            color = Palette.Status,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clearAndSetSemantics { },
        )

        // 2) Mesa: resumo das pontas  3) cada pedra da mesa (fileira que rola para o lado)
        Area("Mesa", spokenTitle = false) {
            Text(
                if (r.board.isEmpty()) "Vazia"
                else "Ponta esquerda ${r.leftEnd}  ·  Ponta direita ${r.rightEnd}",
                fontSize = 15.sp,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clearAndSetSemantics { contentDescription = vm.boardSummary(r) },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Felt),
                contentAlignment = Alignment.Center,
            ) {
                if (vm.shuffling) ShuffleAnimation()
                else Board(r, v)
            }
        }

        // 4) Adversário  5) Monte (uma linha só)
        val n = r.hands[opp].size
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${vm.nameOf(opp)}, ${vm.pedras(n)} restantes" },
            ) {
                DominoTile(0, 0, vertical = true, unit = 12.dp, faceDown = true)
                Text("  ${vm.nameOf(opp)}: ${vm.pedras(n)}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Monte: ${vm.pedras(r.boneyard.size)}", fontSize = 15.sp,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Monte, ${vm.pedras(r.boneyard.size)}" },
            )
        }

        // 6) Suas pedras  7) cada pedra — ocupa o resto da tela e rola só aqui dentro, se precisar.
        val handTitle = when (vm.mode) {
            Mode.LOCAL -> "Pedras do ${vm.nameOf(me)}"
            else -> "Suas pedras"
        }
        Area(handTitle, modifier = Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val res = r.result
                when {
                    res != null -> RoundOver(vm, r, v)
                    vm.shuffling -> Text("Embaralhando…")
                    vm.mode == Mode.LOCAL && !vm.revealed -> Curtain(vm, v)
                    else -> Hand(vm, r, me, v, onChoose = { choosing = it })
                }
                val c = choosing
                if (c != null && res == null) SideChooser(r, c, v, onPick = { s -> choosing = null; vm.play(c, s) }, onCancel = { choosing = null })
                if (res == null && !vm.shuffling && vm.canAct()) {
                    // Depois de comprar e continuar sem pedra, o foco volta para este botão.
                    val actionFocus = remember { FocusRequester() }
                    val seenAction = remember { vm.actionFocus }
                    if (r.canDraw(me)) ActionButton("Comprar pedra", Modifier.focusRequester(actionFocus)) { vm.draw() }
                    if (r.mustPass(me)) ActionButton("Passar vez", Modifier.focusRequester(actionFocus)) { vm.pass() }
                    LaunchedEffect(vm.actionFocus) {
                        if (vm.actionFocus != seenAction) {
                            kotlinx.coroutines.delay(200)
                            runCatching { actionFocus.requestFocus() }
                        }
                    }
                }
            }
        }

        // Só para quem usa leitor de tela.
        if (screenReader) {
            OutlinedButton(onClick = { vm.describe() }, modifier = Modifier.padding(top = 2.dp)) {
                Text("Ouvir mesa")
            }
        }
    }
}

/**
 * Sem pedra que encaixe, na primeira vez da sua vez (com leitor de tela): janela com um botão só,
 * Comprar pedra / Passar vez, com o foco do TalkBack já nele. As pontas e o monte estão na descrição.
 * Para fechar sem comprar, use o voltar do Android.
 */
@Composable
private fun DrawDialog(vm: AppViewModel, r: Round, me: Int) {
    val buy = r.canDraw(me)
    val label = if (buy) "Comprar pedra" else "Passar vez"
    val info = if (r.board.isEmpty()) "Nenhuma pedra sua encaixa."
    else "Nenhuma pedra sua encaixa nas pontas ${Words.n(r.leftEnd!!)} e ${Words.n(r.rightEnd!!)}." +
        if (buy) " Monte com ${vm.pedras(r.boneyard.size)}." else " O monte acabou."
    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.dismissDraw() }) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            runCatching { focus.requestFocus() }
        }
        Button(
            onClick = { if (buy) vm.draw() else vm.pass() },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Start, contentColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .focusRequester(focus)
                .semantics { contentDescription = "$label. $info" },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(info, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Caixa com borda tracejada, como as ".area" do modelo. */
@Composable
private fun Area(
    title: String,
    spokenTitle: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .dashedBorder()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = if (spokenTitle) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

private fun Modifier.dashedBorder(): Modifier = drawBehind {
    val stroke = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    drawRoundRect(Palette.AreaBorder, style = stroke, cornerRadius = CornerRadius(10.dp.toPx()))
}

@Composable
private fun Board(r: Round, @Suppress("UNUSED_PARAMETER") version: Int) {
    if (r.board.isEmpty()) return
    val scroll = rememberScrollState()
    // Mantém visível a ponta onde a última pedra foi jogada.
    LaunchedEffect(r.board.size) {
        if (r.lastMove?.side == Side.LEFT) scroll.animateScrollTo(0) else scroll.animateScrollTo(scroll.maxValue)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        r.board.forEach { p ->
            DominoTile(
                p.left, p.right, vertical = p.tile.isDouble, unit = 30.dp,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = if (p.tile.isDouble) Words.tile(p.tile) else "${Words.n(p.left)} e ${Words.n(p.right)}"
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hand(vm: AppViewModel, r: Round, me: Int, @Suppress("UNUSED_PARAMETER") version: Int, onChoose: (Tile) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        r.hands[me].forEach { t ->
            DominoTile(
                t.b, t.a, vertical = true, unit = 34.dp,
                modifier = Modifier
                    .semantics {
                        contentDescription = "Pedra ${Words.tile(t)}"
                        role = Role.Button
                    }
                    .clickable { if (vm.tapTile(t).size > 1) onChoose(t) },
            )
        }
    }
}

@Composable
private fun SideChooser(r: Round, t: Tile, @Suppress("UNUSED_PARAMETER") version: Int, onPick: (Side) -> Unit, onCancel: () -> Unit) {
    // Diálogo: o TalkBack move o foco para ele automaticamente.
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Jogar ${Words.tile(t)} em qual ponta?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Ponta esquerda, ${Words.n(r.leftEnd!!)}") { onPick(Side.LEFT) }
                ActionButton("Ponta direita, ${Words.n(r.rightEnd!!)}") { onPick(Side.RIGHT) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
    )
}

/** Modo mesmo aparelho: pedras ocultas até o jogador da vez tocar em "Mostrar pedras". */
@Composable
private fun Curtain(vm: AppViewModel, @Suppress("UNUSED_PARAMETER") version: Int) {
    val r = vm.round ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clearAndSetSemantics { }) {
            repeat(r.hands[r.current].size) { DominoTile(0, 0, vertical = true, unit = 22.dp, faceDown = true) }
        }
        // Depois das informações da jogada, o foco do TalkBack vem para este aviso.
        val focus = remember { FocusRequester() }
        val seen = remember { vm.curtainFocus }
        LaunchedEffect(vm.curtainFocus) {
            if (vm.curtainFocus != seen) runCatching { focus.requestFocus() }
        }
        Text(
            if (r.board.isEmpty()) "${vm.nameOf(r.current)} começa" else "Passe para o ${vm.nameOf(r.current)}",
            fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .focusRequester(focus)
                .focusable()
                .semantics { heading() },
        )
        Button(
            onClick = { vm.reveal() },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Start, contentColor = Color.White),
            shape = RoundedCornerShape(50),
            modifier = Modifier.heightIn(min = 64.dp).fillMaxWidth(0.85f),
        ) { Text("Mostrar pedras", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RoundOver(vm: AppViewModel, r: Round, @Suppress("UNUSED_PARAMETER") version: Int) {
    val res = r.result ?: return
    val title = when {
        res.winner < 0 -> "Jogo trancado — empate (não conta)"
        res.reason == EndReason.BATIDA -> "${vm.nameOf(res.winner)} bateu!"
        else -> "Trancado: ${vm.nameOf(res.winner)} venceu a rodada!"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            "${if (vm.matchOver) "Placar final" else "Placar"}: ${vm.placarText()}",
            fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
        if (vm.matchOver) {
            Text(
                "🏆 ${vm.nameOf(vm.matchWinner)} venceu a partida! ${maxOf(vm.scores[0], vm.scores[1])} a ${minOf(vm.scores[0], vm.scores[1])}",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Status, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        for (p in 0..1) if (r.hands[p].isNotEmpty()) {
            val hand = r.hands[p]
            val summary = "${vm.nameOf(p)} ficou com ${vm.pedras(hand.size)}, somando ${r.handPips(p)} pontos"
            // Um elemento só para o TalkBack: quantas pedras, quantos pontos e quais são.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = summary + ": " + hand.joinToString(", ") { Words.tile(it) }
                },
            ) {
                Text("$summary:", fontSize = 15.sp, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(4.dp)) {
                    hand.forEach { DominoTile(it.b, it.a, vertical = true, unit = 20.dp) }
                }
            }
        }
        Button(
            onClick = { vm.nextRound() },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Start, contentColor = Color.White),
            shape = RoundedCornerShape(50),
            modifier = Modifier.padding(top = 10.dp).heightIn(min = 60.dp),
        ) { Text(if (vm.matchOver) "Nova partida" else "Próxima rodada", fontSize = 20.sp) }
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Action, contentColor = Color.White),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(top = 10.dp).heightIn(min = 56.dp),
    ) { Text(label, fontSize = 18.sp) }
}

/** Pedras viradas rolando/embaralhando na mesa enquanto toca o som. */
@Composable
private fun ShuffleAnimation() {
    val progress = remember { Animatable(0f) }
    val seeds = remember { List(14) { Random.nextFloat() to Random.nextFloat() } }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1600)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clearAndSetSemantics { contentDescription = "Embaralhando as pedras" },
    ) {
        val p = progress.value
        val tw = 18.dp.toPx(); val th = tw * 2
        seeds.forEachIndexed { i, (a, b) ->
            val ang = (a * 6.28f) + p * 9f * (if (i % 2 == 0) 1 else -1)
            val rad = size.minDimension * 0.32f * (0.4f + b * 0.6f)
            val cx = size.width / 2 + kotlin.math.cos(ang) * rad * 1.6f
            val cy = size.height / 2 + kotlin.math.sin(ang) * rad
            rotate(ang * 57f, Offset(cx, cy)) {
                drawRoundRect(Palette.Back, Offset(cx - tw / 2, cy - th / 2), Size(tw, th), CornerRadius(4.dp.toPx()))
            }
        }
    }
}
