package com.instawasap.videoia

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ErrorIA(mensaje: String) : Exception(mensaje)

/**
 * Cliente mínimo de la API de Gemini para generar videos con Veo.
 * Flujo: iniciar (predictLongRunning) -> consultar la operación hasta que termine -> descargar el MP4.
 * Los nombres de modelos NO están fijos: se consultan a la API, así si Google da de baja uno, la app sigue andando.
 */
object Gemini {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val URL_CLAVE = "https://aistudio.google.com/apikey"

    data class Modelo(val id: String, val nombre: String) {
        override fun toString() = nombre
    }

    data class Estado(val terminado: Boolean, val uri: String? = null, val error: String? = null)

    // ---------- Partes puras (probadas con tests) ----------

    /** Modelos que generan video: los que admiten predictLongRunning y se llaman veo/video/omni. */
    fun parsearModelos(json: String): List<Modelo> {
        val lista = JSONObject(json).optJSONArray("models") ?: JSONArray()
        return (0 until lista.length()).map { lista.getJSONObject(it) }
            .filter { m ->
                val metodos = m.optJSONArray("supportedGenerationMethods")
                val largo = metodos != null && (0 until metodos.length()).any { metodos.getString(it) == "predictLongRunning" }
                val id = m.optString("name").lowercase(Locale.ROOT)
                largo && ("veo" in id || "video" in id || "omni" in id)
            }
            .map { m ->
                val id = m.getString("name").removePrefix("models/")
                Modelo(id, m.optString("displayName").ifBlank { id } + costoTexto(id))
            }
            .sortedWith(compareBy({ prioridad(it.id) }, { it.id }))
    }

    /** Orden sugerido: rápido (buena relación precio/calidad), económico, normal. */
    private fun prioridad(id: String) = when {
        "fast" in id -> 0
        "lite" in id -> 1
        else -> 2
    }

    /** Precio aproximado por video de 8 s (precios publicados en nov-2026; pueden cambiar). */
    fun costoTexto(id: String): String = when {
        "lite" in id -> " · ~US$0,40 c/video"
        "fast" in id -> " · ~US$0,80 c/video"
        "veo" in id -> " · ~US$3,20 c/video"
        else -> ""
    }

    fun cuerpo(prompt: String, aspecto: String, noQuiero: String, conParametros: Boolean): JSONObject =
        JSONObject().apply {
            put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            if (conParametros) {
                val p = JSONObject().put("aspectRatio", aspecto)
                if (noQuiero.isNotBlank()) p.put("negativePrompt", noQuiero)
                put("parameters", p)
            }
        }

    fun parsearEstado(json: String): Estado {
        val o = JSONObject(json)
        if (!o.optBoolean("done", false)) return Estado(false)
        o.optJSONObject("error")?.let { return Estado(true, error = traducir(it.optString("message"))) }
        val r = o.optJSONObject("response") ?: return Estado(true, error = "La IA terminó pero no devolvió ningún video.")
        val gen = r.optJSONObject("generateVideoResponse") ?: r
        gen.optJSONArray("raiMediaFilteredReasons")?.takeIf { it.length() > 0 }?.let {
            return Estado(true, error = "La IA bloqueó el video por sus reglas de seguridad: ${it.optString(0)}. Probá describiéndolo de otra forma.")
        }
        val muestras = gen.optJSONArray("generatedSamples") ?: gen.optJSONArray("generatedVideos") ?: gen.optJSONArray("videos")
        val uri = muestras?.optJSONObject(0)?.let { it.optJSONObject("video")?.optString("uri") ?: it.optString("uri") }
        return if (uri.isNullOrBlank()) Estado(true, error = "La IA no devolvió ningún video. Probá con otra descripción.")
        else Estado(true, uri = uri)
    }

