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
