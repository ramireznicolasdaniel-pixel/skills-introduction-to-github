package com.instawasap.comun

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StickerTest {
    private fun le32(b: ByteArray, i: Int) = ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /** WebP extendido mínimo (encabezado VP8X + un trozo ANIM) para probar los metadatos. */
    private fun webpFalso(): ByteArray {
        val vp8x = "VP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + byteArrayOf(0x12, 0, 0, 0, 0xFF.toByte(), 1, 0, 0xFF.toByte(), 1, 0)
        val anim = "ANIM".toByteArray() + byteArrayOf(6, 0, 0, 0) + ByteArray(6)
        val cuerpo = "WEBP".toByteArray() + vp8x + anim
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cuerpo.size).array() + cuerpo
    }

    @Test fun agregaExifDeWhatsApp() {
        val salida = Sticker.agregarMetadatos(webpFalso())!!
        assertEquals(salida.size - 8, le32(salida, 4))            // RIFF íntegro
        assertTrue(salida[20].toInt() and 0x08 != 0)              // bandera EXIF
        assertEquals(0x12, salida[20].toInt() and 0x12)           // conserva banderas anteriores (animación/alfa)
        val texto = String(salida, Charsets.UTF_8)
        val json = JSONObject(texto.substring(texto.indexOf('{'), texto.lastIndexOf('}') + 1))
        assertEquals("InstaWasap", json.getString("sticker-pack-name"))
        assertEquals(0, salida.size % 2)                          // trozos RIFF alineados
    }

    @Test fun rechazaNoWebp() {
        assertNull(Sticker.agregarMetadatos("hola mundo, esto no es una imagen webp".toByteArray()))
    }

    @Test fun tramo() {
        assertEquals(2f to 3f, Sticker.ajustarTramo(15f, 2f, 3f))
        assertEquals(14f to 1f, Sticker.ajustarTramo(15f, 999f, 60f))   // inicio fuera de rango
        assertEquals(0f to 10f, Sticker.ajustarTramo(30f, -5f, 60f))    // máximo 10 s
    }

    @Test fun bitrate() {
        val kbps = bitrateParaPeso(60.0)
        val mb = (kbps + 64) * 60 / 8.0 / 1024
        assertTrue("pesa $mb MB", mb < 16)
        assertEquals(100, bitrateParaPeso(100000.0)) // mínimo
    }
}
