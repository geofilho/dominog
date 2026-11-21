package com.geofilho.dominog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HELP = listOf(
    "Jogar contra o computador" to
        "Você joga contra o celular. Toque numa pedra para jogar. Se ela encaixar nas duas pontas, " +
        "o jogo pergunta: esquerda ou direita. As pedras que encaixam ficam com borda amarela.",
    "Jogar pelo Bluetooth" to
        "Digite seu nome uma vez, ele fica salvo. Um dos dois toca em Criar sala. O outro toca em " +
        "Procurar amigo e escolhe o aparelho do amigo na lista. Conectados, quem criou a sala toca em " +
        "Iniciar jogo e o outro toca em Aceitar.",
    "Jogar com um parceiro no mesmo aparelho" to
        "Jogador 1 e Jogador 2 usam o mesmo celular. Depois de cada jogada as pedras ficam ocultas e a voz " +
        "diz de quem é a vez. Passe o aparelho; o próximo toca em Mostrar pedras e a voz avisa: pedras visíveis.",
    "Regras" to
        "São 28 pedras, 7 para cada jogador. Na primeira rodada começa quem tem a carroça mais alta. " +
        "Sem pedra que encaixe, toque em Comprar pedra; se o monte acabar, toque em Passar vez. " +
        "Bate quem jogar a última pedra e ganha os pontos que sobraram na mão do outro. " +
        "Se ninguém puder jogar, o jogo tranca e vence quem tiver menos pontos na mão.",
    "Para pessoas cegas" to
        "O jogo fala cada jogada, as pontas da mesa e de quem é a vez, e tem sons de pedra. " +
        "Funciona com o TalkBack: cada pedra é lida pelo nome. O botão Ouvir mesa e pedras lê a mesa, " +
        "suas pedras e o placar a qualquer momento.",
)

@Composable
fun HelpScreen(vm: AppViewModel) {
    BackHandler { vm.backToMenu() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        OutlinedButton(onClick = { vm.backToMenu() }) { Text("Voltar") }
        Text(
            "Como usar", fontSize = 30.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() },
        )
        HELP.forEach { (title, body) ->
            Text(
                title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Palette.Status,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() },
            )
            Text(body, fontSize = 17.sp, lineHeight = 24.sp)
        }
    }
}
