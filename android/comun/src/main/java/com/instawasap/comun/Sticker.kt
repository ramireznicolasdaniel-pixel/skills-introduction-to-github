package com.instawasap.comun

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Requisitos de WhatsApp para stickers animados y utilidades. */
object Sticker {
    const val LADO = 512
    const val MAX_KB = 500
    const val MAX_SEG = 10f

    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /**
     * Agrega al WebP el bloque EXIF que WhatsApp usa para reconocer stickers
     * (nombre de paquete y autor). Mismo formato que usan las apps de stickers. No recomprime.
     * Devuelve false si el archivo no es un WebP extendido (los animados siempre lo son).
     */
    fun agregarMetadatos(datos: ByteArray, paquete: String = "InstaWasap", autor: String = "InstaWasap"): ByteArray? {
        fun tag(i: Int) = String(datos, i, 4, Charsets.US_ASCII)
        if (datos.size < 30 || tag(0) != "RIFF" || tag(8) != "WEBP" || tag(12) != "VP8X") return null
        val json = """{"sticker-pack-id": "instawasap.${UUID.randomUUID().toString().take(12)}", """ +
            """"sticker-pack-name": "$paquete", "sticker-pack-publisher": "$autor", "emojis": ["😀"]}"""
        val info = json.toByteArray(Charsets.UTF_8)
        val cabecera = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x41, 0x57, 0x07, 0x00,
        )
        val exif = cabecera + le32(info.size) + byteArrayOf(0x16, 0x00, 0x00, 0x00) + info
        var trozo = "EXIF".toByteArray(Charsets.US_ASCII) + le32(exif.size) + exif
        if (exif.size % 2 == 1) trozo += byteArrayOf(0)
        val salida = datos.copyOf() + trozo
        salida[20] = (salida[20].toInt() or 0x08).toByte() // bandera "tiene EXIF" en VP8X
        le32(salida.size - 8).copyInto(salida, 4)          // nuevo tamaño del RIFF
        return salida
    }

    fun agregarMetadatos(archivo: File) {
        agregarMetadatos(archivo.readBytes())?.let { archivo.writeBytes(it) }
    }

    /** Filtro de ffmpeg para dejar el cuadro en 512x512. */
    fun filtroRecorte(completo: Boolean): String =
        if (completo) {
            "scale=$LADO:$LADO:force_original_aspect_ratio=decrease,format=rgba," +
                "pad=$LADO:$LADO:(ow-iw)/2:(oh-ih)/2:color=0x00000000"
        } else {
            "scale=$LADO:$LADO:force_original_aspect_ratio=increase,crop=$LADO:$LADO"
        }

    /** Ajusta inicio y duración a lo que permite el video y WhatsApp. */
    fun ajustarTramo(totalSeg: Float, inicio: Float, segundos: Float): Pair<Float, Float> {
        val ini = inicio.coerceIn(0f, maxOf(totalSeg - 1f, 0f))
        val dur = minOf(segundos, MAX_SEG, totalSeg - ini).coerceAtLeast(0.5f)
        return ini to dur
    }

    /** Cuántos intentos saltear según cuánto se pasó del límite. */
    fun salto(exceso: Double): Int = when {
        exceso < 1.6 -> 1
        exceso < 3 -> 2
        else -> 3
    }

    /** (calidad, fps) de más calidad a menos. */
    val INTENTOS = listOf(70 to 15, 50 to 12, 35 to 10, 20 to 8, 8 to 6)
}

/** Bitrate de video (kbps) para que un video de [segundos] pese menos de [mbObjetivo] MB. */
fun bitrateParaPeso(segundos: Double, mbObjetivo: Double = 15.0, audioKbps: Int = 64): Int {
    val total = mbObjetivo * 8 * 1024 / maxOf(segundos, 1.0) * 0.95
    return maxOf((total - audioKbps).toInt(), 100)
}
