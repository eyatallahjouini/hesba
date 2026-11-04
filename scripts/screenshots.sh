#!/usr/bin/env bash
# Captures d'écran de HESBA sur l'émulateur (docs/screenshots/<langue>-<thème>/).
# Les données affichées viennent d'une base EN MÉMOIRE créée par le test ScreenshotTour :
# les vraies données de l'appli sur l'émulateur ne sont ni lues ni modifiées.
# Usage : scripts/screenshots.sh [variantes...]   ex. scripts/screenshots.sh fr:0 ar:1   (0 = clair, 1 = sombre)
set -e
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
VARIANTS="${*:-fr:0 fr:1 ar:0 ar:1}"
PKG=com.example.hesba
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

# Sauvegarde des préférences et de la langue de l'appli : remises à l'identique à la fin, même si un écran plante.
LOCALES="$("$ADB" shell cmd locale get-app-locales $PKG | tr -d '\r' | sed -n 's/.*are \[\(.*\)\].*/\1/p')"
echo "langue de l'appli avant les captures : [$LOCALES]"
"$ADB" shell "run-as $PKG sh -c 'rm -rf files/.prefs_backup; mkdir -p files/.prefs_backup; cp -r shared_prefs/. files/.prefs_backup/ 2>/dev/null; true'"
restore() {
  "$ADB" shell am force-stop $PKG
  "$ADB" shell "run-as $PKG sh -c 'rm -rf shared_prefs; mkdir shared_prefs; cp -r files/.prefs_backup/. shared_prefs/ 2>/dev/null; rm -rf files/.prefs_backup; true'"
  "$ADB" shell cmd locale set-app-locales $PKG --locales "$LOCALES"
  echo "préférences et langue de l'appli restaurées : [$("$ADB" shell cmd locale get-app-locales $PKG | tr -d '\r' | sed -n 's/.*are \[\(.*\)\].*/\1/p')]"
}
trap restore EXIT

for v in $VARIANTS; do
  # garde-fou : l'émulateur grossit sur le disque ; on s'arrête avant de le remplir
  FREE_MB=$(powershell -NoProfile -Command "[int]((Get-PSDrive C).Free/1MB)" | tr -d '\r')
  if [ "${FREE_MB:-0}" -lt 1500 ]; then echo "ARRÊT : seulement ${FREE_MB} Mo libres sur C:"; exit 1; fi
  lang="${v%%:*}"; night="${v##*:}"; theme=$([ "$night" = 1 ] && echo dark || echo light)
  echo "   (${FREE_MB} Mo libres)"
  echo "== $lang $theme"
  "$ADB" shell am instrument -w -e class com.example.hesba.ScreenshotTour -e lang "$lang" -e night "$night" \
    $PKG.test/androidx.test.runner.AndroidJUnitRunner | tail -3
  rm -rf "docs/screenshots/$lang-$theme"; mkdir -p "docs/screenshots/$lang-$theme"
  MSYS_NO_PATHCONV=1 "$ADB" pull "/sdcard/Android/data/$PKG/files/tour/$lang-$theme/." "docs/screenshots/$lang-$theme/" >/dev/null
done
