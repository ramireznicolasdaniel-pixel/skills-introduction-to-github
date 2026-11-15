# InstaWasap 📲

App web que **descarga videos de Instagram** (reels, posts, IGTV) y los **sube a WhatsApp**.

## Cómo funciona
1. Pegás el link de Instagram → el servidor lo descarga con `yt-dlp`.
2. Ves el video y elegís:
   - **Compartir en WhatsApp**: en el celular abre el menú de compartir con el archivo → elegís un chat o tu **estado**. En PC lo guarda y abre WhatsApp Web.
   - **Guardar en el dispositivo**.
   - **Achicar a menos de 16 MB**: si el video es muy pesado para WhatsApp, lo recomprime con ffmpeg para poder publicarlo.
   - **Hacer sticker animado**: elegís el momento y la duración (hasta 10 s), recortado al centro o cuadro completo con fondo transparente. Sale en el formato de sticker animado de WhatsApp (WebP animado 512×512, < 500 KB, con los metadatos de paquete que usa WhatsApp) y lo compartís directo, lo enviás por la API como sticker, o se descarga.
   - **Si no se puede publicar directo** (navegador sin compartir, error de la API o video de más de 16 MB), el video se descarga automáticamente para subirlo a mano desde la galería.
   - **Enviar por WhatsApp API** (opcional): lo manda directo a un número usando la API oficial de WhatsApp Cloud (Meta).

## 📱 En el celular (Android) — lo más fácil
1. Instalá **Termux** desde F-Droid (https://f-droid.org/packages/com.termux/). La versión de Play Store está desactualizada.
2. Abrí Termux, pegá esto y apretá Enter:
```bash
curl -fsSL https://raw.githubusercontent.com/ramireznicolasdaniel-pixel/skills-introduction-to-github/ccr-45ecf061-x7g29h/insta-wasap/termux.sh | bash
```
3. Se abre el navegador con la app. Las próximas veces solo escribí `instawasap` en Termux.

**Atajo:** en Instagram tocá **Compartir** en un reel → **Termux**, y la app se abre con la descarga ya andando.

Dejá Termux abierto mientras usás la app. El botón **Compartir en WhatsApp** te deja elegir un chat o tu estado.

## Instalación en PC
Necesitás Python 3.10+ y `ffmpeg`.

```bash
cd insta-wasap
pip install -r requirements.txt
python app.py
```
Abrí http://localhost:5000 (para abrirla desde otro dispositivo de la misma red: `HOST=0.0.0.0 python app.py` y entrá a `http://IP-de-tu-PC:5000`).

> El botón "Compartir" con archivo en el celular requiere HTTPS (o localhost). Para usarlo desde el teléfono, publicala con HTTPS (Render, Railway, un túnel como `cloudflared`, etc.).

## Envío automático por la API de WhatsApp (opcional)
1. Creá una app en https://developers.facebook.com → producto **WhatsApp**.
2. Copiá el **token** y el **Phone number ID**.
3. Exportá las variables (ver `.env.example`):
```bash
export WHATSAPP_TOKEN=xxxx
export WHATSAPP_PHONE_NUMBER_ID=123456789
python app.py
```
Notas: el número destino debe estar autorizado (en modo prueba) o haberte escrito en las últimas 24 h; límite de video **16 MB**.

## Videos que piden login
Exportá las cookies de Instagram de tu navegador (formato Netscape, por ejemplo con la extensión "Get cookies.txt") y:
```bash
export INSTAGRAM_COOKIES=/ruta/cookies.txt
```

## Links aceptados
Reels, posts, IGTV, links `instagram.com/share/...`, con `?igsh=` y texto pegado que contenga el link. En posts con varios videos baja el primero.

## Stickers: cómo mandarlos para que se vean animados
- Lo mejor: botón **Compartir sticker en WhatsApp** (o **Enviar sticker por WhatsApp API**, que lo manda como sticker animado sí o sí).
- Si se descargó: abrí **Archivos/Descargas**, mantené apretado `sticker.webp` → **Compartir** → **WhatsApp**.
- Evitá "Crear sticker" dentro de WhatsApp con este archivo: puede dejarlo quieto.

## Pruebas
```bash
pip install pillow   # solo para las pruebas
python -m unittest discover -v tests
```

## Uso responsable
Usala con videos propios o con permiso del autor. Descargar y republicar contenido ajeno puede violar derechos de autor y los términos de Instagram.