    /** Traduce los errores de la API a algo entendible. */
    fun traducir(mensaje: String, codigo: Int = 0): String {
        val m = mensaje.lowercase(Locale.ROOT)
        return when {
            "api key not valid" in m || "api_key_invalid" in m || codigo == 401 ->
                "La clave de API no es válida. Revisala en Configuración."
            "billing" in m || "paid" in m || "free tier" in m ->
                "Generar videos necesita tener la facturación activada en tu cuenta de Google AI Studio (los videos con IA no son gratis)."
            "quota" in m || "resource_exhausted" in m || codigo == 429 ->
                "Llegaste al límite de uso de tu cuenta. Esperá un rato o revisá tu cuota en Google AI Studio."
            "safety" in m || "blocked" in m || "policy" in m ->
                "La IA no acepta esa descripción por sus reglas de seguridad. Probá describiéndolo de otra forma."
            "not found" in m || codigo == 404 ->
                "Ese modelo ya no está disponible. Tocá \"Actualizar modelos\" en Configuración."
            codigo == 403 || "permission" in m ->
                "Tu clave no tiene permiso para generar videos. Revisá que la facturación esté activada."
            else -> "Error de la IA: ${mensaje.take(300)}"
        }
    }

    // ---------- Red ----------

    private fun pedir(metodo: String, url: String, clave: String, cuerpo: JSONObject? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.setRequestProperty("x-goog-api-key", clave)
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (codigo !in 200..299) {
                val msg = runCatching { JSONObject(texto).getJSONObject("error").optString("message") }.getOrDefault(texto)
                throw ErrorHttp(codigo, msg)
            }
            return texto
        } catch (e: IOException) {
            throw ErrorIA("No se pudo conectar con Google. Revisá tu conexión a internet.")
        } finally {
            c.disconnect()
        }
    }

    class ErrorHttp(val codigo: Int, val mensaje: String) : RuntimeException(mensaje)

    private inline fun <T> amigable(bloque: () -> T): T = try {
        bloque()
    } catch (e: ErrorHttp) {
        throw ErrorIA(traducir(e.mensaje, e.codigo))
    }

    fun listarModelos(clave: String): List<Modelo> = amigable {
        val todos = mutableListOf<Modelo>()
        var pagina: String? = null
        do {
            val url = "$BASE/models?pageSize=1000" + (pagina?.let { "&pageToken=$it" } ?: "")
            val json = pedir("GET", url, clave)
            todos += parsearModelos(json)
            pagina = JSONObject(json).optString("nextPageToken").ifBlank { null }
        } while (pagina != null)
        todos.distinctBy { it.id }.sortedWith(compareBy({ prioridad(it.id) }, { it.id }))
    }

    /** Pide el video y devuelve el nombre de la operación. Si la API rechaza los ajustes, reintenta sin ellos. */
    fun iniciar(clave: String, modelo: String, prompt: String, aspecto: String, noQuiero: String): String {
        val url = "$BASE/models/$modelo:predictLongRunning"
        val json = try {
            pedir("POST", url, clave, cuerpo(prompt, aspecto, noQuiero, conParametros = true))
        } catch (e: ErrorHttp) {
            if (e.codigo != 400 || "api key" in e.mensaje.lowercase(Locale.ROOT)) throw ErrorIA(traducir(e.mensaje, e.codigo))
            amigable { pedir("POST", url, clave, cuerpo(prompt, aspecto, noQuiero, conParametros = false)) }
        }
        return JSONObject(json).optString("name").ifBlank { throw ErrorIA("La IA no aceptó el pedido.") }
    }

    fun consultar(clave: String, operacion: String): Estado = amigable {
        parsearEstado(pedir("GET", "$BASE/$operacion", clave))
    }

    fun descargar(clave: String, uri: String, destino: File) {
        var url = uri
        for (salto in 0 until 5) { // seguimos redirecciones a mano (pueden cambiar de dominio)
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            // la clave solo se manda a Google, nunca al servidor final de la redirección
            if (URL(url).host == "generativelanguage.googleapis.com") c.setRequestProperty("x-goog-api-key", clave)
            val codigo = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw ErrorIA("No se pudo descargar el video. Revisá tu conexión.")
            }
            try {
                when (codigo) {
                    in 200..299 -> {
                        c.inputStream.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
                        return
                    }
                    in 300..399 -> url = URL(URL(url), c.getHeaderField("Location") ?: throw ErrorIA("La descarga no tiene destino.")).toString()
                    else -> throw ErrorIA(traducir(c.errorStream?.bufferedReader()?.readText().orEmpty(), codigo))
                }
            } catch (e: IOException) {
                throw ErrorIA("Se cortó la descarga del video. Revisá tu conexión.")
            } finally {
                c.disconnect()
            }
        }
        throw ErrorIA("Demasiadas redirecciones al descargar el video.")
    }
}
