package com.instawasap.videoia

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cliente de Abracadabrax (abracadabrax.app) para generar videos con Kling, Seedance, Sora o Veo
 * usando los créditos de TU cuenta de Abracadabrax.
 *
 * Abracadabrax no tiene una API común: expone un conector MCP (JSON-RPC por HTTP) con tres herramientas:
 *  - prepare_ai_video: arma el borrador y dice cuántos créditos cuesta (gratis)
 *  - generate_media: genera (gasta créditos)
 *  - get_media_job: consulta el estado y devuelve el link del video
 * El inicio de sesión es OAuth 2.1 con PKCE; la app recibe la respuesta en 127.0.0.1 (lo que Abracadabrax permite).
 */
object Abracadabrax {
    const val SITIO = "https://abracadabrax.app"
    const val MCP = "$SITIO/api/manus/ai-video-generator/mcp"
    private const val SCOPE = "abracadabrax:read abracadabrax:write"
    private const val PROTOCOLO = "2025-06-18"

    data class ModeloAbx(val id: String, val nombre: String, val duraciones: List<Int>) {
        override fun toString() = nombre
    }

    /** Modelos y duraciones permitidas según la documentación del conector. */
    val MODELOS = listOf(
        ModeloAbx("kling-3-pro", "Kling 3 Pro (recomendado)", listOf(5, 10)),
        ModeloAbx("kling-3-standard", "Kling 3 Standard (más barato)", listOf(5, 10)),
        ModeloAbx("seedance-1-5-pro", "Seedance 1.5 Pro", listOf(5, 8, 10, 12)),
        ModeloAbx("seedance-1-lite", "Seedance Lite (económico)", listOf(5, 8, 10)),
        ModeloAbx("sora-2", "Sora 2", listOf(4, 8, 12)),
        ModeloAbx("veo-3-1-fast", "Veo 3.1 Fast (con sonido)", listOf(4, 6, 8)),
        ModeloAbx("veo-3-1", "Veo 3.1 (con sonido)", listOf(4, 6, 8)),
    )

    data class Tokens(val acceso: String, val refresco: String?, val venceMs: Long)

    data class Borrador(
        val proyecto: String, val creditos: Int?, val saldo: Int?, val panel: String?, val estado: String,
    )

    data class Trabajo(val estado: String, val id: String?, val video: String?, val error: String?, val panel: String?)

    class SesionVencida : Exception("Tu sesión de Abracadabrax venció. Tocá \"Conectar mi cuenta\" de nuevo.")

    // ---------- OAuth 2.1 + PKCE ----------

    data class Pkce(val verificador: String, val desafio: String)

    fun pkce(): Pkce {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verificador = b64url(bytes)
        val desafio = b64url(MessageDigest.getInstance("SHA-256").digest(verificador.toByteArray(Charsets.US_ASCII)))
        return Pkce(verificador, desafio)
    }

    fun aleatorio(): String = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Registra esta app como cliente (registro dinámico) para la dirección de retorno dada. */
    fun registrar(redireccion: String): String {
        val cuerpo = JSONObject()
            .put("client_name", "VideoIA (Android)")
            .put("redirect_uris", org.json.JSONArray().put(redireccion))
            .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", org.json.JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("scope", SCOPE)
        val r = http("POST", "$SITIO/oauth/register", cuerpo.toString(), "application/json")
        return JSONObject(r.cuerpo).optString("client_id").ifBlank {
            throw ErrorIA("Abracadabrax no aceptó registrar la app: ${r.cuerpo.take(200)}")
        }
    }

    fun urlAutorizar(cliente: String, redireccion: String, desafio: String, estado: String): String =
        "$SITIO/oauth/authorize?response_type=code&client_id=${enc(cliente)}&redirect_uri=${enc(redireccion)}" +
            "&scope=${enc(SCOPE)}&state=${enc(estado)}&code_challenge=${enc(desafio)}&code_challenge_method=S256" +
            "&resource=${enc(MCP)}"

