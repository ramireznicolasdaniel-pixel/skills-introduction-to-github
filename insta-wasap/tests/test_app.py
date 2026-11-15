"""Pruebas sin conexión a Instagram: links, compresión y API.  Correr: python -m unittest -v"""
import shutil
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import app  # noqa: E402

HAY_FFMPEG = shutil.which("ffmpeg") is not None


class TestLinks(unittest.TestCase):
    def test_links_validos(self):
        casos = {
            "https://www.instagram.com/reel/C5Y3x0qrUGl/?igsh=abc": "https://www.instagram.com/reel/C5Y3x0qrUGl/?igsh=abc",
            "Mirá esto https://instagram.com/p/ABC123/ jaja": "https://instagram.com/p/ABC123/",
            "https://www.instagram.com/share/reel/BAz9_x": "https://www.instagram.com/share/reel/BAz9_x",
            "https://m.instagram.com/usuario.x/reel/XYZ": "https://m.instagram.com/usuario.x/reel/XYZ",
            "https://instagr.am/tv/Q1": "https://instagr.am/tv/Q1",
        }
        for texto, esperado in casos.items():
            self.assertEqual(app.extraer_url(texto), esperado, texto)

    def test_links_invalidos(self):
        for texto in ["", "hola", "https://youtube.com/watch?v=1", "https://www.instagram.com/usuario/",
                      "https://evil.com/instagram.com/reel/x"]:
            self.assertIsNone(app.extraer_url(texto), texto)

    def test_errores_amigables(self):
        self.assertIn("iniciar sesión", app.traducir_error("ERROR: login required"))
        self.assertIn("limitando", app.traducir_error("HTTP Error 429: Too Many Requests"))


@unittest.skipUnless(HAY_FFMPEG, "falta ffmpeg")
class TestComprimir(unittest.TestCase):
    def test_achica_a_menos_de_16mb(self):
        grande = app.DESCARGAS / "prueba_grande.mp4"
        # 20 s de ruido 1080p a 12 Mbps ≈ 30 MB: imposible de comprimir sin bajar bitrate
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "nullsrc=s=1080x1920:d=20,geq=random(1)*255:128:128",
                        "-f", "lavfi", "-i", "sine=d=20", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "12M",
                        "-c:a", "aac", "-shortest", str(grande)], check=True)
        try:
            self.assertGreater(app.mb_de(grande), 16)
            chico = app.comprimir(grande)
            self.assertLess(app.mb_de(chico), 16)
            self.assertAlmostEqual(app.duracion(chico), 20, delta=1)
            chico.unlink()
        finally:
            grande.unlink(missing_ok=True)


@unittest.skipUnless(HAY_FFMPEG, "falta ffmpeg")
class TestSticker(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.video = app.DESCARGAS / ("b" * 32 + ".mp4")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1080x1920:d=12:r=30",
                        "-c:v", "libx264", "-preset", "ultrafast", str(cls.video)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.video.unlink(missing_ok=True)

    def revisar(self, ruta, max_kb, animado):
        from PIL import Image
        with Image.open(ruta) as im:
            self.assertEqual(im.size, (512, 512))
            self.assertEqual(getattr(im, "is_animated", False), animado)
        self.assertLessEqual(ruta.stat().st_size, max_kb * 1024)
        ruta.unlink()

    def test_animado_cuadrado_10s(self):
        self.revisar(app.hacer_sticker(self.video, 1, 10, True, "cuadrado"), 500, True)

    def test_animado_completo_transparente(self):
        st = app.hacer_sticker(self.video, 0, 3, True, "completo")
        from PIL import Image
        with Image.open(st) as im:
            self.assertEqual(im.convert("RGBA").getpixel((0, 0))[3], 0)  # esquina transparente
        self.revisar(st, 500, True)

    def test_estatico(self):
        self.revisar(app.hacer_sticker(self.video, 5, 1, False, "cuadrado"), 100, False)

    def test_limites_fuera_de_rango(self):
        # inicio pasado el final y duración enorme no deben romper
        self.revisar(app.hacer_sticker(self.video, 999, 60, True, "cuadrado"), 500, True)

    def test_api_sticker(self):
        c = app.app.test_client()
        r = c.post("/api/sticker", json={"archivo": self.video.name, "inicio": 2, "segundos": 3})
        self.assertEqual(r.status_code, 200, r.json)
        self.assertFalse(r.json["supera_limite"])
        g = c.get(f"/sticker/{r.json['sticker']}")
        self.assertEqual(g.mimetype, "image/webp")
        g.close()
        (app.DESCARGAS / r.json["sticker"]).unlink()
        self.assertEqual(c.get("/sticker/..%2Fapp.py").status_code, 404)
        self.assertEqual(c.post("/api/sticker", json={"archivo": self.video.name, "inicio": "x"}).status_code, 400)


class TestAPI(unittest.TestCase):
    def setUp(self):
        self.c = app.app.test_client()

    def test_rechaza_link_no_instagram(self):
        r = self.c.post("/api/descargar", json={"url": "https://youtube.com/x"})
        self.assertEqual(r.status_code, 400)

    def test_descarga_ok(self):
        falso = {"archivo": "a" * 32 + ".mp4", "titulo": "t", "autor": "u", "mb": 20.0}
        with mock.patch.object(app, "descargar_instagram", return_value=falso) as m:
            r = self.c.post("/api/descargar", json={"url": "mirá https://www.instagram.com/reel/abc/"})
        self.assertEqual(r.status_code, 200)
        self.assertTrue(r.json["supera_limite"])
        m.assert_called_once_with("https://www.instagram.com/reel/abc/")

    def test_error_de_instagram(self):
        from yt_dlp.utils import DownloadError
        with mock.patch.object(app, "descargar_instagram", side_effect=DownloadError("login required")):
            r = self.c.post("/api/descargar", json={"url": "https://www.instagram.com/reel/abc/"})
        self.assertEqual(r.status_code, 502)
        self.assertIn("iniciar sesión", r.json["error"])

    def test_no_sirve_archivos_fuera(self):
        self.assertEqual(self.c.get("/video/..%2Fapp.py").status_code, 404)
        self.assertEqual(self.c.post("/api/comprimir", json={"archivo": "../app.py"}).status_code, 404)


if __name__ == "__main__":
    unittest.main()
