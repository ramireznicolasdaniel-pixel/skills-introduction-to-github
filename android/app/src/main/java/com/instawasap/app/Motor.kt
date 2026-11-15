package com.instawasap.app

import android.content.Context
import com.instawasap.comun.Procesar
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class Video(val archivo: File, val autor: String, val titulo: String) {
    val mb: Double get() = archivo.length() / 1_048_576.0
}

class ErrorAmigable(mensaje: String) : Exception(mensaje)

/** Descarga con yt-dlp (viene dentro de la APK). */
object Motor {
    const val MAX_MB_WHATSAPP = Procesar.MAX_MB_WHATSAPP

    fun carpeta(ctx: Context) = Procesar.carpeta(ctx)

    fun preparar(ctx: Context) = Procesar.preparar(ctx)

    /** Actualiza yt-dlp como máximo una vez por día (Instagram cambia seguido). */
    fun actualizarDescargador(ctx: Context) {
        val prefs = ctx.getSharedPreferences("instawasap", Context.MODE_PRIVATE)
        val ultima = prefs.getLong("ultimaActualizacion", 0)
        if (System.currentTimeMillis() - ultima < 24 * 3600_000L) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("ultimaActualizacion", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
            // sin internet o GitHub caído: se usa la versión que trae la app
        }
    }


    fun descargar(ctx: Context, url: String, progreso: (Int) -> Unit): Video {
        Procesar.limpiarViejos(ctx)
        val id = Procesar.nuevoId()
        val dir = carpeta(ctx)
        val req = YoutubeDLRequest(url).apply {
            addOption("-o", File(dir, "$id.%(ext)s").absolutePath)
            addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--playlist-items", "1") // posts con varios videos: el primero
            addOption("--write-info-json")
            addOption("--no-mtime")
        }
        try {
            YoutubeDL.getInstance().execute(req, id) { p, _, _ -> progreso(p.toInt().coerceIn(0, 100)) }
        } catch (e: Exception) {
            throw ErrorAmigable(traducirError(e.message ?: e.toString()))
        }
        val video = dir.listFiles()?.firstOrNull { it.name.startsWith(id) && it.extension == "mp4" }
            ?: dir.listFiles()?.firstOrNull { it.name.startsWith(id) && it.extension in setOf("webm", "mkv", "mov") }
            ?: throw ErrorAmigable("Ese post no tiene video (puede ser solo fotos).")
        val json = dir.listFiles()?.firstOrNull { it.name.startsWith(id) && it.name.endsWith(".info.json") }
        var autor = ""
        var titulo = "Video de Instagram"
        json?.let {
            runCatching {
                val o = JSONObject(it.readText())
                autor = o.optString("uploader", o.optString("channel", ""))
                titulo = o.optString("description", o.optString("title", titulo)).take(200).ifBlank { titulo }
            }
            it.delete()
        }
        return Video(video, autor, titulo)
    }

    fun traducirError(msg: String): String {
        val bajo = msg.lowercase(Locale.ROOT)
        return when {
            "login" in bajo || "private" in bajo || "cookies" in bajo ->
                "Instagram pide iniciar sesión para este video (puede ser privado o te limitaron). Probá de nuevo en unos minutos."
            "rate" in bajo || "429" in bajo ->
                "Instagram está limitando las descargas. Esperá unos minutos y probá de nuevo."
            "no video" in bajo -> "Ese post no tiene video (puede ser solo fotos)."
            "unable to download" in bajo || "connect" in bajo || "resolve" in bajo ->
                "No se pudo conectar con Instagram. Revisá tu conexión a internet."
            else -> "No se pudo descargar: " + msg.replace("ERROR: ", "").lines().lastOrNull { it.isNotBlank() }.orEmpty().take(300)
        }
    }

    /** Recomprime para que WhatsApp lo acepte (< 16 MB). */
    fun comprimir(ctx: Context, origen: Video): Video = origen.copy(archivo = Procesar.comprimir(ctx, origen.archivo))
}
