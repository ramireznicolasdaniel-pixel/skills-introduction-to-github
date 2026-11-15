package com.instawasap.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.instawasap.comun.Compartir
import com.instawasap.comun.Procesar
import com.instawasap.comun.Sticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var url: TextInputEditText
    private lateinit var btnDescargar: MaterialButton
    private lateinit var progreso: LinearProgressIndicator
    private lateinit var estado: TextView
    private lateinit var cardVideo: MaterialCardView
    private lateinit var video: VideoView
    private lateinit var meta: TextView
    private lateinit var aviso: TextView
    private lateinit var btnAchicar: MaterialButton
    private lateinit var cardSticker: MaterialCardView
    private lateinit var grupoRecorte: MaterialButtonToggleGroup
    private lateinit var sliderInicio: Slider
    private lateinit var sliderDur: Slider
    private lateinit var txtInicio: TextView
    private lateinit var txtDur: TextView
    private lateinit var btnCrearSticker: MaterialButton
    private lateinit var resSticker: View
    private lateinit var imgSticker: ImageView
    private lateinit var metaSticker: TextView

    private var listo = false
    private var trabajando = false
    private var linkPendiente: String? = null
    private var actual: Video? = null
    private var sticker: File? = null
    private var guardadoPendiente: Pair<File, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enlazarVistas()
        ajustarBordes()
        configurarEventos()
        recibirLink(intent)
        prepararMotor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recibirLink(intent)
    }

    private fun enlazarVistas() {
        url = findViewById(R.id.url)
        btnDescargar = findViewById(R.id.btnDescargar)
        progreso = findViewById(R.id.progreso)
        estado = findViewById(R.id.estado)
        cardVideo = findViewById(R.id.cardVideo)
        video = findViewById(R.id.video)
        meta = findViewById(R.id.meta)
        aviso = findViewById(R.id.aviso)
        btnAchicar = findViewById(R.id.btnAchicar)
        cardSticker = findViewById(R.id.cardSticker)
        grupoRecorte = findViewById(R.id.grupoRecorte)
        sliderInicio = findViewById(R.id.sliderInicio)
        sliderDur = findViewById(R.id.sliderDur)
        txtInicio = findViewById(R.id.txtInicio)
        txtDur = findViewById(R.id.txtDur)
        btnCrearSticker = findViewById(R.id.btnCrearSticker)
        resSticker = findViewById(R.id.resSticker)
        imgSticker = findViewById(R.id.imgSticker)
        metaSticker = findViewById(R.id.metaSticker)
    }

    /** Android 15 dibuja detrás de las barras del sistema: dejamos espacio. */
    private fun ajustarBordes() {
        val contenido = findViewById<View>(R.id.contenido)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.raiz)) { _, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            contenido.updatePadding(top = barras.top, bottom = barras.bottom + dp(32))
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun configurarEventos() {
        findViewById<MaterialButton>(R.id.btnPegar).setOnClickListener {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val texto = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (texto.isNullOrBlank()) toast("No hay nada copiado") else url.setText(texto.trim())
        }
        btnDescargar.setOnClickListener { descargar() }
        url.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_GO) { descargar(); true } else false
        }

        findViewById<MaterialButton>(R.id.btnWhatsApp).setOnClickListener {
            actual?.let { mandarPorWhatsApp(it.archivo, "video/mp4") }
        }
        findViewById<MaterialButton>(R.id.btnGuardar).setOnClickListener {
            actual?.let { guardar(it.archivo, "video/mp4", null) }
        }
        findViewById<MaterialButton>(R.id.btnOtraApp).setOnClickListener {
            actual?.let { Compartir.conOtraApp(this, it.archivo, "video/mp4") }
        }
        btnAchicar.setOnClickListener { achicar() }

        sliderInicio.addOnChangeListener { _, valor, deUsuario ->
            txtInicio.text = String.format(Locale.US, "Desde el segundo: %.1f", valor)
            if (deUsuario) video.seekTo((valor * 1000).toInt())
        }
        sliderDur.addOnChangeListener { _, valor, _ ->
            txtDur.text = String.format(Locale.US, "Duración: %s s (máx. 10)", formatear(valor))
        }
        findViewById<MaterialButton>(R.id.btnUsarActual).setOnClickListener {
            sliderInicio.value = (video.currentPosition / 1000f).coerceIn(sliderInicio.valueFrom, sliderInicio.valueTo)
        }
        btnCrearSticker.setOnClickListener { crearSticker() }
        findViewById<MaterialButton>(R.id.btnStickerWhatsApp).setOnClickListener {
            sticker?.let { mandarPorWhatsApp(it, "image/webp") }
        }
        findViewById<MaterialButton>(R.id.btnStickerGuardar).setOnClickListener {
            sticker?.let { guardar(it, "image/webp", null) }
        }
    }

    private fun formatear(v: Float) = if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    // ---------- Preparación ----------

    private fun prepararMotor() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Motor.preparar(applicationContext) }
                estado.text = "Actualizando el descargador…"
                withContext(Dispatchers.IO) { Motor.actualizarDescargador(applicationContext) }
                listo = true
                btnDescargar.isEnabled = true
                estado.text = "Pegá un link o, desde Instagram, tocá Compartir → InstaWasap."
                linkPendiente?.let { linkPendiente = null; url.setText(it); descargar() }
            } catch (e: Exception) {
                estado.text = "No se pudo preparar la app: ${e.message}"
            }
        }
    }

    /** Link que llega desde Instagram > Compartir > InstaWasap. */
    private fun recibirLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val link = Links.extraer(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: run {
            toast("Eso no parece un link de Instagram")
            return
        }
        intent.action = null // que no se repita al girar la pantalla
        url.setText(link)
        if (listo) descargar() else linkPendiente = link
    }

    // ---------- Descarga ----------

    private fun ocupado(si: Boolean, texto: String = "") {
        trabajando = si
        if (si) progreso.isIndeterminate = true // solo se puede cambiar mientras está oculta
        btnDescargar.isEnabled = !si && listo
        btnCrearSticker.isEnabled = !si
        btnAchicar.isEnabled = !si
        progreso.visibility = if (si) View.VISIBLE else View.GONE
        if (texto.isNotEmpty()) estado.text = texto
    }

    private fun descargar() {
        if (trabajando || !listo) return
        val link = Links.extraer(url.text?.toString())
        if (link == null) {
            estado.text = "Pegá un link de Instagram válido (reel, post o tv)."
            return
        }
        ocupado(true, "Descargando…")
        lifecycleScope.launch {
            try {
                val v = withContext(Dispatchers.IO) {
                    Motor.descargar(applicationContext, link) { p ->
                        runOnUiThread {
                            if (p > 0) {
                                progreso.isIndeterminate = false
                                progreso.setProgressCompat(p, true)
                                estado.text = "Descargando… $p%"
                            }
                        }
                    }
                }
                mostrarVideo(v)
                estado.text = "¡Listo! Video descargado."
                if (v.mb > Motor.MAX_MB_WHATSAPP) {
                    // Plan B: muy pesado para WhatsApp -> se guarda solo en la galería.
                    guardar(v.archivo, "video/mp4",
                        "Pesa %.1f MB y WhatsApp acepta hasta 16 MB, así que no se puede publicar directo. Tocá \"Achicar\" para poder mandarlo.".format(Locale.US, v.mb))
                }
            } catch (e: ErrorAmigable) {
                estado.text = e.message
            } catch (e: Exception) {
                estado.text = Motor.traducirError(e.message ?: e.toString())
            } finally {
                ocupado(false)
            }
        }
    }

    private fun mostrarVideo(v: Video) {
        actual = v
        sticker = null
        resSticker.visibility = View.GONE
        aviso.visibility = View.GONE
        cardVideo.visibility = View.VISIBLE
        cardSticker.visibility = View.VISIBLE
        meta.text = (if (v.autor.isNotBlank()) "@${v.autor} · " else "") + "%.1f MB — ".format(Locale.US, v.mb) + v.titulo
        btnAchicar.visibility = if (v.mb > Motor.MAX_MB_WHATSAPP) View.VISIBLE else View.GONE

        val controles = MediaController(this).also { it.setAnchorView(video) }
        video.setMediaController(controles)
        video.setVideoPath(v.archivo.absolutePath)
        video.setOnPreparedListener { mp ->
            mp.isLooping = true
            val seg = mp.duration / 1000f
            sliderInicio.value = 0f // primero el valor, después el máximo (si no, Slider falla)
            sliderInicio.valueTo = maxOf(seg - 0.5f, 0.1f)
            video.start()
        }
    }

    private fun achicar() {
        val v = actual ?: return
        ocupado(true, "Achicando el video… (puede tardar un poco)")
        lifecycleScope.launch {
            try {
                val chico = withContext(Dispatchers.IO) { Motor.comprimir(applicationContext, v) }
                mostrarVideo(chico)
                estado.text = if (chico.mb > Motor.MAX_MB_WHATSAPP)
                    "Quedó en %.1f MB, todavía es grande para WhatsApp.".format(Locale.US, chico.mb)
                else "Listo: ahora pesa %.1f MB. Ya podés mandarlo por WhatsApp.".format(Locale.US, chico.mb)
            } catch (e: Exception) {
                estado.text = "No se pudo achicar el video: ${e.message?.take(200)}"
            } finally {
                ocupado(false)
            }
        }
    }

    // ---------- Sticker ----------

    private fun crearSticker() {
        val v = actual ?: return
        val completo = grupoRecorte.checkedButtonId == R.id.btnCompleto
        ocupado(true, "Creando sticker animado…")
        lifecycleScope.launch {
            try {
                val st = withContext(Dispatchers.IO) {
                    Procesar.hacerSticker(applicationContext, v.archivo, sliderInicio.value, sliderDur.value, completo)
                }
                sticker = st
                mostrarSticker(st)
                val kb = st.length() / 1024
                metaSticker.text = "Animado · 512×512 · $kb KB" +
                    if (kb > Sticker.MAX_KB) " — muy pesado, probá con menos duración" else " — listo para WhatsApp"
                resSticker.visibility = View.VISIBLE
                estado.text = "Sticker animado creado."
            } catch (e: Exception) {
                estado.text = "No se pudo crear el sticker: ${e.message?.take(200)}"
            } finally {
                ocupado(false)
            }
        }
    }

    private fun mostrarSticker(st: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(st))
            imgSticker.setImageDrawable(d)
            (d as? AnimatedImageDrawable)?.start()
        } else {
            imgSticker.setImageBitmap(BitmapFactory.decodeFile(st.absolutePath)) // Android viejo: vista previa quieta
        }
    }

    // ---------- Compartir / guardar ----------

    private fun mandarPorWhatsApp(archivo: File, mime: String) {
        if (Compartir.whatsappInstalado(this) == null) {
            guardar(archivo, mime, "WhatsApp no está instalado.")
            return
        }
        if (!Compartir.enWhatsApp(this, archivo, mime)) {
            guardar(archivo, mime, "No se pudo abrir WhatsApp.")
        }
    }

    /** Guarda en la galería. Si [motivo] no es null, es el plan B de "no se pudo publicar". */
    private fun guardar(archivo: File, mime: String, motivo: String?) {
        if (Compartir.necesitaPermiso() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            guardadoPendiente = archivo to mime
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            return
        }
        try {
            val carpeta = Compartir.guardarEnGaleria(this, archivo, mime)
            val que = if (mime.startsWith("video")) "El video" else "El sticker"
            val texto = (motivo?.let { "$it " } ?: "") + "$que se guardó en $carpeta." +
                if (motivo != null && mime.startsWith("video")) " Subilo a WhatsApp desde la galería."
                else if (motivo != null) " Para que llegue animado: abrí Archivos, mantené apretado el sticker > Compartir > WhatsApp."
                else ""
            if (motivo != null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("No se pudo publicar directo")
                    .setMessage(texto)
                    .setPositiveButton("Entendido", null)
                    .show()
            } else {
                toast("$que se guardó en $carpeta")
            }
        } catch (e: Exception) {
            toast("No se pudo guardar: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val pendiente = guardadoPendiente ?: return
        guardadoPendiente = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) guardar(pendiente.first, pendiente.second, null)
        else toast("Sin permiso no se puede guardar en la galería")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
