#!/bin/bash
# Abre todas as telas/dialogos do app e confere se nada fecha.
export MSYS_NO_PATHCONV=1 ANDROID_SERIAL=emulator-5554
A="C:/Users/tikto/Android/Sdk/platform-tools/adb.exe"
S="C:/Users/tikto/AppData/Local/Temp/claude/dc91bd8b5b4cc490/7f83db01-7265-4929-ae02-fe4cbf0b6d75/scratchpad"

alive() {
  local pid=$($A shell pidof com.motorota | tr -d '\r')
  local c=$($A logcat -d -b crash | grep -c "FATAL EXCEPTION")
  if [ -z "$pid" ] || [ "$c" != "0" ]; then echo "  !! FECHOU em: $1"; $A logcat -d -b crash | grep -E "Exception|at com.motorota" | head -4; exit 1; fi
  echo "  ok: $1"
}
# toca no elemento cujo texto comeca com $1
tapt() {
  $A shell uiautomator dump /sdcard/u.xml >/dev/null
  local b=$($A shell cat /sdcard/u.xml | grep -o "text=\"[^\"]*$1[^\"]*\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  if [ -z "$b" ]; then echo "  ?? nao achei: $1"; return 1; fi
  local xy=$(echo $b | sed 's/\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')
  $A shell input tap $xy
}
longpress() { $A shell input swipe $1 $2 $1 $2 1500; sleep 2; }

cd C:/Users/tikto/MotoRota
$A shell am force-stop com.motorota
$A logcat -c; $A logcat -c -b crash
$A shell am start -n com.motorota/.MainActivity >/dev/null; sleep 9
$A shell uiautomator dump /sdcard/u.xml >/dev/null; $A shell cat /sdcard/u.xml | grep -q "Nova versão" && { $A shell input tap 948 518; sleep 1; echo "  (fechei aviso de atualizacao)"; }
$A emu geo fix -43.1729 -22.9068 >/dev/null; sleep 2
alive "abrir o app"

$A shell input tap 970 236; sleep 2; alive "tela de mapas"; tapt "OK"; sleep 1

longpress 540 1000; alive "menu tocar e segurar"
tapt "Avisar: buraco"; sleep 2; alive "menu de avisos"
tapt "Buraco"; sleep 2; alive "marcar buraco"
longpress 540 1000; tapt "Marcar área de risco"; sleep 2; alive "tamanho da area de risco"
tapt "Pequena"; sleep 2; alive "marcar area de risco"
longpress 540 1000; tapt "Remover aviso"; sleep 2; alive "remover aviso"
longpress 540 1000; tapt "Remover esta área"; sleep 2; alive "remover area de risco"

tapt "⭐"; sleep 2; alive "passeios"; tapt "Fechar"; sleep 1
tapt "☀️" || tapt "&#127769;"; sleep 3; alive "trocar tema"; $A exec-out screencap -p > "$S/sw_noite.png"
tapt "☀️" || tapt "&#127769;"; sleep 2
tapt "&#128752;"; sleep 4; alive "satelite ligado"; $A exec-out screencap -p > "$S/sw_sat.png"
tapt "&#128752;"; sleep 2; alive "satelite desligado"

longpress 420 1200; tapt "Ir para cá"; sleep 2
for i in $(seq 1 30); do sleep 2; n=$($A logcat -d -s MotoRota | grep -c "rota "); [ "$n" -ge 3 ] && break; done
alive "tracar rota"
tapt "Salvar passeio"; sleep 2; alive "salvar passeio"; tapt "Salvar\"" ; sleep 2; alive "passeio salvo"
tapt "Ida e volta"; sleep 6; alive "ida e volta"
tapt "Editar caminho"; sleep 2; alive "modo edicao"
$A shell input tap 300 800; sleep 6; alive "adicionar ponto"
tapt "Desfazer"; sleep 4; alive "desfazer"
tapt "Pronto"; sleep 8; alive "pronto"
tapt "⭐"; sleep 2; $A exec-out screencap -p > "$S/sw_trips.png"; alive "lista de passeios"; tapt "Fechar"; sleep 1
tapt "INICIAR"; sleep 4; alive "iniciar navegacao"
$A shell input tap 952 1924; sleep 2; alive "botao avisar na navegacao"; tapt "Cancelar"; sleep 1
tapt "Sair"; sleep 2; alive "sair da navegacao"
tapt "Cancelar"; sleep 2; alive "cancelar rota"
echo "VARREDURA COMPLETA SEM FECHAR"
