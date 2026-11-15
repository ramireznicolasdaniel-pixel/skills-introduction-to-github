"""InstaWasap: descarga videos de Instagram y los comparte/envía por WhatsApp."""
import json
import os
import re
import struct
import subprocess
import time
import uuid
from pathlib import Path

import requests
from flask import Flask, abort, jsonify, render_template, request, send_from_directory
from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError

BASE = Path(__file__).parent
DESCARGAS = BASE / "descargas"
DESCARGAS.mkdir(exist_ok=True)

WA_TOKEN = os.getenv("WHATSAPP_TOKEN", "")
WA_PHONE_ID = os.getenv("WHATSAPP_PHONE_NUMBER_ID", "")
WA_API = "https://graph.facebook.com/v21.0"
COOKIES = os.getenv("INSTAGRAM_COOKIES", "")

# Busca un link de Instagram dentro de cualquier texto (Instagram a veces copia texto + link).
IG_EN_TEXTO = re.compile(
    r"https?://(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/(?:[\w.]+/)?"
    r"(?:p|reel|reels|tv|share(?:/reel|/p)?)/[\w-]+[^\s]*",
    re.I,
)
MB_OBJETIVO = 15  # tamaño al que se comprime para entrar en WhatsApp con margen
ID_OK = re.compile(r"^[0-9a-f]{32}\.mp4$")
STICKER_OK = re.compile(r"^[0-9a-f]{32}\.webp$")
# Requisitos de WhatsApp para stickers
STICKER_LADO = 512
STICKER_MAX_KB_ANIMADO = 500
STICKER_MAX_SEG = 10
MAX_MB_WHATSAPP = 16  # límite de video de WhatsApp

app = Flask(__name__)


def limpiar_viejos(horas: float = 1) -> None:
    """Borra videos descargados hace más de `horas` para no llenar la memoria."""
    limite = time.time() - horas * 3600
    for f in DESCARGAS.iterdir():
        if f.stat().st_mtime < limite:
            f.unlink(missing_ok=True)


def extraer_url(texto: str) -> str | None:
    """Devuelve el primer link de Instagram que aparezca en el texto, o None."""
    m = IG_EN_TEXTO.search(texto or "")
    return m.group(0) if m else None


def resolver_share(url: str) -> str:
    """Los links instagram.com/share/... redirigen al reel real; los seguimos."""
    if "/share/" not in url:
        return url
    try:
        r = requests.head(url, allow_redirects=True, timeout=15,
                          headers={"User-Agent": "Mozilla/5.0"})
        return r.url or url
    except requests.RequestException:
        return url


def mb_de(ruta: Path) -> float:
    return round(ruta.stat().st_size / 1_048_576, 1)


def duracion(ruta: Path) -> float:
    salida = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(ruta)],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    return float(salida)


def comprimir(origen: Path, mb_objetivo: float = MB_OBJETIVO) -> Path:
    """Recomprime el video para que pese menos de `mb_objetivo` MB (calcula el bitrate)."""
    segundos = max(duracion(origen), 1.0)
    audio_kbps = 64
    total_kbps = mb_objetivo * 8 * 1024 / segundos * 0.95  # 5% de margen
    video_kbps = max(int(total_kbps - audio_kbps), 100)
    destino = DESCARGAS / f"{uuid.uuid4().hex}.mp4"
    escala = "scale='min(720,iw)':-2"  # máximo 720 px de ancho
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", str(origen), "-vf", escala,
         "-c:v", "libx264", "-preset", "veryfast", "-b:v", f"{video_kbps}k",
         "-maxrate", f"{video_kbps}k", "-bufsize", f"{video_kbps * 2}k",
         "-c:a", "aac", "-b:a", f"{audio_kbps}k", "-movflags", "+faststart", str(destino)],
        check=True, capture_output=True,
    )
    return destino


