"""InstaWasap: descarga videos de Instagram y los comparte/envía por WhatsApp."""
import os
import re
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

IG_URL = re.compile(r"^https?://(www\.)?instagram\.com/(p|reel|reels|tv)/[\w-]+", re.I)
ID_OK = re.compile(r"^[0-9a-f]{32}\.mp4$")
MAX_MB_WHATSAPP = 16  # límite de video de WhatsApp

app = Flask(__name__)


def limpiar_viejos(horas: float = 1) -> None:
    """Borra videos descargados hace más de `horas` para no llenar la memoria."""
    limite = time.time() - horas * 3600
    for f in DESCARGAS.iterdir():
        if f.stat().st_mtime < limite:
            f.unlink(missing_ok=True)


def descargar_instagram(url: str) -> dict:
    """Descarga el video con yt-dlp y devuelve nombre de archivo y metadatos."""
    limpiar_viejos()
    nombre = f"{uuid.uuid4().hex}.mp4"
    opciones = {
        "outtmpl": str(DESCARGAS / nombre),
        "format": "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b",
        "merge_output_format": "mp4",
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
    }
    if COOKIES:
        opciones["cookiefile"] = COOKIES
    with YoutubeDL(opciones) as ydl:
        info = ydl.extract_info(url, download=True)
    ruta = DESCARGAS / nombre
    if not ruta.exists():
        raise DownloadError("No se generó el archivo de video")
    return {
        "archivo": nombre,
        "titulo": (info.get("description") or info.get("title") or "Video de Instagram")[:200],
        "autor": info.get("uploader") or info.get("channel") or "",
        "mb": round(ruta.stat().st_size / 1_048_576, 1),
    }


def enviar_whatsapp(ruta: Path, telefono: str, texto: str) -> str:
    """Sube el video a WhatsApp Cloud API y lo envía a un número. Devuelve el id del mensaje."""
    headers = {"Authorization": f"Bearer {WA_TOKEN}"}
    with ruta.open("rb") as f:
        r = requests.post(
            f"{WA_API}/{WA_PHONE_ID}/media",
            headers=headers,
            data={"messaging_product": "whatsapp", "type": "video/mp4"},
            files={"file": (ruta.name, f, "video/mp4")},
            timeout=120,
        )
    r.raise_for_status()
    media_id = r.json()["id"]

    cuerpo = {
        "messaging_product": "whatsapp",
        "to": telefono,
        "type": "video",
        "video": {"id": media_id, "caption": texto[:1024]},
    }
    r = requests.post(f"{WA_API}/{WA_PHONE_ID}/messages", headers=headers, json=cuerpo, timeout=60)
    r.raise_for_status()
    return r.json()["messages"][0]["id"]


@app.get("/")
def inicio():
    return render_template("index.html", api_activa=bool(WA_TOKEN and WA_PHONE_ID))


@app.post("/api/descargar")
def api_descargar():
    url = (request.get_json(silent=True) or {}).get("url", "").strip()
    if not IG_URL.match(url):
        return jsonify(error="Pegá un link de Instagram válido (post, reel o tv)."), 400
    try:
        datos = descargar_instagram(url)
    except DownloadError as e:
        msg = str(e)
        if "login" in msg.lower() or "private" in msg.lower():
            msg = "Este video pide iniciar sesión o es privado. Configurá INSTAGRAM_COOKIES."
        return jsonify(error=f"No se pudo descargar: {msg}"), 502
    datos["supera_limite"] = datos["mb"] > MAX_MB_WHATSAPP
    return jsonify(datos)


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
    if not ID_OK.match(archivo) or not (DESCARGAS / archivo).exists():
        return jsonify(error="Video no encontrado. Descargalo de nuevo."), 404
    if len(telefono) < 8:
        return jsonify(error="Número inválido. Usá formato internacional, ej: 5491122334455."), 400
    try:
        msg_id = enviar_whatsapp(DESCARGAS / archivo, telefono, datos.get("texto", ""))
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
