package com.instawasap.videoia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTest {
    private val modelosJson = """
        {"models": [
          {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash", "supportedGenerationMethods": ["generateContent"]},
          {"name": "models/veo-3.1-generate-preview", "displayName": "Veo 3.1", "supportedGenerationMethods": ["predictLongRunning"]},
          {"name": "models/veo-3.1-fast-generate-preview", "displayName": "Veo 3.1 Fast", "supportedGenerationMethods": ["predictLongRunning"]},
          {"name": "models/veo-3.1-lite-generate-preview", "displayName": "", "supportedGenerationMethods": ["predictLongRunning"]},
          {"name": "models/imagen-4.0-generate-001", "displayName": "Imagen 4", "supportedGenerationMethods": ["predict"]}
        ], "nextPageToken": ""}
    """.trimIndent()

    @Test fun soloModelosDeVideoYOrdenSugerido() {
        val m = Gemini.parsearModelos(modelosJson)
        assertEquals(listOf("veo-3.1-fast-generate-preview", "veo-3.1-lite-generate-preview", "veo-3.1-generate-preview"), m.map { it.id })
        assertTrue(m[0].nombre.startsWith("Veo 3.1 Fast"))
        assertTrue(m[1].nombre.startsWith("veo-3.1-lite")) // sin displayName usa el id
        assertTrue(m[0].nombre.contains("US$"))
    }

    @Test fun sinModelos() {
        assertTrue(Gemini.parsearModelos("{}").isEmpty())
    }

    @Test fun cuerpoConYSinParametros() {
        val con = Gemini.cuerpo("un perro", "9:16", "texto", conParametros = true)
        assertEquals("un perro", con.getJSONArray("instances").getJSONObject(0).getString("prompt"))
        assertEquals("9:16", con.getJSONObject("parameters").getString("aspectRatio"))
        assertEquals("texto", con.getJSONObject("parameters").getString("negativePrompt"))
        val sinNegativo = Gemini.cuerpo("x", "16:9", " ", conParametros = true)
        assertFalse(sinNegativo.getJSONObject("parameters").has("negativePrompt"))
        assertFalse(Gemini.cuerpo("x", "16:9", "", conParametros = false).has("parameters"))
    }

    @Test fun imagenEnLosDosFormatos() {
        val img = Gemini.Imagen("QUJD", "image/jpeg")
        val f0 = Gemini.cuerpo("", "9:16", "", true, img, 0).getJSONArray("instances").getJSONObject(0)
        assertEquals(Gemini.IDEA_POR_DEFECTO, f0.getString("prompt")) // sin idea usa una por defecto
        assertEquals("QUJD", f0.getJSONObject("image").getJSONObject("inlineData").getString("data"))
        assertEquals("image/jpeg", f0.getJSONObject("image").getJSONObject("inlineData").getString("mimeType"))
        val f1 = Gemini.cuerpo("que vuele", "9:16", "", true, img, 1).getJSONArray("instances").getJSONObject(0)
        assertEquals("que vuele", f1.getString("prompt"))
        assertEquals("QUJD", f1.getJSONObject("image").getString("bytesBase64Encoded"))
        assertFalse(Gemini.cuerpo("x", "9:16", "", true).getJSONArray("instances").getJSONObject(0).has("image"))
    }

    @Test fun enProceso() {
        assertFalse(Gemini.parsearEstado("""{"name": "models/veo/operations/abc"}""").terminado)
        assertFalse(Gemini.parsearEstado("""{"name": "x", "done": false}""").terminado)
    }

    @Test fun videoListo() {
        val e = Gemini.parsearEstado("""
            {"name": "x", "done": true, "response": {"@type": "t", "generateVideoResponse":
              {"generatedSamples": [{"video": {"uri": "https://generativelanguage.googleapis.com/v1beta/files/abc:download?alt=media"}}]}}}
        """)
        assertTrue(e.terminado)
        assertNull(e.error)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/files/abc:download?alt=media", e.uri)
    }

    @Test fun formatoAlternativo() {
        val e = Gemini.parsearEstado("""{"done": true, "response": {"generatedVideos": [{"video": {"uri": "https://x/y"}}]}}""")
        assertEquals("https://x/y", e.uri)
    }

    @Test fun bloqueadoPorSeguridad() {
        val e = Gemini.parsearEstado("""
            {"done": true, "response": {"generateVideoResponse": {"raiMediaFilteredCount": 1,
              "raiMediaFilteredReasons": ["Contiene personas famosas"]}}}
        """)
        assertTrue(e.error!!.contains("seguridad"))
        assertTrue(e.error!!.contains("Contiene personas famosas"))
    }

    @Test fun errorDeOperacion() {
        val e = Gemini.parsearEstado("""{"done": true, "error": {"code": 8, "message": "Resource has been exhausted (e.g. check quota)."}}""")
        assertTrue(e.error!!.contains("límite"))
    }

    @Test fun traduccionDeErrores() {
        assertTrue(Gemini.traducir("API key not valid. Please pass a valid API key.", 400).contains("clave"))
        assertTrue(Gemini.traducir("This model is only available on the paid tier. Please enable billing.").contains("facturación"))
        assertTrue(Gemini.traducir("models/veo-9 is not found for API version v1beta", 404).contains("Actualizar modelos"))
        assertTrue(Gemini.traducir("otra cosa rara").startsWith("Error de la IA"))
    }

    @Test fun costos() {
        assertTrue(Gemini.costoTexto("veo-3.1-lite-generate-preview").contains("0,40"))
        assertTrue(Gemini.costoTexto("veo-3.1-fast-generate-preview").contains("0,80"))
        assertTrue(Gemini.costoTexto("veo-3.1-generate-preview").contains("3,20"))
        JSONObject() // org.json disponible en tests
    }
}
