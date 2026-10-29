#!/usr/bin/env bash
# Setup one-shot via ADB USB. Dopo questo script l'app e' autonoma:
# nessun ADB, Shizuku o root necessario a runtime.
set -euo pipefail

PKG="dev.local.aawafixer"
SERVICE="$PKG/$PKG.WaFixAccessibilityService"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK" ]]; then
  echo "APK non trovato: $APK. Esegui prima ./gradlew :app:assembleDebug" >&2
  exit 1
fi

adb wait-for-device
echo "== install =="
adb install -r "$APK"

echo "== MANAGE_EXTERNAL_STORAGE (accesso diretto alle note vocali) =="
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow

enable_accessibility() {
  local current
  current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
  case "$current" in
    *"$SERVICE"*) ;;
    null|"") adb shell settings put secure enabled_accessibility_services "$SERVICE" ;;
    *) adb shell settings put secure enabled_accessibility_services "$CURRENT:$SERVICE" ;;
  esac
  adb shell settings put secure accessibility_enabled 1
}

accessibility_enabled() {
  local current
  current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
  [[ "$current" == *"$SERVICE"* ]]
}

echo "== abilita l'AccessibilityService preservando quelle esistenti =="
# Subito dopo l'install il sistema puo' non aver ancora registrato il
# componente: si verifica e si ritenta una volta.
sleep 1
enable_accessibility
sleep 1
if ! accessibility_enabled; then
  echo "Primo tentativo non persistito, riprovo..." >&2
  sleep 2
  enable_accessibility
  sleep 1
fi

echo "== avvio app =="
adb shell am start -n "$PKG/.MainActivity" >/dev/null

echo
echo "== verifica =="
printf 'MANAGE_EXTERNAL_STORAGE: '
adb shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE | tr -d '\r'
printf 'accessibility_enabled: '
adb shell settings get secure accessibility_enabled | tr -d '\r'
printf 'enabled_accessibility_services: '
adb shell settings get secure enabled_accessibility_services | tr -d '\r'

if ! accessibility_enabled; then
  echo >&2
  echo "ERRORE: il servizio di accessibilita' non risulta abilitato." >&2
  echo "Abilitalo a mano: Impostazioni > Accessibilita' > AA Voice Fix." >&2
  exit 1
fi

echo
echo "Fatto. Log runtime: adb logcat -s AAWaFixer"
echo "Nota: non usare 'adb shell am force-stop $PKG': Android disabilita il"
echo "servizio di accessibilita' del pacchetto. In quel caso rilancia questo script."