    /**
     * Espera a que el navegador vuelva a http://127.0.0.1:puerto/oauth?code=...&state=...
     * Devuelve el código. Responde con una página que invita a volver a la app.
     */
    fun esperarCodigo(servidor: ServerSocket, estadoEsperado: String, timeoutMs: Int = 5 * 60_000): String {
        servidor.soTimeout = timeoutMs
        while (true) {
            val socket = try {
                servidor.accept()
            } catch (e: SocketTimeoutException) {
                throw ErrorIA("Se cortó el inicio de sesión (pasaron 5 minutos). Probá de nuevo.")
            }
            socket.use { s ->
                val linea = s.getInputStream().bufferedReader().readLine().orEmpty() // "GET /oauth?code=..&state=.. HTTP/1.1"
                val ruta = linea.split(" ").getOrNull(1).orEmpty()
                if (!ruta.startsWith("/oauth")) {
                    responder(s.getOutputStream(), 404, "")
                    return@use
                }
                val params = parsearQuery(ruta.substringAfter('?', ""))
                val error = params["error"]
                val codigo = params["code"]
                val ok = error == null && codigo != null && params["state"] == estadoEsperado
                responder(s.getOutputStream(), 200, paginaRetorno(ok))
                when {
                    error != null -> throw ErrorIA("Abracadabrax no autorizó la conexión ($error).")
                    params["state"] != estadoEsperado -> throw ErrorIA("La respuesta del inicio de sesión no coincide. Probá de nuevo.")
                    codigo != null -> return codigo
                }
            }
        }
    }

    fun parsearQuery(q: String): Map<String, String> =
        q.split("&").filter { "=" in it }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    private fun paginaRetorno(ok: Boolean): String {
        val titulo = if (ok) "¡Listo! Tu cuenta quedó conectada" else "No se pudo conectar"
        return """<!doctype html><html lang="es"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><title>VideoIA</title>
            <style>body{font-family:sans-serif;text-align:center;padding:48px 16px;background:#f4f5f7;color:#1c1e21}
            a{display:inline-block;margin-top:24px;padding:16px 28px;background:#6C3CE0;color:#fff;border-radius:12px;
            text-decoration:none;font-weight:bold;font-size:18px}</style></head>
            <body><h2>$titulo</h2><p>Ya podés volver a VideoIA.</p><a href="videoia://volver">Volver a VideoIA</a>
            <script>setTimeout(function(){location.href='videoia://volver'},800)</script></body></html>"""
    }

