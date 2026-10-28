#!/usr/bin/env bash
# Instala "Mi Launcher TV" (CleanYourTV con los cambios de esta rama) en otra TV Android.
#
# Uso, en Git Bash y desde la carpeta del repo:
#   mi-launcher/instalar.sh <ip de la TV> [carpeta con los APK]
#   DRY_RUN=1 mi-launcher/instalar.sh 192.168.1.50      # muestra lo que haría, sin tocar la TV
#
# No desactiva ni desinstala nada en la TV. Todo se deshace (ver mi-launcher/LEEME.md).
set -euo pipefail
cd "$(dirname "$0")/.."
TV_HOST="${1:?Uso: mi-launcher/instalar.sh <ip de la TV> [carpeta con los APK]}"
APKS="${2:-mi-launcher/apk}"
export TV_HOST MSYS_NO_PATHCONV=1
DRY="${DRY_RUN:-0}"
HOME_PKG=com.gathin.tvhome
BRIGHT_PKG=io.github.maxhayim.brightness

PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
if [ ! -x "$PY" ] && [ "$DRY" != 1 ]; then
  echo "== Preparando las herramientas (solo la primera vez)"
  python -m venv tools/venv 2>/dev/null || python3 -m venv tools/venv
  PY=tools/venv/bin/python; [ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
  "$PY" -m pip install -q -r tools/requirements.txt
fi

tv() {
  if [ "$DRY" = 1 ]; then echo "  [tv] $*"; return 0; fi
  "$PY" -I tools/tv.py "$@"
}
push() {
  if [ "$DRY" = 1 ]; then echo "  [push] $1 -> $2"; return 0; fi
  "$PY" -I tools/b64push.py "$1" "$2"
}

[ -f "$APKS/launcher.apk" ] || { echo "Falta $APKS/launcher.apk (viene en el .zip, o compilalo: ver LEEME.md)" >&2; exit 1; }

echo "== 1/8 Conectando con $TV_HOST. Si la TV pregunta \"¿Permitir depuración USB?\", aceptá."
tv --probe
SDK=$(tv 'getprop ro.build.version.sdk' | tr -dc '0-9' || true)
if [ "$DRY" != 1 ] && [ -n "$SDK" ] && [ "$SDK" -lt 26 ]; then
  echo "Esta TV tiene Android con SDK $SDK; el launcher necesita 26 (Android 8) o más. No se cambió nada." >&2; exit 1
fi

echo "== 2/8 Copia de seguridad del estado actual"
B="backup/$(printf '%s' "$TV_HOST" | tr -c 'A-Za-z0-9._-' '_')-$(date +%Y%m%d-%H%M)"
if [ "$DRY" != 1 ]; then
  mkdir -p "$B"
  tv 'pm list packages -f' > "$B/packages.txt"
  tv 'pm list packages -d' > "$B/disabled.txt"
  tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' > "$B/home-before.txt"
  for ns in global secure system; do tv "settings list $ns" > "$B/settings-$ns.txt"; done
  echo "   guardada en $B"
fi

echo "== 3/8 Instalando el launcher"
push "$APKS/launcher.apk" /data/local/tmp/tvhome.apk
OUT=$(tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk')
echo "$OUT"
case "$OUT" in *Success*|*"[tv]"*) ;; *) echo "ERROR: la instalación falló; no se cambió el inicio." >&2; exit 1 ;; esac

echo "== 4/8 Probando que el launcher abra, y dejándolo como inicio"
START=$(tv "am force-stop $HOME_PKG; am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $HOME_PKG/.MainActivity")
echo "$START"
if [ "$DRY" != 1 ] && ! printf '%s' "$START" | grep -qF 'Status: ok'; then
  echo "ERROR: el launcher no arrancó; el inicio de la TV quedó como estaba." >&2; exit 1
fi
tv "cmd package set-home-activity --user 0 $HOME_PKG/.MainActivity"
NOW=$(tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1 | tr -d '\r')
case "$NOW" in
  $HOME_PKG/*|*"[tv]"*) echo "   el inicio ahora es $NOW" ;;
  *) echo "AVISO: el inicio sigue siendo '$NOW'. Apretá Inicio en el control y elegí \"CleanYourTV\" como predeterminado." ;;
esac

echo "== 5/8 Aplicando el tema (video de fondo, dock, clima, panel ↓)"
if [ "$DRY" = 1 ]; then echo "  [theme] mi-launcher/tema"; else tools/theme.sh mi-launcher/tema; fi

echo "== 6/8 Protector de pantalla Reloj, inicio al encender y animaciones más rápidas"
tv "settings put secure screensaver_components $HOME_PKG/.ClockDream"
tv 'settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5'
SVC=$HOME_PKG/.HomeKeyService
tv "cmd appops set $HOME_PKG AUTO_START allow 2>/dev/null; cmd appops set $HOME_PKG APP_AUTO_START allow 2>/dev/null; appops write-settings 2>/dev/null; s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *HomeKeyService*) settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services \"\$s\" ;; null|'') settings put secure enabled_accessibility_services $SVC ;; *) settings put secure enabled_accessibility_services \"\$s:$SVC\" ;; esac; settings put secure accessibility_enabled 1; echo ayudante-activado"

echo "== 7/8 Brillo nocturno (complemento Brightness)"
if [ -f "$APKS/brightness.apk" ]; then
  push "$APKS/brightness.apk" /data/local/tmp/brightness.apk
  OUT=$(tv 'pm install -r /data/local/tmp/brightness.apk; rm -f /data/local/tmp/brightness.apk')
  echo "$OUT"
  tv "appops set $BRIGHT_PKG SYSTEM_ALERT_WINDOW allow; cmd appops set $BRIGHT_PKG APP_AUTO_START allow 2>/dev/null; am start -n $BRIGHT_PKG/.MainActivity >/dev/null; echo brillo-instalado"
else
  echo "   (no está $APKS/brightness.apk; se saltea)"
fi

echo "== 8/8 Listo"
tv 'input keyevent HOME'
cat <<'EOF'

Hecho. Revisá en la TV:
  - Apretá ↓ desde el dock: aparece el panel con pronóstico, Buscar, Todas las apps y Recientes.
  - El clima es de Villa Ángela: para otra ciudad, cambiá weather_lat/weather_lon en mi-launcher/tema/config.json
    y volvé a correr:  tools/theme.sh mi-launcher/tema
  - Las apps del dock salen de "dock_apps" en ese mismo archivo (si una no está instalada, se saltea).

Opcional (no se hizo): las listas de mi-launcher/listas-noblex/ son SOLO para la Noblex. En otra marca, no las uses
sin revisarlas. Cuando termines, apagá la "Depuración USB" en la TV.
EOF
