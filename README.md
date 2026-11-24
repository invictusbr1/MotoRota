# MotoRota 🏍️

Um app do **Grupo Conecta** — navegação inteligente e offline para motociclistas.

Escolha o perfil que importa para a sua viagem:

| Modo | O que prioriza |
|---|---|
| 🛡️ **Segura** | Prioriza rodovias externas e desvia com força de áreas urbanas densas e de risco (margem 50% maior) |
| 🏍️ **Desempenho** | Equilíbrio ideal: pista livre, contorna cidades e rende melhor mesmo rodando alguns km a mais |
| ⚡ **Rápida** | Menor tempo estimado, mantendo desvios de áreas bloqueadas e avisos de trechos críticos |

Em **todos** os modos:
- **Estrada de terra só entra se não houver alternativa asfaltada.**
- **Áreas bloqueadas pelo piloto nunca são atravessadas.**

## Principais Recursos

- 🧭 **HUD de Navegação Profissional**: ícones vetoriais nítidos de manobra, barra **"Em seguida"**, placa brasileira de **limite da via** (`--` quando não informado), botão **Re-centralizar**, visão **Geral** e toque direto nas rotas alternativas no mapa.
- 🔒 **Navegação com Tela Bloqueada (2º plano)**: serviço dedicado em primeiro plano mantém o GPS e os avisos por voz ativos com o celular no bolso ou na jaqueta (ideal para intercomunicador no capacete), com botão de encerrar direto na notificação.
- 🏍️ **Cockpit de Moto**: velocímetro em anel de 240° (âmbar perto do limite, vermelho acima, com proteção contra travamento de velocidade em túneis), computador de bordo (distância, média em movimento) e **resumo completo ao chegar**.
- 📷 **Radares Offline** (~11 mil em BR, AR, CL e UY): alerta visual e aviso antecipado por voz sem interromper instruções de curva.
- ⚠️ **Alertas Colaborativos Locais**: buraco, polícia, acidente, radar móvel, obra, óleo/areia e animal, com validade automática por categoria.
- 🗺️ **Mapa Limpo + Satélite**: temas **dia** e **noite** automáticos sem poluição visual e camada **satélite Sentinel-2** com ampliação contínua até o nível da rua.
- ✏️ **Roteiro Inteligente e Passeios**: painel recolhível, inversão rápida entre partida e destino (`⇅`), reordenação de paradas, inserção automática na melhor ordem ao tocar no mapa, **ida e volta**, desfazer e salvamento de passeios.
- ⛽ **Autonomia do Tanque**: acompanhe os km rodados pelo GPS, receba avisos de reserva por voz e saiba antes de sair se a viagem exigirá parada para abastecer.
- 🌧️ **Clima e Pôr do Sol no Trajeto**: previsão de chuva na hora exata em que você passará por cada trecho ([Open-Meteo](https://open-meteo.com), respeitando o fuso horário local) e cálculo offline do pôr do sol.
- 🚦 **Trânsito ao Vivo** ([TomTom](https://developer.tomtom.com)): desvia automaticamente de vias interditadas e lista acidentes, obras, alagamentos e retenções severas.
- 🚨 **Proteção em Áreas e Corredores de Risco**:
  - **Áreas críticas** ([`areas_risco.csv`](app/src/main/assets/areas_risco.csv)): desvia de ruas internas mantendo vias expressas de borda liberadas, com aviso por voz *"evite parar"*.
  - **Zonas urbanas e corredores** ([`zonas_risco.csv`](app/src/main/assets/zonas_risco.csv) e [`trechos_risco.json`](app/src/main/assets/trechos_risco.json)): aplica a regra dos motociclistas (*rodovia por fora > rodovia urbana > rua de bairro*), reforçando o peso à noite e nos fins de semana (`CorridorIndex`).

## Como instalar no celular

1. Abra a página **Releases** deste repositório no GitHub pelo celular.
2. Baixe o arquivo **`MotoRota.apk`** da versão mais recente.
3. Abra o arquivo (se solicitado, autorize instalar apps desta fonte).
4. Permita o acesso à localização e toque no ícone de download para baixar os mapas offline desejados (Brasil, Argentina, Chile e Uruguai — gerenciamento individual por país).

As atualizações instalam por cima da versão anterior mantendo seus mapas, ajustes, alertas e passeios salvos.

## O que funciona sem internet

| Função | Sem internet? |
|---|---|
| Calcular e recalcular rotas | ✅ Sim (após baixar os mapas `.rd5`) |
| Navegação GPS, voz e tela bloqueada | ✅ Sim |
| Áreas de risco, corredores e alertas | ✅ Sim |
| Radares, limite da via e pôr do sol | ✅ Sim |
| Mapa visual | ⚠️ Regiões já visualizadas ficam em cache (até 600 MB) |
| Busca por texto / Clima / Trânsito ao vivo | ❌ Requer conexão (sem sinal, marque destinos tocando no mapa) |

## Arquitetura Técnica

- **Android Nativo**: Kotlin + Jetpack Compose (Material 3) + Foreground Location Service.
- **Motor de Rotas Offline**: [BRouter](https://github.com/abrensch/brouter) (MIT) embarcado com índice espacial customizado (`CorridorIndex`) e perfil cinemático [`moto.brf`](app/src/main/assets/brouter/moto.brf).
- **Renderização de Mapas**: [MapLibre Native](https://maplibre.org) + [OpenFreeMap](https://openfreemap.org) + Sentinel-2 Cloudless (EOX).
- **Geocodificação**: [Photon](https://photon.komoot.io) (dados © [OpenStreetMap](https://www.openstreetmap.org/copyright)).
- **CI/CD**: GitHub Actions gera e publica o APK assinado automaticamente a cada push na `main` ([`.github/workflows/build.yml`](.github/workflows/build.yml)) e atualiza a base de radares mensalmente ([`.github/workflows/radares.yml`](.github/workflows/radares.yml)).