def agregar_metadatos_sticker(ruta: Path, paquete: str = "InstaWasap", autor: str = "InstaWasap") -> None:
    """Agrega al WebP el bloque EXIF que WhatsApp usa para reconocer stickers (nombre de paquete y autor).

    Es el mismo formato que usan las apps y bots de stickers. No recomprime la imagen.
    """
    datos = bytearray(ruta.read_bytes())
    if datos[:4] != b"RIFF" or datos[8:12] != b"WEBP" or datos[12:16] != b"VP8X":
        return  # solo WebP extendido (los animados siempre lo son)
    info = json.dumps({
        "sticker-pack-id": f"instawasap.{uuid.uuid4().hex[:12]}",
        "sticker-pack-name": paquete,
        "sticker-pack-publisher": autor,
        "emojis": ["😀"],
    }, ensure_ascii=False).encode()
    exif = (b"II*\x00\x08\x00\x00\x00\x01\x00\x41\x57\x07\x00"
            + struct.pack("<I", len(info)) + b"\x16\x00\x00\x00" + info)
    trozo = b"EXIF" + struct.pack("<I", len(exif)) + exif + (b"\x00" if len(exif) % 2 else b"")
    datos[20] |= 0x08                      # marca "tiene EXIF" en el encabezado VP8X
    datos += trozo
    datos[4:8] = struct.pack("<I", len(datos) - 8)  # nuevo tamaño total del RIFF
    ruta.write_bytes(bytes(datos))


def hacer_sticker(origen: Path, inicio: float = 0, segundos: float = 3,
                  recorte: str = "cuadrado") -> Path:
    """Convierte un tramo del video en sticker ANIMADO WebP 512x512 que cumple los límites de WhatsApp.

    recorte="cuadrado" recorta el centro; "completo" muestra todo el cuadro con fondo transparente.
    Si queda muy pesado, baja calidad y fps (y como último recurso la duración) hasta entrar en 500 KB.
    """
    total = duracion(origen)
    inicio = min(max(float(inicio), 0.0), max(total - 1.0, 0.0))
    segundos = max(min(float(segundos), STICKER_MAX_SEG, total - inicio), 0.5)
    lado = STICKER_LADO
    if recorte == "completo":
        ajuste = (f"scale={lado}:{lado}:force_original_aspect_ratio=decrease,format=rgba,"
                  f"pad={lado}:{lado}:(ow-iw)/2:(oh-ih)/2:color=0x00000000")
    else:
        ajuste = f"scale={lado}:{lado}:force_original_aspect_ratio=increase,crop={lado}:{lado}"
    destino = DESCARGAS / f"{uuid.uuid4().hex}.webp"
    limite = (STICKER_MAX_KB_ANIMADO - 2) * 1024  # margen para los metadatos

    # Intentos de más calidad a menos: (calidad, fps). Si se pasa mucho del límite,
    # salta intentos para no perder tiempo (importante en el celular).
    intentos = [(70, 15), (50, 12), (35, 10), (20, 8), (8, 6)]
    i = 0
    while i < len(intentos):
        calidad, fps = intentos[i]
        subprocess.run(
            ["ffmpeg", "-y", "-v", "error", "-ss", f"{inicio:.2f}", "-i", str(origen), "-an",
             "-t", f"{segundos:.2f}", "-vf", f"fps={fps},{ajuste}", "-c:v", "libwebp_anim",
             "-loop", "0", "-q:v", str(calidad), "-compression_level", "4", str(destino)],
            check=True, capture_output=True,
        )
        exceso = destino.stat().st_size / limite
        if exceso <= 1:
            break
        i += 1 if exceso < 1.6 else 2 if exceso < 3 else 3
    else:
        if segundos > 1:  # último recurso: acortar a la mitad
            destino.unlink(missing_ok=True)
            return hacer_sticker(origen, inicio, segundos / 2, recorte)
    agregar_metadatos_sticker(destino)
    return destino


