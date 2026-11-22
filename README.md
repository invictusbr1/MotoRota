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
- Interdições e obras em tempo real (TomTom, com chave gratuita).
- Satélite em alta resolução (Esri, com chave gratuita).
- Dados públicos de violência (ex.: Fogo Cruzado no Rio).
- Postos de combustível ao longo da rota e aviso de autonomia do tanque.
- Mapa visual 100% offline.
