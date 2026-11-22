package com.geofilho.dominog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
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
    BackHandler { vm.backToMenu() }
    // A rodada é um objeto mutável: [v] muda a cada jogada e é passado às partes da tela
    // para que o Compose as redesenhe (senão ele reaproveita o desenho antigo).
    val v = vm.version
    val r = vm.round ?: return
    val me = vm.me
    val opp = 1 - me
    var choosing by remember { mutableStateOf<Tile?>(null) }
    if (choosing != null && (!vm.canAct() || choosing !in r.hands[me])) choosing = null

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1) Placar
        Text(
            "${vm.nameOf(0)} ${vm.scores[0]}  ×  ${vm.scores[1]} ${vm.nameOf(1)}",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.semantics {
                contentDescription = "Placar: ${vm.nameOf(0)} ${vm.scores[0]}, ${vm.nameOf(1)} ${vm.scores[1]}"
            },
        )
        Spacer(Modifier.height(6.dp))
        // Linha de status amarela (só visual: a voz do app já fala tudo, o TalkBack não repete).
        Text(
            vm.status,
            color = Palette.Status,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clearAndSetSemantics { },
        )

        // 2) Mesa: resumo das pontas  3) cada pedra da mesa
        Area("Mesa", spokenTitle = false) {
            Text(
                if (r.board.isEmpty()) "Vazia"
                else "Ponta esquerda ${r.leftEnd}  ·  Ponta direita ${r.rightEnd}",
                fontSize = 15.sp,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clearAndSetSemantics { contentDescription = vm.boardSummary(r) },
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Felt),
                contentAlignment = Alignment.Center,
            ) {
                if (vm.shuffling) ShuffleAnimation()
                else Board(r, v)
            }
        }

        // 4) Adversário  5) Monte
        Area(vm.nameOf(opp), spokenTitle = false) {
            val n = r.hands[opp].size
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${vm.nameOf(opp)}, ${vm.pedras(n)} restantes" },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(minOf(n, 12)) { DominoTile(0, 0, vertical = true, unit = 16.dp, faceDown = true) }
                }
                Text("${vm.pedras(n)} restantes", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Text(
                "Monte: ${vm.pedras(r.boneyard.size)}", fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clearAndSetSemantics { contentDescription = "Monte, ${vm.pedras(r.boneyard.size)}" },
            )
        }

        // 6) Suas pedras  7) cada pedra
        val handTitle = when (vm.mode) {
            Mode.LOCAL -> "Pedras do ${vm.nameOf(me)}"
            else -> "Suas pedras"
        }
        Area(handTitle) {
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
                if (r.canDraw(me)) ActionButton("Comprar Pedra") { vm.draw() }
                if (r.mustPass(me)) ActionButton("Passar Vez") { vm.pass() }
            }
        }

        // Só para quem usa leitor de tela.
        if (rememberScreenReaderOn()) {
            OutlinedButton(onClick = { vm.describe() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Ouvir mesa")
            }
        }
        OutlinedButton(onClick = { vm.backToMenu() }, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
            Text("Sair do jogo")
        }
    }
}

/** Caixa com borda tracejada, como as ".area" do modelo. */
@Composable
private fun Area(title: String, spokenTitle: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .dashedBorder()
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = if (spokenTitle) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.height(8.dp))
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
            .padding(12.dp),
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
                t.b, t.a, vertical = true, unit = 38.dp,
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
    Column(Modifier.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Jogar ${Words.tile(t)} em qual ponta?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            ActionButton("Esquerda (${r.leftEnd})") { onPick(Side.LEFT) }
            ActionButton("Direita (${r.rightEnd})") { onPick(Side.RIGHT) }
        }
        OutlinedButton(onClick = onCancel) { Text("Cancelar") }
    }
}

/** Modo mesmo aparelho: pedras ocultas até o jogador da vez tocar em "Mostrar pedras". */
@Composable
private fun Curtain(vm: AppViewModel, @Suppress("UNUSED_PARAMETER") version: Int) {
    val r = vm.round ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clearAndSetSemantics { }) {
            repeat(r.hands[r.current].size) { DominoTile(0, 0, vertical = true, unit = 22.dp, faceDown = true) }
        }
        Text(
            "Vez do ${vm.nameOf(r.current)}. Passe o aparelho.",
            fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp),
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
        res.winner < 0 -> "Jogo trancado — empate"
        res.reason == EndReason.BATIDA -> "${vm.nameOf(res.winner)} bateu! +${res.points}"
        else -> "Trancado: ${vm.nameOf(res.winner)} venceu! +${res.points}"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        for (p in 0..1) if (r.hands[p].isNotEmpty()) {
            Text("${vm.nameOf(p)} ficou com:", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(4.dp)) {
                r.hands[p].forEach { DominoTile(it.b, it.a, vertical = true, unit = 20.dp) }
            }
        }
        Button(
            onClick = { vm.nextRound() },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Start, contentColor = Color.White),
            shape = RoundedCornerShape(50),
            modifier = Modifier.padding(top = 10.dp).heightIn(min = 60.dp),
        ) { Text("Próxima rodada", fontSize = 20.sp) }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Action, contentColor = Color.White),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 10.dp).heightIn(min = 56.dp),
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
