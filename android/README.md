# InstaWasap para Android (APK) 📲

App de Android que descarga videos de Instagram, los manda por WhatsApp y hace **stickers animados**. Todo pasa dentro del celular: yt-dlp, Python y ffmpeg vienen incluidos en la APK, y no hace falta Termux ni ningún servidor.

## Instalar
1. Desde el celular, entrá a **Releases → "InstaWasap (última versión)"**:
   https://github.com/ramireznicolasdaniel-pixel/skills-introduction-to-github/releases/tag/apk-ultima
2. Bajá **`InstaWasap-arm64-v8a.apk`**, que sirve para casi todos los celulares actuales. Si no se instala, probá `InstaWasap-armeabi-v7a.apk`.
3. Abrí el archivo. Si Android te avisa de "orígenes desconocidos", permití instalar desde el navegador o desde Archivos.
4. Si Play Protect avisa que la app es desconocida, tocá **"Instalar de todas formas"**: pasa porque no está publicada en Play Store.

## Usar
- **Desde Instagram:** en un reel tocá **Compartir → InstaWasap** y se descarga solo.
- O abrí la app, pegá el link y tocá **Descargar**.
- **Mandar por WhatsApp:** abre WhatsApp con el video y elegís un chat o **Mi estado**.
- **Achicar a menos de 16 MB:** aparece si el video es muy pesado para WhatsApp.
- **Hacer sticker animado:** elegís el momento y la duración (hasta 10 s). Sale en WebP animado de 512×512 y menos de 500 KB, con los datos de paquete que usa WhatsApp.
- **Si no se puede publicar directo** (WhatsApp no está instalado, no abre o el video es muy pesado), el archivo se guarda solo en la galería: `Movies/InstaWasap` o `Pictures/InstaWasap`.
- yt-dlp se actualiza solo una vez por día, porque Instagram cambia seguido.

## Cómo se compila
GitHub Actions (`.github/workflows/android-apk.yml`) compila la APK en cada cambio dentro de `android/` y la publica en Releases.

Para compilarla en tu PC necesitás JDK 17 y el SDK de Android:
```bash
cd android && ./gradlew assembleRelease
```

> La clave de firma (`app/instawasap.jks`, contraseña `instawasap`) está en el repo para que cada versión se pueda instalar encima de la anterior. Si vas a distribuir la app públicamente, cambiala y guardala como *secret* de GitHub (`KEYSTORE_PASSWORD`).

---

# VideoIA 🎬✨ — videos con inteligencia artificial

Segunda app del mismo proyecto (`android/videoia`). Escribís una idea, la IA de Google (**Veo**) genera un video de unos 8 segundos, y después lo **mandás por WhatsApp**, lo **guardás** o lo convertís en **sticker animado**.

## Instalar
Desde el celular: https://github.com/ramireznicolasdaniel-pixel/skills-introduction-to-github/releases/tag/videoia-ultima

Bajá **`VideoIA-arm64-v8a.apk`**.

## Configurar (una sola vez)
1. Entrá a **Google AI Studio** (https://aistudio.google.com/apikey) con tu cuenta de Google y tocá **Create API key**.
2. **Activá la facturación** en esa cuenta. Generar videos con IA no es gratis: Google cobra cada video.
3. En VideoIA tocá **⚙ Configuración**, pegá la clave y tocá **Guardar clave y buscar modelos**.

La app le pregunta a Google qué modelos de video tiene tu cuenta. Así, si Google da de baja un modelo, la app sigue andando.

## Costo aproximado por video de 8 s
Precios publicados en noviembre de 2026; Google los puede cambiar:

| Modelo | Aprox. |
|---|---|
| Veo Lite | US$ 0,40 |
| Veo Fast (el que se elige por defecto) | US$ 0,80 |
| Veo (calidad máxima) | US$ 3,20 |

## Usar
- Escribí la idea con detalle: quién, qué hace, dónde, estilo y cámara. O tocá una de las ideas de ejemplo.
- **Vertical** sirve para estados de WhatsApp; **Horizontal** para compartir normal.
- La IA tarda entre 1 y 3 minutos. Podés salir de la app: al volver, sigue esperando el video.
- Si la IA rechaza la idea por sus reglas de seguridad, la app te avisa. Pasa, por ejemplo, con personas famosas o con violencia.
- La clave de API se guarda solo en tu celular.
