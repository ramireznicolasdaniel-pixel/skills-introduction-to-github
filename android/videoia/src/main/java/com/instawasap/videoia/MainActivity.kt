package com.instawasap.videoia

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
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
import androidx.core.view.children
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.instawasap.comun.Compartir
import com.instawasap.comun.Procesar
import com.instawasap.comun.Sticker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("videoia", Context.MODE_PRIVATE) }

    // Configuración
    private lateinit var cardConfig: MaterialCardView
    private lateinit var clave: TextInputEditText
    private lateinit var modelo: MaterialAutoCompleteTextView
    private lateinit var estadoConfig: TextView

    // Crear
    private lateinit var idea: TextInputEditText
    private lateinit var noQuiero: TextInputEditText
    private lateinit var grupoFormato: MaterialButtonToggleGroup
    private lateinit var costo: TextView
    private lateinit var btnGenerar: MaterialButton
    private lateinit var btnCancelar: MaterialButton
    private lateinit var progreso: LinearProgressIndicator
    private lateinit var estado: TextView

    // Resultado
    private lateinit var cardVideo: MaterialCardView
    private lateinit var video: VideoView
    private lateinit var meta: TextView
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

    private var modelos: List<Gemini.Modelo> = emptyList()
    private var actual: File? = null
    private var sticker: File? = null
    private var tarea: Job? = null
    private var guardadoPendiente: Pair<File, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enlazar()
        ajustarBordes()
        eventos()
        cargarConfig()
        recibirTexto(intent)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Procesar.preparar(applicationContext) } }
        // Si la app se cerró mientras se generaba un video, seguimos esperándolo.
        prefs.getString("operacion", null)?.let { esperarVideo(it, SystemClock.elapsedRealtime()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recibirTexto(intent)
    }

    private fun enlazar() {
        cardConfig = findViewById(R.id.cardConfig)
        clave = findViewById(R.id.clave)
        modelo = findViewById(R.id.modelo)
        estadoConfig = findViewById(R.id.estadoConfig)
        idea = findViewById(R.id.idea)
        noQuiero = findViewById(R.id.noQuiero)
        grupoFormato = findViewById(R.id.grupoFormato)
        costo = findViewById(R.id.costo)
        btnGenerar = findViewById(R.id.btnGenerar)
        btnCancelar = findViewById(R.id.btnCancelar)
        progreso = findViewById(R.id.progreso)
        estado = findViewById(R.id.estado)
        cardVideo = findViewById(R.id.cardVideo)
        video = findViewById(R.id.video)
        meta = findViewById(R.id.meta)
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

    private fun ajustarBordes() {
        val contenido = findViewById<View>(R.id.contenido)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.raiz)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            contenido.updatePadding(top = b.top, bottom = b.bottom + (32 * resources.displayMetrics.density).toInt())
            insets
        }
    }

    private fun eventos() {
        findViewById<MaterialButton>(R.id.btnConfig).setOnClickListener {
            cardConfig.visibility = if (cardConfig.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.btnConseguirClave).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Gemini.URL_CLAVE)))
        }
        findViewById<MaterialButton>(R.id.btnGuardarClave).setOnClickListener { guardarClave() }
        modelo.setOnItemClickListener { _, _, pos, _ ->
            prefs.edit().putString("modelo", modelos[pos].id).apply()
            mostrarCosto()
        }
        findViewById<ChipGroup>(R.id.ideas).children.forEach { v ->
            (v as Chip).setOnClickListener { idea.setText(v.text); idea.setSelection(v.text.length) }
        }
        btnGenerar.setOnClickListener { generar() }
        btnCancelar.setOnClickListener { cancelar() }

        findViewById<MaterialButton>(R.id.btnWhatsApp).setOnClickListener { actual?.let { mandar(it, "video/mp4") } }
        findViewById<MaterialButton>(R.id.btnGuardar).setOnClickListener { actual?.let { guardar(it, "video/mp4", null) } }
        findViewById<MaterialButton>(R.id.btnOtraApp).setOnClickListener {
            actual?.let { Compartir.conOtraApp(this, it, "video/mp4") }
        }
        btnAchicar.setOnClickListener { achicar() }

        sliderInicio.addOnChangeListener { _, valor, deUsuario ->
            txtInicio.text = String.format(Locale.US, "Desde el segundo: %.1f", valor)
            if (deUsuario) video.seekTo((valor * 1000).toInt())
        }
        sliderDur.addOnChangeListener { _, valor, _ ->
            val t = if (valor % 1f == 0f) valor.toInt().toString() else String.format(Locale.US, "%.1f", valor)
            txtDur.text = "Duración: $t s (máx. 10)"
        }
        findViewById<MaterialButton>(R.id.btnUsarActual).setOnClickListener {
            sliderInicio.value = (video.currentPosition / 1000f).coerceIn(sliderInicio.valueFrom, sliderInicio.valueTo)
        }
        btnCrearSticker.setOnClickListener { crearSticker() }
        findViewById<MaterialButton>(R.id.btnStickerWhatsApp).setOnClickListener { sticker?.let { mandar(it, "image/webp") } }
        findViewById<MaterialButton>(R.id.btnStickerGuardar).setOnClickListener { sticker?.let { guardar(it, "image/webp", null) } }
    }

    /** Texto compartido desde otra app: se usa como idea. */
    private fun recibirTexto(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { idea.setText(it.trim()) }
        intent.action = null
    }

    // ---------- Configuración ----------

    private val claveGuardada get() = prefs.getString("clave", "").orEmpty()

    private fun cargarConfig() {
        clave.setText(claveGuardada)
        if (claveGuardada.isBlank()) {
            cardConfig.visibility = View.VISIBLE
            estadoConfig.text = "Primero guardá tu clave de API."
            estado.text = "Configurá tu clave de Google AI Studio para empezar (⚙ Configuración)."
        } else {
            buscarModelos(claveGuardada, silencioso = true)
        }
    }

    private fun guardarClave() {
        val k = clave.text?.toString()?.trim().orEmpty()
        if (k.isBlank()) { estadoConfig.text = "Pegá tu clave primero."; return }
        prefs.edit().putString("clave", k).apply()
        buscarModelos(k, silencioso = false)
    }

    private fun buscarModelos(k: String, silencioso: Boolean) {
        estadoConfig.text = "Buscando los modelos de video de tu cuenta…"
        lifecycleScope.launch {
            try {
                modelos = withContext(Dispatchers.IO) { Gemini.listarModelos(k) }
                if (modelos.isEmpty()) {
                    estadoConfig.text = "Tu cuenta no tiene modelos de video disponibles. Revisá que la facturación esté activada en Google AI Studio."
                    cardConfig.visibility = View.VISIBLE
                    return@launch
                }
                modelo.setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, modelos))
                val elegido = modelos.firstOrNull { it.id == prefs.getString("modelo", null) } ?: modelos.first()
                prefs.edit().putString("modelo", elegido.id).apply()
                modelo.setText(elegido.nombre, false)
                estadoConfig.text = "✓ Clave guardada. ${modelos.size} modelo(s) de video disponibles."
                if (!silencioso) {
                    cardConfig.visibility = View.GONE
                    toast("¡Listo! Ya podés generar videos.")
                }
                if (estado.text.startsWith("Configurá")) estado.text = ""
                mostrarCosto()
            } catch (e: Exception) {
                estadoConfig.text = e.message
                cardConfig.visibility = View.VISIBLE
            }
        }
    }

    private fun modeloElegido(): String? = prefs.getString("modelo", null)

    private fun mostrarCosto() {
        val id = modeloElegido() ?: return
        costo.text = "Cada video dura unos 8 segundos. Costo aprox.:${Gemini.costoTexto(id).removePrefix(" ·")} (lo cobra Google a tu cuenta)."
    }

    // ---------- Generar ----------

    private fun ocupado(si: Boolean) {
        if (si) progreso.isIndeterminate = true
        progreso.visibility = if (si) View.VISIBLE else View.GONE
        btnGenerar.isEnabled = !si
        btnCancelar.visibility = if (si) View.VISIBLE else View.GONE
        btnCrearSticker.isEnabled = !si
        btnAchicar.isEnabled = !si
        if (si) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun generar() {
        val k = claveGuardada
        val m = modeloElegido()
        val texto = idea.text?.toString()?.trim().orEmpty()
        when {
            k.isBlank() || m == null -> {
                cardConfig.visibility = View.VISIBLE
                estado.text = "Primero configurá tu clave de API."
                return
            }
            texto.length < 5 -> { estado.text = "Escribí qué video querés (cuanto más detalle, mejor)."; return }
        }
        val aspecto = if (grupoFormato.checkedButtonId == R.id.btnHorizontal) "16:9" else "9:16"
        ocupado(true)
        estado.text = "Enviando tu idea a la IA…"
        tarea = lifecycleScope.launch {
            try {
                val op = withContext(Dispatchers.IO) {
                    Gemini.iniciar(k, m!!, texto, aspecto, noQuiero.text?.toString().orEmpty())
                }
                prefs.edit().putString("operacion", op).putString("ideaActual", texto).apply()
                esperarVideo(op, SystemClock.elapsedRealtime())
            } catch (e: Exception) {
                estado.text = e.message ?: "No se pudo empezar a generar el video."
                ocupado(false)
            }
        }
    }

    /** Consulta cada 10 s hasta que el video esté listo (suele tardar 1 a 3 minutos). */
    private fun esperarVideo(operacion: String, desde: Long) {
        val k = claveGuardada
        ocupado(true)
        tarea = lifecycleScope.launch {
            try {
                while (isActive) {
                    val seg = (SystemClock.elapsedRealtime() - desde) / 1000
                    estado.text = String.format(Locale.US, "Generando video… %d:%02d (suele tardar entre 1 y 3 minutos)", seg / 60, seg % 60)
                    val e = withContext(Dispatchers.IO) { Gemini.consultar(k, operacion) }
                    if (e.terminado) {
                        prefs.edit().remove("operacion").apply()
                        if (e.error != null) {
                            estado.text = e.error
                        } else {
                            estado.text = "Descargando el video…"
                            val destino = File(Procesar.carpeta(applicationContext), Procesar.nuevoId() + ".mp4")
                            withContext(Dispatchers.IO) {
                                Procesar.limpiarViejos(applicationContext, horas = 24)
                                Gemini.descargar(k, e.uri!!, destino)
                            }
                            mostrarVideo(destino)
                            estado.text = "¡Tu video está listo! 🎉"
                        }
                        break
                    }
                    if (seg > 15 * 60) {
                        prefs.edit().remove("operacion").apply()
                        estado.text = "La IA está tardando demasiado. Probá de nuevo más tarde."
                        break
                    }
                    delay(10_000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Error de red mientras esperamos: la operación sigue guardada y se retoma al volver a abrir.
                estado.text = (e.message ?: "Error") + " (Si cerrás y abrís la app, sigue esperando el video.)"
            } finally {
                ocupado(false)
            }
        }
    }

    private fun cancelar() {
        tarea?.cancel()
        prefs.edit().remove("operacion").apply()
        estado.text = "Cancelado. (Si Google ya empezó a generarlo, igual puede cobrarlo.)"
        ocupado(false)
    }

    // ---------- Resultado ----------

    private fun mostrarVideo(f: File) {
        actual = f
        sticker = null
        resSticker.visibility = View.GONE
        cardVideo.visibility = View.VISIBLE
        cardSticker.visibility = View.VISIBLE
        val mb = Procesar.mb(f)
        meta.text = String.format(Locale.US, "%.1f MB — %s", mb, prefs.getString("ideaActual", "").orEmpty())
        btnAchicar.visibility = if (mb > Procesar.MAX_MB_WHATSAPP) View.VISIBLE else View.GONE
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        video.setVideoPath(f.absolutePath)
        video.setOnPreparedListener { mp ->
            mp.isLooping = true
            sliderInicio.value = 0f
            sliderInicio.valueTo = maxOf(mp.duration / 1000f - 0.5f, 0.1f)
            video.start()
        }
        cardVideo.post { findViewById<View>(R.id.raiz).scrollTo(0, cardVideo.top) }
    }

    private fun achicar() {
        val f = actual ?: return
        ocupado(true)
        btnCancelar.visibility = View.GONE
        estado.text = "Achicando el video…"
        lifecycleScope.launch {
            try {
                val chico = withContext(Dispatchers.IO) { Procesar.comprimir(applicationContext, f) }
                mostrarVideo(chico)
                estado.text = String.format(Locale.US, "Listo: ahora pesa %.1f MB.", Procesar.mb(chico))
            } catch (e: Exception) {
                estado.text = "No se pudo achicar: ${e.message?.take(200)}"
            } finally {
                ocupado(false)
            }
        }
    }

    private fun crearSticker() {
        val f = actual ?: return
        val completo = grupoRecorte.checkedButtonId == R.id.btnCompleto
        ocupado(true)
        btnCancelar.visibility = View.GONE
        estado.text = "Creando sticker animado…"
        lifecycleScope.launch {
            try {
                val st = withContext(Dispatchers.IO) {
                    Procesar.preparar(applicationContext)
                    Procesar.hacerSticker(applicationContext, f, sliderInicio.value, sliderDur.value, completo)
                }
                sticker = st
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(st))
                    imgSticker.setImageDrawable(d)
                    (d as? AnimatedImageDrawable)?.start()
                } else {
                    imgSticker.setImageBitmap(BitmapFactory.decodeFile(st.absolutePath))
                }
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

    private fun mandar(f: File, mime: String) {
        when {
            Compartir.whatsappInstalado(this) == null -> guardar(f, mime, "WhatsApp no está instalado.")
            !Compartir.enWhatsApp(this, f, mime) -> guardar(f, mime, "No se pudo abrir WhatsApp.")
        }
    }

    private fun guardar(f: File, mime: String, motivo: String?) {
        if (Compartir.necesitaPermiso() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            guardadoPendiente = f to mime
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            return
        }
        try {
            val carpeta = Compartir.guardarEnGaleria(this, f, mime)
            val que = if (mime.startsWith("video")) "El video" else "El sticker"
            if (motivo != null) {
                val extra = if (mime.startsWith("video")) " Subilo a WhatsApp desde la galería."
                else " Para que llegue animado: abrí Archivos, mantené apretado el sticker > Compartir > WhatsApp."
                MaterialAlertDialogBuilder(this)
                    .setTitle("No se pudo publicar directo")
                    .setMessage("$motivo $que se guardó en $carpeta.$extra")
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
        val p = guardadoPendiente ?: return
        guardadoPendiente = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) guardar(p.first, p.second, null)
        else toast("Sin permiso no se puede guardar en la galería")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
