package com.instawasap.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class Video(val archivo: File, val autor: String, val titulo: String) {
    val mb: Double get() = archivo.length() / 1_048_576.0
}

class ErrorAmigable(mensaje: String) : Exception(mensaje)

/** Descarga con yt-dlp y procesa con ffmpeg (los dos vienen dentro de la APK). */
object Motor {
    const val MAX_MB_WHATSAPP = 16.0
    @Volatile private var listo = false

    fun carpeta(ctx: Context) = File(ctx.cacheDir, "instawasap").apply { mkdirs() }

    @Synchronized
    fun preparar(ctx: Context) {
        if (listo) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        listo = true
    }

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

    fun limpiarViejos(ctx: Context, horas: Int = 1) {
        val limite = System.currentTimeMillis() - horas * 3600_000L
        carpeta(ctx).listFiles()?.filter { it.lastModified() < limite }?.forEach { it.delete() }
    }

    private fun nuevoId() = UUID.randomUUID().toString().replace("-", "")

    fun descargar(ctx: Context, url: String, progreso: (Int) -> Unit): Video {
        limpiarViejos(ctx)
        val id = nuevoId()
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

    // ---------- ffmpeg / ffprobe (binarios incluidos por youtubedl-android) ----------

    private fun ejecutar(ctx: Context, binario: String, args: List<String>): String {
        val libDir = ctx.applicationInfo.nativeLibraryDir
        val paquetes = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(File(libDir, binario).absolutePath) + args).redirectErrorStream(true)
        pb.environment().apply {
            this["LD_LIBRARY_PATH"] = "$paquetes/python/usr/lib:$paquetes/ffmpeg/usr/lib"
            this["TMPDIR"] = ctx.cacheDir.absolutePath
        }
        val proceso = pb.start()
        val salida = proceso.inputStream.bufferedReader().readText()
        if (proceso.waitFor() != 0) throw IOException("$binario falló: ${salida.takeLast(500)}")
        return salida
    }

    private fun ffmpeg(ctx: Context, vararg args: String) =
        ejecutar(ctx, "libffmpeg.so", listOf("-y", "-v", "error") + args)

    fun duracion(ctx: Context, archivo: File): Double =
        ejecutar(
            ctx, "libffprobe.so",
            listOf("-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", archivo.absolutePath),
        ).trim().lines().first().toDouble()

    /** Recomprime para que pese menos de 15 MB y WhatsApp lo acepte. */
    fun comprimir(ctx: Context, origen: Video): Video {
        val kbps = bitrateParaPeso(duracion(ctx, origen.archivo))
        val destino = File(carpeta(ctx), nuevoId() + ".mp4")
        ffmpeg(
            ctx, "-i", origen.archivo.absolutePath, "-vf", "scale='min(720,iw)':-2",
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "${kbps}k", "-maxrate", "${kbps}k",
            "-bufsize", "${kbps * 2}k", "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart",
            destino.absolutePath,
        )
        return origen.copy(archivo = destino)
    }

    /** Sticker ANIMADO WebP 512x512, < 500 KB, hasta 10 s, con metadatos de WhatsApp. */
    fun hacerSticker(ctx: Context, origen: File, inicio: Float, segundos: Float, completo: Boolean): File {
        val (ini, dur) = Sticker.ajustarTramo(duracion(ctx, origen).toFloat(), inicio, segundos)
        val destino = File(carpeta(ctx), nuevoId() + ".webp")
        val limite = (Sticker.MAX_KB - 2) * 1024.0 // margen para los metadatos
        var i = 0
        var entra = false
        while (i < Sticker.INTENTOS.size) {
            val (calidad, fps) = Sticker.INTENTOS[i]
            ffmpeg(
                ctx, "-ss", "%.2f".format(Locale.US, ini), "-i", origen.absolutePath, "-an",
                "-t", "%.2f".format(Locale.US, dur), "-vf", "fps=$fps,${Sticker.filtroRecorte(completo)}",
                "-c:v", "libwebp_anim", "-loop", "0", "-q:v", "$calidad", "-compression_level", "4",
                destino.absolutePath,
            )
            val exceso = destino.length() / limite
            if (exceso <= 1) { entra = true; break }
            i += Sticker.salto(exceso)
        }
        if (!entra && dur > 1) { // último recurso: acortar a la mitad
            destino.delete()
            return hacerSticker(ctx, origen, ini, dur / 2, completo)
        }
        Sticker.agregarMetadatos(destino)
        return destino
    }
}
