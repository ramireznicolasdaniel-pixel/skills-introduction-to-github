package com.instawasap.app

/** Busca un link de Instagram dentro de cualquier texto (Instagram a veces comparte texto + link). */
object Links {
    private val IG = Regex(
        """https?://(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/(?:[\w.]+/)?""" +
            """(?:p|reel|reels|tv|share(?:/reel|/p)?)/[\w-]+\S*""",
        RegexOption.IGNORE_CASE,
    )

    fun extraer(texto: String?): String? = texto?.let { IG.find(it)?.value }
}
