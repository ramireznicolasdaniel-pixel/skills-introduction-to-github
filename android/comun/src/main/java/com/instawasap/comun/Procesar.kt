package com.instawasap.comun

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** ffmpeg/ffprobe incluidos en la APK: achicar videos y hacer stickers animados. */
object Procesar {
    const val MAX_MB_WHATSAPP = 16.0
    @Volatile private var listo = false

    fun carpeta(ctx: Context) = File(ctx.cacheDir, "instawasap").apply { mkdirs() }

    fun nuevoId() = UUID.randomUUID().toString().replace("-", "")

    fun mb(archivo: File) = archivo.length() / 1_048_576.0

    /** Descomprime Python y ffmpeg la primera vez (tarda unos segundos). */
    @Synchronized
    fun preparar(ctx: Context) {
        if (listo) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        listo = true
    }

    fun limpiarViejos(ctx: Context, horas: Int = 1) {
        val limite = System.currentTimeMillis() - horas * 3600_000L
        carpeta(ctx).listFiles()?.filter { it.lastModified() < limite }?.forEach { it.delete() }
    }

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

    /** Ancho y alto del primer stream de video. */
    fun dimensiones(ctx: Context, archivo: File): Pair<Int, Int> {
        val t = ejecutar(
            ctx, "libffprobe.so",
            listOf("-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                "-of", "csv=s=x:p=0", archivo.absolutePath),
        ).trim().lines().first()
        val (w, h) = t.split("x").map { it.trim().toInt() }
        return w to h
    }

    fun tieneAudio(ctx: Context, archivo: File): Boolean =
        ejecutar(
            ctx, "libffprobe.so",
            listOf("-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", archivo.absolutePath),
        ).isNotBlank()

    /**
     * Une [primero] (cortado hasta [hastaSeg]) con [segundo], uno detrás del otro.
     * El primero se adapta al tamaño del segundo (con bordes si hace falta); si alguno no tiene audio, se le pone silencio.
     */
    fun unir(ctx: Context, primero: File, hastaSeg: Double, segundo: File): File {
        val (w, h) = dimensiones(ctx, segundo)
        val durPrimero = minOf(hastaSeg, duracion(ctx, primero)).coerceAtLeast(0.1)
        val durSegundo = duracion(ctx, segundo)
        val args = mutableListOf("-t", "%.2f".format(Locale.US, durPrimero), "-i", primero.absolutePath, "-i", segundo.absolutePath)
        var entradas = 2
        fun audio(indice: Int, archivo: File, dur: Double, etiqueta: String): String {
            val fuente = if (tieneAudio(ctx, archivo)) {
                "[$indice:a]"
            } else {
                args += listOf("-f", "lavfi", "-t", "%.2f".format(Locale.US, dur), "-i", "anullsrc=r=44100:cl=stereo")
                "[${entradas++}:a]"
            }
            return "${fuente}aresample=44100,aformat=channel_layouts=stereo[$etiqueta]"
        }
        val a0 = audio(0, primero, durPrimero, "a0")
        val a1 = audio(1, segundo, durSegundo, "a1")
        val ajuste = "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p"
        val filtro = "[0:v]$ajuste[v0];[1:v]$ajuste[v1];$a0;$a1;[v0][a0][v1][a1]concat=n=2:v=1:a=1[v][a]"
        val destino = File(carpeta(ctx), nuevoId() + ".mp4")
        ffmpeg(
            ctx, *args.toTypedArray(), "-filter_complex", filtro, "-map", "[v]", "-map", "[a]",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart", destino.absolutePath,
        )
        return destino
    }

    /** Recomprime para que pese menos de 15 MB y WhatsApp lo acepte. */
    fun comprimir(ctx: Context, origen: File): File {
        val kbps = bitrateParaPeso(duracion(ctx, origen))
        val destino = File(carpeta(ctx), nuevoId() + ".mp4")
        ffmpeg(
            ctx, "-i", origen.absolutePath, "-vf", "scale='min(720,iw)':-2",
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "${kbps}k", "-maxrate", "${kbps}k",
            "-bufsize", "${kbps * 2}k", "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart",
            destino.absolutePath,
        )
        return destino
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
