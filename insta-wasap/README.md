# InstaWasap 📲

App web que **descarga videos de Instagram** (reels, posts, IGTV) y los **sube a WhatsApp**.

## Cómo funciona
1. Pegás el link de Instagram → el servidor lo descarga con `yt-dlp`.
2. Ves el video y elegís:
   - **Compartir en WhatsApp**: en el celular abre el menú de compartir con el archivo → elegís un chat o tu **estado**. En PC lo guarda y abre WhatsApp Web.
   - **Guardar en el dispositivo**.
   - **Si no se puede publicar directo** (navegador sin compartir, error de la API o video de más de 16 MB), el video se descarga automáticamente para subirlo a mano desde la galería.
   - **Enviar por WhatsApp API** (opcional): lo manda directo a un número usando la API oficial de WhatsApp Cloud (Meta).

## Instalación
Necesitás Python 3.10+ y `ffmpeg`.

```bash
cd insta-wasap
pip install -r requirements.txt
python app.py
```
Abrí http://localhost:5000 (desde el celular en la misma red: `http://IP-de-tu-PC:5000`).

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

## Uso responsable
Usala con videos propios o con permiso del autor. Descargar y republicar contenido ajeno puede violar derechos de autor y los términos de Instagram.
