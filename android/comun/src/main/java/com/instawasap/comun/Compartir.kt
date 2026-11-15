package com.instawasap.comun

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object Compartir {
    private val WHATSAPPS = listOf("com.whatsapp", "com.whatsapp.w4b") // normal y Business

    fun whatsappInstalado(ctx: Context): String? = WHATSAPPS.firstOrNull {
        try {
            ctx.packageManager.getPackageInfo(it, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun intent(ctx: Context, archivo: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", archivo)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Abre WhatsApp con el archivo (elegís chat o "Mi estado"). Devuelve false si no se pudo. */
    fun enWhatsApp(ctx: Context, archivo: File, mime: String): Boolean {
        val paquete = whatsappInstalado(ctx) ?: return false
        return try {
            ctx.startActivity(intent(ctx, archivo, mime).setPackage(paquete))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Menú de compartir de Android con todas las apps. */
    fun conOtraApp(ctx: Context, archivo: File, mime: String) {
        ctx.startActivity(Intent.createChooser(intent(ctx, archivo, mime), "Compartir con…"))
    }

    /** Hace falta pedir permiso de almacenamiento solo en Android 9 o anterior. */
    fun necesitaPermiso(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Guarda en la galería: videos en Movies/InstaWasap, stickers en Pictures/InstaWasap.
     * Devuelve la carpeta donde quedó, para mostrarle al usuario.
     */
    fun guardarEnGaleria(ctx: Context, archivo: File, mime: String): String {
        val esVideo = mime.startsWith("video")
        val nombre = (if (esVideo) "instawasap_" else "sticker_") + System.currentTimeMillis() + "." + archivo.extension
        val carpeta = (if (esVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/InstaWasap"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val coleccion = if (esVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, carpeta)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(coleccion, valores) ?: error("No se pudo crear el archivo en la galería")
            resolver.openOutputStream(uri)!!.use { salida -> archivo.inputStream().use { it.copyTo(salida) } }
            valores.clear()
            valores.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, valores, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), carpeta).apply { mkdirs() }
            val destino = File(dir, nombre)
            archivo.copyTo(destino, overwrite = true)
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(destino.absolutePath), arrayOf(mime), null)
        }
        return carpeta
    }
}
