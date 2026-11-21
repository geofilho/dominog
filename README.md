# Dominó Acessível (Android)

Jogo de dominó nativo (Kotlin + Jetpack Compose), para quem enxerga e adaptado para pessoas cegas
(voz própria do app, sons de pedra e suporte ao TalkBack).

## Modos
- **Contra o computador** – o celular joga contra você.
- **Bluetooth** – digite seu nome (fica salvo), um aparelho toca em *Criar sala*, o outro em
  *Procurar amigo* e escolhe o aparelho. Quem criou a sala toca em *Iniciar jogo* e o outro *Aceita*.
- **Mesmo aparelho** – Jogador 1 e Jogador 2. Depois de cada jogada as pedras ficam ocultas e a voz diz
  "Jogador dois, agora sua vez". O próximo toca em *Mostrar pedras* e a voz avisa "Pedras visíveis".

## Regras (2 jogadores)
28 pedras (duplo-seis), 7 para cada, 14 no monte. Na primeira rodada sai quem tem a carroça mais alta.
Sem jogada: compra do monte; monte vazio: passa. Bate quem zerar a mão (ganha os pontos da mão do outro).
Jogo trancado: vence quem tiver menos pontos.

## Estrutura
- `game/` – regras puras (testadas em `app/src/test`), IA e serialização do estado.
- `audio/SoundFx.kt` – sons de pedra sintetizados (sem arquivos de áudio no APK).
- `speech/Voice.kt` – voz em português (TextToSpeech).
- `bt/BtLink.kt` – Bluetooth clássico (RFCOMM). O anfitrião tem a autoridade do jogo e envia o estado.
- `ui/` – telas Compose e `AppViewModel` que coordena os modos.

## Build
`./gradlew assembleDebug` (ou baixe o APK gerado pelo GitHub Actions em *Actions → Android → artifacts*).