def descargar_instagram(url: str) -> dict:
    """Descarga el video con yt-dlp y devuelve nombre de archivo y metadatos."""
    limpiar_viejos()
    nombre = f"{uuid.uuid4().hex}.mp4"
    opciones = {
        "outtmpl": str(DESCARGAS / nombre),
        "format": "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
        "merge_output_format": "mp4",
        "noplaylist": True,
        "playlist_items": "1",  # posts con varios videos: el primero
        "quiet": True,
        "no_warnings": True,
    }
    if COOKIES:
        opciones["cookiefile"] = COOKIES
    with YoutubeDL(opciones) as ydl:
        info = ydl.extract_info(url, download=True)
    if info.get("entries"):
        info = next((e for e in info["entries"] if e), info)
    ruta = DESCARGAS / nombre
    if not ruta.exists():
        raise DownloadError("No se generó el archivo de video")
    return {
        "archivo": nombre,
        "titulo": (info.get("description") or info.get("title") or "Video de Instagram")[:200],
        "autor": info.get("uploader") or info.get("channel") or "",
        "mb": mb_de(ruta),
    }


def enviar_whatsapp(ruta: Path, telefono: str, texto: str = "", tipo: str = "video") -> str:
    """Sube el archivo a WhatsApp Cloud API y lo envía a un número. Devuelve el id del mensaje.

    tipo="video" manda el MP4; tipo="sticker" manda el WebP como sticker animado.
    """
    mime = "image/webp" if tipo == "sticker" else "video/mp4"
    headers = {"Authorization": f"Bearer {WA_TOKEN}"}
    with ruta.open("rb") as f:
        r = requests.post(
            f"{WA_API}/{WA_PHONE_ID}/media",
            headers=headers,
            data={"messaging_product": "whatsapp", "type": mime},
            files={"file": (ruta.name, f, mime)},
            timeout=120,
        )
    r.raise_for_status()
    media_id = r.json()["id"]

    contenido = {"id": media_id}
    if tipo == "video" and texto:
        contenido["caption"] = texto[:1024]  # los stickers no llevan texto
    cuerpo = {"messaging_product": "whatsapp", "to": telefono, "type": tipo, tipo: contenido}
    r = requests.post(f"{WA_API}/{WA_PHONE_ID}/messages", headers=headers, json=cuerpo, timeout=60)
    r.raise_for_status()
    return r.json()["messages"][0]["id"]


@app.get("/")
def inicio():
    return render_template("index.html", api_activa=bool(WA_TOKEN and WA_PHONE_ID))


@app.post("/api/descargar")
def api_descargar():
    url = extraer_url((request.get_json(silent=True) or {}).get("url", ""))
    if not url:
        return jsonify(error="Pegá un link de Instagram válido (reel, post o tv)."), 400
    try:
        datos = descargar_instagram(resolver_share(url))
    except DownloadError as e:
        return jsonify(error=traducir_error(str(e))), 502
    datos["supera_limite"] = datos["mb"] > MAX_MB_WHATSAPP
    return jsonify(datos)


def traducir_error(msg: str) -> str:
    bajo = msg.lower()
    if "login" in bajo or "private" in bajo or "cookies" in bajo:
        return ("Instagram pide iniciar sesión para este video (puede ser privado o te limitaron). "
                "Probá de nuevo en unos minutos o configurá INSTAGRAM_COOKIES.")
    if "rate" in bajo or "429" in bajo:
        return "Instagram está limitando las descargas. Esperá unos minutos y probá de nuevo."
    if "no video" in bajo or "there is no video" in bajo:
        return "Ese post no tiene video (puede ser solo fotos)."
    if "unable to download" in bajo or "connect" in bajo:
        return "No se pudo conectar con Instagram. Revisá tu conexión a internet."
    return f"No se pudo descargar: {msg.replace('ERROR: ', '')[:300]}"


