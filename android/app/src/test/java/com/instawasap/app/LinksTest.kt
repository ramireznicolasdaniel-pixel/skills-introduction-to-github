package com.instawasap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test fun linksValidos() {
        mapOf(
            "https://www.instagram.com/reel/C5Y3x0qrUGl/?igsh=abc" to "https://www.instagram.com/reel/C5Y3x0qrUGl/?igsh=abc",
            "Mirá esto https://instagram.com/p/ABC123/ jaja" to "https://instagram.com/p/ABC123/",
            "https://www.instagram.com/share/reel/BAz9_x" to "https://www.instagram.com/share/reel/BAz9_x",
            "https://m.instagram.com/usuario.x/reel/XYZ" to "https://m.instagram.com/usuario.x/reel/XYZ",
            "https://instagr.am/tv/Q1" to "https://instagr.am/tv/Q1",
        ).forEach { (texto, esperado) -> assertEquals(texto, esperado, Links.extraer(texto)) }
    }

    @Test fun linksInvalidos() {
        listOf(null, "", "hola", "https://youtube.com/watch?v=1", "https://www.instagram.com/usuario/",
            "https://evil.com/instagram.com/reel/x").forEach { assertNull(it, Links.extraer(it)) }
    }
}

