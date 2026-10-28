# Mi Launcher TV

Esta es mi versión de [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (licencia MIT, créditos a su autor y
colaboradores), con estos agregados:

- **Videos de fondo** que rotan cada 5 minutos, más oscuros de noche, con opción Sí/No en Ajustes del launcher (⌂).
  Son 6 videos gratuitos de [Mixkit](https://mixkit.co/) (licencia libre de Mixkit), dentro del launcher.
- **Temperatura** debajo de la fecha (Open-Meteo, sin cuenta; solo se envían las coordenadas de la ciudad).
- **Panel ↓**: al apretar abajo desde el dock aparece el pronóstico (por hora / por día), "Buscar" (búsqueda
  universal de Google TV), "Todas las apps" y "Recientes" (apps usadas en los últimos 14 días y hace cuánto).
- **Menú de arriba a la derecha** en la esquina, casi transparente hasta que pasás el control.
- **Biblioteca sin apps del sistema** (`library_hidden_apps`), **logo de YouTube** para SmartTube, y el recuadro de
  selección que vuelve a su lugar después del protector de pantalla.
- **Brillo nocturno** (complemento `addons/brightness`): desde las 23 h baja de a poco en 45 minutos hasta 60 %,
  y a las 6 h vuelve a la normalidad. Se prende y apaga desde ⌂ > "Brillo nocturno…".

## Qué hay en el .zip

| Carpeta | Qué es |
|---|---|
| `cleanyourtv/` | Todo el código (rama `mi-launcher`), con las herramientas para instalar. |
| `cleanyourtv/mi-launcher/apk/launcher.apk` | El launcher ya compilado. |
| `cleanyourtv/mi-launcher/apk/brightness.apk` | El complemento de brillo nocturno ya compilado. |
| `cleanyourtv/mi-launcher/tema/config.json` | El tema: dock, videos, clima, panel ↓, apps ocultas. |
| `cleanyourtv/mi-launcher/listas-noblex/` | Lo que desactivé en la Noblex. **Solo sirve para ese modelo.** |
| `cleanyourtv/mi-launcher/instalar.sh` | Instalador para otra TV. |
| `cleanyourtv/mi-launcher/cambios.patch` | Todos mis cambios sobre el proyecto original, en un archivo. |

## Instalar en otra TV (Android TV / Google TV, Android 8 o más)

1. **En la TV**: Ajustes > Preferencias del dispositivo > Información > apretá OK 7 veces sobre "Compilación".
   Volvé atrás > Opciones de desarrollador > **Depuración USB** en Sí (y "Depuración por red/ADB" si aparece).
2. **La IP de la TV**: Ajustes > Red e Internet > tu Wi-Fi > Dirección IP (por ejemplo 192.168.1.50).
3. **En la PC** (Windows, con [Git para Windows](https://git-scm.com/) y [Python 3](https://www.python.org/)):
   descomprimí el .zip, abrí **Git Bash** dentro de la carpeta `cleanyourtv` y ejecutá:

   ```bash
   mi-launcher/instalar.sh 192.168.1.50
   ```

   Cuando la TV pregunte "¿Permitir depuración USB?", marcá "Permitir siempre" y aceptá.
   Para ver qué haría sin tocar nada: `DRY_RUN=1 mi-launcher/instalar.sh 192.168.1.50`.
4. Al terminar, **apagá la Depuración USB** en la TV.

El instalador hace una copia de seguridad en `backup/`, instala el launcher y lo pone como inicio (solo si arranca
bien), aplica el tema, pone el protector Reloj, activa "inicio al encender", acelera las animaciones e instala el
brillo nocturno. **No desactiva ni desinstala nada.**

## Adaptarlo a otra casa

Editá `mi-launcher/tema/config.json` y volvé a aplicar con `tools/theme.sh mi-launcher/tema`:

- **Ciudad del clima**: `weather_lat` y `weather_lon` (buscalas en
  `https://geocoding-api.open-meteo.com/v1/search?name=TU+CIUDAD&language=es`).
- **Apps del dock**: `dock_apps`, con los nombres de paquete (los ves con
  `tools/venv/Scripts/python tools/tv.py "pm list packages -3"`).
- **Apps ocultas**: `hidden_apps` (inicio) y `library_hidden_apps` (biblioteca). Los de la Noblex no molestan en otra TV.
- **Videos**: `video_wallpaper` (true/false), `video_rotation_minutes`, `video_dim`, `video_dim_evening`, `video_dim_night`.

## Deshacer

- Volver al launcher anterior: Ajustes > Apps > CleanYourTV > Desinstalar (o elegí el otro inicio cuando Android pregunte).
- Brillo nocturno: Ajustes > Apps > Brightness > Desinstalar.
- Animaciones: Opciones de desarrollador > las 3 "Escala de animación" en 1x.
- En la Noblex, lo desactivado se reactiva con `tools/restore.sh mi-launcher/listas-noblex/<lista>.txt`.

## Compilar desde el código

Hace falta **Java 17 (JDK)** y el **Android SDK** (plataforma 34 y build-tools 34.0.0). En mi PC están en
`C:\Users\Ale\Desktop\launcher\buildtools` (no van en el .zip). Con eso:

```bash
export JAVA_HOME=/c/ruta/al/jdk-17 ANDROID_HOME=/c/ruta/al/sdk
cd launcher && ./gradlew assembleRelease        # -> launcher/app/build/outputs/apk/release/app-release.apk
cd ../addons/brightness && ./gradlew assembleRelease
```

Si tu antivirus revisa las conexiones HTTPS (por ejemplo Avast) y Gradle falla con "PKIX path building failed",
agregá `export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` antes de compilar.