@app.post("/api/comprimir")
def api_comprimir():
    archivo = (request.get_json(silent=True) or {}).get("archivo", "")
    if not ID_OK.match(archivo) or not (DESCARGAS / archivo).exists():
        return jsonify(error="Video no encontrado. Descargalo de nuevo."), 404
    try:
        nuevo = comprimir(DESCARGAS / archivo)
    except (subprocess.CalledProcessError, ValueError, FileNotFoundError):
        return jsonify(error="No se pudo comprimir el video (¿está instalado ffmpeg?)."), 500
    mb = mb_de(nuevo)
    return jsonify(archivo=nuevo.name, mb=mb, supera_limite=mb > MAX_MB_WHATSAPP)


@app.post("/api/sticker")
def api_sticker():
    datos = request.get_json(silent=True) or {}
    archivo = datos.get("archivo", "")
    if not ID_OK.match(archivo) or not (DESCARGAS / archivo).exists():
        return jsonify(error="Video no encontrado. Descargalo de nuevo."), 404
    try:
        inicio = float(datos.get("inicio", 0))
        segundos = float(datos.get("segundos", 3))
    except (TypeError, ValueError):
        return jsonify(error="Inicio y duración tienen que ser números."), 400
    recorte = "completo" if datos.get("recorte") == "completo" else "cuadrado"
    try:
        st = hacer_sticker(DESCARGAS / archivo, inicio, segundos, recorte)
    except (subprocess.CalledProcessError, ValueError, FileNotFoundError):
        return jsonify(error="No se pudo crear el sticker (¿ffmpeg tiene soporte WebP?)."), 500
    kb = round(st.stat().st_size / 1024)
    return jsonify(sticker=st.name, kb=kb, supera_limite=kb > STICKER_MAX_KB_ANIMADO)


@app.get("/sticker/<archivo>")
def sticker(archivo):
    if not STICKER_OK.match(archivo):
        abort(404)
    return send_from_directory(DESCARGAS, archivo, mimetype="image/webp",
                               as_attachment=request.args.get("dl") == "1",
                               download_name="sticker.webp")


@app.get("/video/<archivo>")
def video(archivo):
    if not ID_OK.match(archivo):
        abort(404)
    return send_from_directory(DESCARGAS, archivo, mimetype="video/mp4",
                               as_attachment=request.args.get("dl") == "1",
                               download_name="instagram.mp4")


@app.post("/api/enviar")
def api_enviar():
    if not (WA_TOKEN and WA_PHONE_ID):
        return jsonify(error="La API de WhatsApp no está configurada."), 400
    datos = request.get_json(silent=True) or {}
    archivo = datos.get("archivo", "")
    telefono = re.sub(r"\D", "", datos.get("telefono", ""))
    tipo = "sticker" if STICKER_OK.match(archivo) else "video"
    if not (ID_OK.match(archivo) or tipo == "sticker") or not (DESCARGAS / archivo).exists():
        return jsonify(error="Archivo no encontrado. Descargalo de nuevo."), 404
    if len(telefono) < 8:
        return jsonify(error="Número inválido. Usá formato internacional, ej: 5491122334455."), 400
    try:
        msg_id = enviar_whatsapp(DESCARGAS / archivo, telefono, datos.get("texto", ""), tipo)
    except requests.HTTPError as e:
        detalle = e.response.json().get("error", {}).get("message", str(e)) if e.response is not None else str(e)
        return jsonify(error=f"WhatsApp rechazó el envío: {detalle}"), 502
    return jsonify(ok=True, mensaje_id=msg_id)


@app.delete("/api/video/<archivo>")
def borrar(archivo):
    if ID_OK.match(archivo):
        (DESCARGAS / archivo).unlink(missing_ok=True)
    return jsonify(ok=True)


if __name__ == "__main__":
    app.run(host=os.getenv("HOST", "127.0.0.1"), port=int(os.getenv("PORT", 5000)), debug=False)
