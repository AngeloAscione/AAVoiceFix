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

SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$SDK" -le 32 ]]; then
  echo "== READ_EXTERNAL_STORAGE (Android <= 12) =="
  adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE || true
fi

echo "== abilita l'AccessibilityService preservando quelle esistenti =="
CURRENT="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$CURRENT" in
  *"$SERVICE"*) ;;
  null|"") adb shell settings put secure enabled_accessibility_services "$SERVICE" ;;
  *) adb shell settings put secure enabled_accessibility_services "$CURRENT:$SERVICE" ;;
esac
adb shell settings put secure accessibility_enabled 1

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
echo
echo "Fatto. Log runtime: adb logcat -s AAWaFixer"