    private fun responder(salida: java.io.OutputStream, codigo: Int, html: String) {
        val cuerpo = html.toByteArray(Charsets.UTF_8)
        val estado = if (codigo == 200) "200 OK" else "404 Not Found"
        salida.write(
            ("HTTP/1.1 $estado\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${cuerpo.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        salida.write(cuerpo)
        salida.flush()
    }

    /** Abre un puerto libre solo en este celular (127.0.0.1) para recibir la respuesta. */
    fun abrirServidorLocal(): ServerSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    fun canjear(cliente: String, redireccion: String, codigo: String, verificador: String): Tokens =
        tokens(
            "grant_type=authorization_code&code=${enc(codigo)}&redirect_uri=${enc(redireccion)}" +
                "&client_id=${enc(cliente)}&code_verifier=${enc(verificador)}&resource=${enc(MCP)}",
        )

    fun refrescar(cliente: String, refresco: String): Tokens =
        tokens("grant_type=refresh_token&refresh_token=${enc(refresco)}&client_id=${enc(cliente)}&resource=${enc(MCP)}")

    private fun tokens(form: String): Tokens {
        val r = http("POST", "$SITIO/oauth/token", form, "application/x-www-form-urlencoded")
        if (r.codigo !in 200..299) {
            if (r.codigo == 400 || r.codigo == 401) throw SesionVencida()
            throw ErrorIA("Abracadabrax rechazó el inicio de sesión (${r.codigo}).")
        }
        return parsearTokens(r.cuerpo, System.currentTimeMillis())
    }

    fun parsearTokens(json: String, ahora: Long): Tokens {
        val o = JSONObject(json)
        val acceso = o.optString("access_token").ifBlank { throw ErrorIA("Abracadabrax no devolvió acceso.") }
        val segundos = o.optLong("expires_in", 3600)
        return Tokens(acceso, o.optString("refresh_token").ifBlank { null }, ahora + (segundos - 60) * 1000)
    }

    // ---------- MCP (JSON-RPC por HTTP) ----------

    /** Conexión MCP con la cuenta del usuario. */
    class Conexion(private val token: String) {
        private var sesion: String? = null
        private var id = 0

        private fun rpc(metodo: String, params: JSONObject?, notificacion: Boolean = false): JSONObject? {
            val cuerpo = JSONObject().put("jsonrpc", "2.0").put("method", metodo)
            if (params != null) cuerpo.put("params", params)
            if (!notificacion) cuerpo.put("id", ++id)
            val extra = mutableMapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json, text/event-stream",
                "MCP-Protocol-Version" to PROTOCOLO,
            )
            sesion?.let { extra["Mcp-Session-Id"] = it }
            val r = http("POST", MCP, cuerpo.toString(), "application/json", extra)
            if (r.codigo == 401 || r.codigo == 403) throw SesionVencida()
            r.cabeceras["mcp-session-id"]?.let { sesion = it }
            if (notificacion) return null
            if (r.codigo !in 200..299) throw ErrorIA("Abracadabrax respondió con error (${r.codigo}): ${r.cuerpo.take(200)}")
            val respuesta = parsearRespuestaMcp(r.cuerpo)
            respuesta.optJSONObject("error")?.let { throw ErrorIA("Abracadabrax: ${it.optString("message")}") }
            return respuesta.optJSONObject("result") ?: JSONObject()
        }

        fun iniciar() {
            rpc(
                "initialize",
                JSONObject().put("protocolVersion", PROTOCOLO).put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "VideoIA").put("version", "1.0")),
            )
            rpc("notifications/initialized", null, notificacion = true)
        }

