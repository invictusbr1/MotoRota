# MotoRota 🏍️

Um app do **Grupo Conecta**.

Navegação para moto que deixa **você** escolher o que importa na rota:

| Modo | O que prioriza |
|---|---|
| 🛡️ **Segura** | Fica nas vias principais e passa longe das áreas de risco (margem 50% maior) |
| 🏍️ **Desempenho** | Rodovia e pista livre, contorna cidades. Aceita rodar mais km para render mais |
| ⚡ **Rápida** | Menor tempo |

Em **todos** os modos:
- **Estrada de terra só entra se não tiver outro caminho.**
- **Áreas de risco marcadas nunca são atravessadas.**

E mais:
- 📷 **Radares** (11 mil em BR, AR, CL e UY): aparecem no mapa como plaquinha, com aviso na tela e por voz.
- 🚦 **Placa com o limite de velocidade** da via. A velocidade fica vermelha se você passar do limite.
- ⚠️ **Avisos tipo Waze**: buraco, polícia, acidente, radar móvel, obra, óleo/areia, animal. Cada um some sozinho depois de um tempo.
- 🗺️ **Mapa limpo**, só com as estradas em destaque, nos temas **dia** e **noite**, e com **🛰️ satélite** (imagens Sentinel-2 da EOX).
- ✏️ **Editar caminho**: toque no mapa em todos os lugares por onde quer passar. Cada ponto entra sozinho na ordem certa. Toque num ponto para mover ou remover, e use **Desfazer**.
- ⭐ **Passeios**: reordene as paradas (⬆⬇), faça **ida e volta** e salve o roteiro para usar depois.
- 🧭 **Navegação estilo Waze**: faixa escura com a manobra e "em seguida", placa do **limite da via** sempre no mesmo canto ("--" quando a via não informa), botão **Re-centralizar** quando você arrasta o mapa e **Geral** para ver a rota toda.
- ☰ **Menu configurável**: todas as funções num menu; toque no 📌 para deixar a função como botão na tela (mapa e navegação têm botões separados).
- 🏍️ **Cockpit de moto**: velocímetro em anel (laranja perto do limite, vermelho acima), computador de bordo (km da viagem, média) e **resumo ao chegar** (distância, tempo, média e máxima).
- ⛽ **Autonomia do tanque**: informe quantos km a moto faz por tanque (⚙️) e toque em "Abasteci". O app conta os km, avisa por voz quando estiver acabando e diz se a rota vai precisar de parada para abastecer.
- 🌧️ **Chuva e pôr do sol no caminho**: ao traçar a rota, mostra se há chuva prevista nos pontos do trajeto, na hora em que você vai passar ([Open-Meteo](https://open-meteo.com), grátis), e se vai pegar noite.
- 🚦 **Trânsito ao vivo** ([TomTom](https://developer.tomtom.com)): desvia de vias interditadas e avisa acidentes, obras, alagamentos e trânsito parado.
- 🚨 **Áreas de risco conhecidas** ([app/src/main/assets/areas_risco.csv](app/src/main/assets/areas_risco.csv)): 32 áreas para evitar no Grande Rio (tiroteios frequentes, segundo o Instituto Fogo Cruzado) e 10 de atenção (roubos de moto em SP, homicídios em Porto Alegre). A rota desvia das ruas internas, mas continua usando as vias expressas da borda (Linha Vermelha, Av. Brasil), e a voz avisa "evite parar" ao passar ao lado. Dá para desligar em ⚙️.
- 🧭 **Rota mais segura de verdade** (regra dos motociclistas: *rodovia que passa por fora > rodovia dentro da cidade > rua de bairro*):
  - **Zonas urbanas** ([app/src/main/assets/zonas_risco.csv](app/src/main/assets/zonas_risco.csv)): dentro do Grande Rio e de Grande SP a rota prefere as vias expressas às ruas; nas áreas densas (Baixada, Zona Norte, São Gonçalo, centro de SP) todas as vias pagam, a rodovia menos. Ex.: saindo de Caxias para SP, vai pela Washington Luís → Arco → Dutra em vez de cortar a Baixada; contorna São Paulo por fora.
  - **Rodovias com muitos assaltos** ([app/src/main/assets/trechos_risco.json](app/src/main/assets/trechos_risco.json)): Washington Luís, Rio–Magé, Arco, Linha Amarela, Av. Brasil e Rodoanel ficam **só com aviso por voz** ("evite parar") e as ruas locais em volta ficam caras (o app nunca troca a rodovia pelos bairros vizinhos).
  - **Os 3 modos são diferentes**: ⚡ Rápida = menor tempo (só avisa); 🏍️ Desempenho = rodovia por fora; 🛡️ Segura = idem, mais forte. Quando dois modos dão o mesmo caminho, o cartão mostra "= Desempenho".
  - À noite e no fim de semana o app foge mais das áreas urbanas. O motor BRouter foi adaptado (`CorridorIndex`).

## Como instalar no celular

1. No celular, abra a página **Releases** deste repositório no GitHub.
2. Baixe o arquivo **MotoRota.apk** da versão mais recente.
3. Abra o arquivo. Se o Android pedir, permita "instalar apps desta fonte".
4. Abra o app, permita a localização e toque em **⬇** para baixar os mapas
   (Brasil, Argentina, Chile e Uruguai, cerca de 625 MB; melhor no Wi-Fi).

As atualizações instalam por cima da versão anterior. Os mapas e as áreas de risco são mantidos.

## Como usar

- **Buscar destino:** digite na barra de cima.
- **Tocar e segurar no mapa** abre as opções: ir para cá, passar por aqui, sair daqui, avisar (buraco, polícia…) ou marcar área de risco.
- Embaixo aparecem os 3 modos com km e tempo de cada um. Toque num modo para ver a rota dele e depois em **INICIAR**.
- Durante a viagem, a voz avisa as curvas e os radares. Se você sair da rota, ela é recalculada sozinha.
- O botão amarelo **⚠️** marca um aviso (buraco, polícia…) onde você está.
- **⭐** abre os passeios salvos, e **🌙/☀️** troca o tema do mapa.

## O que funciona sem internet

| Função | Sem internet? |
|---|---|
| Calcular e recalcular rotas | ✅ sim (depois de baixar os mapas) |
| Voz e instruções | ✅ sim |
| Áreas de risco e avisos | ✅ sim (ficam salvos no celular) |
| Radares e limite de velocidade | ✅ sim |
| Desenho do mapa | ⚠️ só as regiões que já foram vistas (fica em cache, até 600 MB) |
| Busca de endereços | ❌ precisa de internet. Sem sinal, marque o destino tocando no mapa |

## Como funciona (parte técnica)

- App Android nativo em Kotlin + Jetpack Compose.
- **Rotas:** [BRouter](https://github.com/abrensch/brouter) (MIT) roda dentro do app, com o perfil próprio
  [`moto.brf`](app/src/main/assets/brouter/moto.brf), onde estão as regras dos 3 modos.
- **Mapa visual:** [MapLibre](https://maplibre.org) + [OpenFreeMap](https://openfreemap.org), grátis e sem chave.
- **Radares:** OpenStreetMap, atualizados todo mês por [.github/workflows/radares.yml](.github/workflows/radares.yml).
- **Busca:** [Photon](https://photon.komoot.io), grátis.
- **Dados de mapa:** © colaboradores do [OpenStreetMap](https://www.openstreetmap.org/copyright).
- O GitHub Actions gera o APK a cada alteração na branch `main` ([.github/workflows/build.yml](.github/workflows/build.yml)).

## Próximos passos

- Avisos e áreas de risco compartilhados entre a família (hoje ficam só no celular de quem marcou).
- Satélite em alta resolução (Esri, com chave gratuita).
- Postos de combustível ao longo da rota.
- Mais áreas de risco (Sul, Nordeste) e atualização automática pelo Fogo Cruzado.
- Mapa visual 100% offline.
