package com.instawasap.videoia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbracadabraxTest {
    @Test fun respuestaJsonYEventos() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""
        assertTrue(Abracadabrax.parsearRespuestaMcp(json).getJSONObject("result").getBoolean("ok"))
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"x\":5}}\n\n"
        assertEquals(5, Abracadabrax.parsearRespuestaMcp(sse).getJSONObject("result").getInt("x"))
    }

    @Test fun resultadoDeHerramienta() {
        val estructurado = JSONObject("""{"structuredContent":{"state":"ready"},"content":[{"type":"text","text":"listo"}]}""")
        assertEquals("ready", Abracadabrax.resultadoHerramienta(estructurado).getString("state"))
        val comoTexto = JSONObject("""{"content":[{"type":"text","text":"{\"state\":\"processing\",\"job\":{\"id\":\"j1\"}}"}]}""")
        assertEquals("j1", Abracadabrax.resultadoHerramienta(comoTexto).getJSONObject("job").getString("id"))
    }

    @Test fun borrador() {
        val b = Abracadabrax.parsearBorrador(JSONObject("""
            {"project":{"id":"p123","model_id":"kling-3-pro"},"credits_required":40,
             "balance":{"credits":250,"plan":"free"},"dashboard_url":"https://abracadabrax.app/p/p123","state":"ready"}
        """))
        assertEquals("p123", b.proyecto)
        assertEquals(40, b.creditos)
        assertEquals(250, b.saldo)
        assertEquals("ready", b.estado)
    }

    @Test fun borradorSinCuenta() {
        val sinSaldo = Abracadabrax.parsearBorrador(JSONObject("""{"project":{"id":"p1"},"state":"ready"}"""))
        assertNull(sinSaldo.creditos)
        assertNull(sinSaldo.saldo)
    }

    @Test fun trabajos() {
        val proc = Abracadabrax.parsearTrabajo(JSONObject("""{"state":"processing","job":{"id":"j9"}}"""))
        assertEquals("processing", proc.estado)
        assertEquals("j9", proc.id)
        val listo = Abracadabrax.parsearTrabajo(JSONObject("""{"state":"completed","job":{"id":"j9","result":{"video":"https://abracadabrax.app/media/v.mp4"}}}"""))
        assertEquals("https://abracadabrax.app/media/v.mp4", listo.video)
        val listoObj = Abracadabrax.parsearTrabajo(JSONObject("""{"state":"completed","job":{"result":{"video":{"url":"https://x/v.mp4"}}}}"""))
        assertEquals("https://x/v.mp4", listoObj.video)
        val fallo = Abracadabrax.parsearTrabajo(JSONObject("""{"state":"failed","job":{"id":"j9","error":"content policy"}}"""))
        assertEquals("content policy", fallo.error)
    }

    @Test fun estados() {
        assertTrue(Abracadabrax.mensajeEstado("insufficient_credits", "https://abracadabrax.app/d")!!.contains("créditos"))
        assertTrue(Abracadabrax.mensajeEstado("insufficient_credits", "https://abracadabrax.app/d")!!.contains("https://abracadabrax.app/d"))
        assertNull(Abracadabrax.mensajeEstado("ready", null))
        assertNull(Abracadabrax.mensajeEstado("processing", null))
    }

    @Test fun tokensYQuery() {
        val t = Abracadabrax.parsearTokens("""{"access_token":"a","refresh_token":"r","expires_in":3600}""", 1000)
        assertEquals("a", t.acceso)
        assertEquals("r", t.refresco)
        assertEquals(1000 + 3540_000L, t.venceMs)
        val q = Abracadabrax.parsearQuery("code=abc%2Fdef&state=xyz")
        assertEquals("abc/def", q["code"])
        assertEquals("xyz", q["state"])
    }

    @Test fun modelosYDuraciones() {
        assertEquals("kling-3-pro", Abracadabrax.MODELOS.first().id)
        assertEquals(listOf(4, 6, 8), Abracadabrax.MODELOS.first { it.id == "veo-3-1" }.duraciones)
    }

    @Test fun escenasTitoYPancho() {
        assertEquals(8, Plantillas.TITO_Y_PANCHO.size)
        assertTrue(Plantillas.TITO_Y_PANCHO.all { "TITO:" in it.texto && "PANCHO:" in it.texto })
    }
}
