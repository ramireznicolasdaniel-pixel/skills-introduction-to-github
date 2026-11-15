package com.instawasap.videoia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.instawasap.comun.Procesar
import java.io.ByteArrayOutputStream
import java.io.File

/** Prepara imágenes y cuadros de video para mandarlos a la IA como punto de partida. */
object Partida {
    private const val MAX_LADO = 1280 // suficiente para la IA y liviano para mandar

    /** Copia un archivo elegido (galería, otra app) a la carpeta de la app. */
    fun copiar(ctx: Context, uri: Uri, extension: String): File {
        val destino = File(Procesar.carpeta(ctx), Procesar.nuevoId() + "." + extension)
        ctx.contentResolver.openInputStream(uri)!!.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
        return destino
    }

    /** Lee una imagen respetando la rotación de la foto y la achica a 1280 px como máximo. */
    fun leerImagen(ctx: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val escala = minOf(1f, MAX_LADO.toFloat() / maxOf(w, h))
                dec.setTargetSize((w * escala).toInt().coerceAtLeast(1), (h * escala).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opciones) }
        var muestra = 1
        while (maxOf(opciones.outWidth, opciones.outHeight) / (muestra * 2) >= MAX_LADO) muestra *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        } ?: error("No se pudo leer la imagen")
        return achicar(bmp)
    }

    /** Cuadro del video en el segundo indicado. */
    fun cuadro(video: File, segundo: Float): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val bmp = r.getFrameAtTime((segundo * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime((segundo * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                ?: error("No se pudo sacar ese cuadro del video")
            return achicar(bmp)
        } finally {
            r.release()
        }
    }

    fun duracionSeg(video: File): Float {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000f
        } finally {
            r.release()
        }
    }

    private fun achicar(bmp: Bitmap): Bitmap {
        val lado = maxOf(bmp.width, bmp.height)
        if (lado <= MAX_LADO) return bmp
        val e = MAX_LADO.toFloat() / lado
        return Bitmap.createScaledBitmap(bmp, (bmp.width * e).toInt(), (bmp.height * e).toInt(), true)
    }

    /** Imagen lista para la API: JPEG en base64. */
    fun paraIA(bmp: Bitmap): Gemini.Imagen {
        val salida = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, salida)
        return Gemini.Imagen(Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP), "image/jpeg")
    }

    /** Formato sugerido según la forma de la imagen. */
    fun esHorizontal(bmp: Bitmap) = bmp.width > bmp.height
}