        fun herramienta(nombre: String, args: JSONObject): JSONObject {
            if (sesion == null && id == 0) iniciar()
            val r = rpc("tools/call", JSONObject().put("name", nombre).put("arguments", args))!!
            return resultadoHerramienta(r)
        }
    }

    /** La respuesta puede venir como JSON o como eventos (SSE: "data: {...}"). */
    fun parsearRespuestaMcp(cuerpo: String): JSONObject {
        val t = cuerpo.trim()
        if (t.startsWith("{")) return JSONObject(t)
        val datos = t.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            .filter { it.startsWith("{") }
        return datos.map { JSONObject(it) }.lastOrNull { it.has("result") || it.has("error") }
            ?: throw ErrorIA("Respuesta de Abracadabrax que no se entiende: ${t.take(150)}")
    }

    /** Saca los datos de la herramienta: structuredContent o el JSON que viene como texto. */
    fun resultadoHerramienta(resultado: JSONObject): JSONObject {
        resultado.optJSONObject("structuredContent")?.let { return it }
        val contenido = resultado.optJSONArray("content")
        if (contenido != null) {
            for (i in 0 until contenido.length()) {
                val texto = contenido.optJSONObject(i)?.optString("text").orEmpty().trim()
                if (texto.startsWith("{")) return JSONObject(texto)
            }
            val texto = (0 until contenido.length()).joinToString("\n") { contenido.optJSONObject(it)?.optString("text").orEmpty() }
            if (resultado.optBoolean("isError")) throw ErrorIA("Abracadabrax: ${texto.take(300)}")
            return JSONObject().put("texto", texto)
        }
        return resultado
    }

    // ---------- Herramientas ----------

    fun preparar(c: Conexion, escena: String, estilo: String, formato: String, segundos: Int, modelo: String): Borrador =
        parsearBorrador(
            c.herramienta(
                "prepare_ai_video",
                JSONObject().put("scene", escena).put("style", estilo).put("format", formato)
                    .put("duration", segundos).put("model_id", modelo),
            ),
        )

    fun parsearBorrador(o: JSONObject): Borrador {
        val proyecto = o.optJSONObject("project")?.optString("id").orEmpty()
            .ifBlank { o.optString("project_id") }
            .ifBlank { throw ErrorIA(mensajeEstado(o.optString("state"), o.optString("dashboard_url")) ?: "Abracadabrax no guardó el borrador.") }
        val saldo = o.optJSONObject("balance")?.let { if (it.has("credits")) it.optInt("credits") else null }
        return Borrador(
            proyecto,
            if (o.has("credits_required")) o.optInt("credits_required") else null,
            saldo,
            o.optString("dashboard_url").ifBlank { null },
            o.optString("state"),
        )
    }

    fun generar(c: Conexion, proyecto: String): Trabajo =
        parsearTrabajo(c.herramienta("generate_media", JSONObject().put("project_id", proyecto)))

    fun consultar(c: Conexion, trabajo: String): Trabajo =
        parsearTrabajo(c.herramienta("get_media_job", JSONObject().put("job_id", trabajo)))

    fun parsearTrabajo(o: JSONObject): Trabajo {
        val job = o.optJSONObject("job")
        val resultado = job?.optJSONObject("result")
        val video = resultado?.let { r ->
            r.optJSONObject("video")?.optString("url")?.ifBlank { null } ?: r.optString("video").ifBlank { null }
        }
        val error = job?.opt("error")?.let { if (it is JSONObject) it.optString("message") else it.toString() }
            ?.takeIf { it.isNotBlank() && it != "null" }
        return Trabajo(
            o.optString("state").ifBlank { job?.optString("status").orEmpty() },
            job?.optString("id")?.ifBlank { null },
            video,
            error,
            o.optString("dashboard_url").ifBlank { null },
        )
    }

    /** Mensaje para los estados que no son éxito (null si no hay nada que avisar). */
    fun mensajeEstado(estado: String, panel: String?): String? {
        val enlace = panel?.let { " Seguilo en: $it" }.orEmpty()
        return when (estado) {
            "insufficient_credits" -> "Tu cuenta de Abracadabrax no tiene créditos suficientes. El borrador quedó guardado y sigue cuando cargues créditos.$enlace"
            "pending_payment" -> "Abracadabrax no pudo cobrar los créditos. El borrador quedó guardado.$enlace"
            "account_required" -> "Hace falta conectar tu cuenta de Abracadabrax (⚙ Configuración)."
            "failed" -> "Abracadabrax no pudo terminar el video. Probá de nuevo."
            else -> null
        }
    }

    // ---------- Descarga ----------

    fun descargar(url: String, token: String, destino: File) {
        var actual = url
        for (salto in 0 until 5) {
            val c = URL(actual).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            if (URL(actual).host == "abracadabrax.app") c.setRequestProperty("Authorization", "Bearer $token")
            try {
                when (val codigo = c.responseCode) {
                    in 200..299 -> {
                        c.inputStream.use { e -> destino.outputStream().use { e.copyTo(it) } }
                        return
                    }
                    in 300..399 -> actual = URL(URL(actual), c.getHeaderField("Location") ?: "").toString()
                    else -> throw ErrorIA("No se pudo descargar el video ($codigo).")
                }
            } catch (e: IOException) {
                throw ErrorIA("Se cortó la descarga del video. Revisá tu conexión.")
            } finally {
                c.disconnect()
            }
        }
        throw ErrorIA("Demasiadas redirecciones al descargar el video.")
    }

    // ---------- HTTP ----------

    class Respuesta(val codigo: Int, val cuerpo: String, val cabeceras: Map<String, String>)

    private fun http(
        metodo: String, url: String, cuerpo: String?, tipo: String?, extra: Map<String, String> = emptyMap(),
    ): Respuesta {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 30_000
            c.readTimeout = 90_000
            extra.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (cuerpo != null) {
                c.doOutput = true
                tipo?.let { c.setRequestProperty("Content-Type", it) }
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            val cab = c.headerFields.entries.filter { it.key != null }
                .associate { it.key!!.lowercase() to (it.value.firstOrNull() ?: "") }
            return Respuesta(codigo, texto, cab)
        } catch (e: IOException) {
            throw ErrorIA("No se pudo conectar con Abracadabrax. Revisá tu conexión a internet.")
        } finally {
            c.disconnect()
        }
    }
}
