package com.instawasap.comun

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Apps gratis para editar video: se les manda el video con un toque. */
object Editores {
    data class Editor(val nombre: String, val paquete: String, val detalle: String)

    val LISTA = listOf(
        Editor("CapCut", "com.lemon.lvoverseas", "efectos, textos, música y plantillas"),
        Editor("InShot", "com.camerasideas.instashot", "cortar, música, textos y filtros"),
        Editor("YouCut", "com.camerasideas.trimmer", "cortar y unir sin marca de agua"),
        Editor("VN Editor", "com.frontrow.vlog", "edición por capas, sin marca de agua"),
    )

    fun instalado(ctx: Context, paquete: String) = try {
        ctx.packageManager.getPackageInfo(paquete, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun intentVideo(ctx: Context, archivo: File, accion: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", archivo)
        return Intent(accion).apply {
            if (accion == Intent.ACTION_SEND) {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
            } else {
                setDataAndType(uri, "video/mp4")
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** Abre el editor con el video (prueba "compartir", después "editar" y "abrir"). */
    fun abrir(ctx: Context, archivo: File, paquete: String): Boolean {
        for (accion in listOf(Intent.ACTION_SEND, Intent.ACTION_EDIT, Intent.ACTION_VIEW)) {
            try {
                ctx.startActivity(intentVideo(ctx, archivo, accion).setPackage(paquete))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        // Último recurso: abrir la app sola (el video ya está guardado en la galería)
        return ctx.packageManager.getLaunchIntentForPackage(paquete)?.let { ctx.startActivity(it); true } ?: false
    }

    fun abrirPlayStore(ctx: Context, paquete: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$paquete")))
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$paquete")))
        }
    }

    /**
     * Muestra la lista de editores gratis. Si está instalado, le manda el video; si no, abre Play Store.
     * [alAbrir] se llama antes de abrir un editor (por ejemplo, para guardar una copia en la galería).
     */
    fun elegir(act: Activity, archivo: File, alAbrir: () -> Unit = {}) {
        val items = LISTA.map { e ->
            val estado = if (instalado(act, e.paquete)) "✓ instalada" else "⬇ bajar gratis"
            "${e.nombre} — $estado\n${e.detalle}"
        } + "Otra app de edición…"
        MaterialAlertDialogBuilder(act)
            .setTitle("Editar gratis con…")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == LISTA.size) {
                    act.startActivity(Intent.createChooser(intentVideo(act, archivo, Intent.ACTION_EDIT), "Editar con…"))
                    return@setItems
                }
                val e = LISTA[i]
                if (instalado(act, e.paquete)) {
                    alAbrir()
                    if (!abrir(act, archivo, e.paquete)) abrirPlayStore(act, e.paquete)
                } else {
                    abrirPlayStore(act, e.paquete)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Para el manifiesto: paquetes que hay que declarar en <queries>. */
    val PAQUETES = LISTA.map { it.paquete }
}
