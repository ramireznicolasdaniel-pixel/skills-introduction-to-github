package com.instawasap.videoia

/**
 * Escenas listas de "Los colores con Tito y Pancho" (personajes originales, estilo dibujo 3D infantil).
 * Cada escena repite la descripción de los personajes para que la IA los dibuje siempre iguales.
 */
object Plantillas {
    const val ESTILO_INFANTIL =
        "3D animated preschool cartoon, soft rounded shapes, bright cheerful colors, glossy toy-like textures, " +
            "soft warm lighting, big expressive eyes, smooth gentle camera, no text on screen"

    private const val PERSONAJES =
        "TITO: a 3-year-old boy with a big round head, rosy cheeks, short brown hair with one curl on top, " +
            "big brown eyes, yellow t-shirt with a white star, blue overalls, red sneakers. " +
            "PANCHO: a small light-brown puppy with one white ear, green collar with a golden bell."

    data class Escena(val titulo: String, val accion: String) {
        val texto get() = "$PERSONAJES $accion"
        override fun toString() = titulo
    }

    val TITO_Y_PANCHO = listOf(
        Escena("1 · Intro en el jardín", "Tito and Pancho run out of a little round house into a sunny flower garden, stop, and wave happily at the camera. Birds chirping, cheerful ukulele music."),
        Escena("2 · Estribillo bailando", "Tito and Pancho dance in a circle on the grass; Tito puts his hand over his eyes and looks up at the sky and down at the ground, curious. Bouncy kids music."),
        Escena("3 · Rojo: la manzana", "Tito finds a big shiny red apple under a tree, holds it up proudly and takes a big crunchy bite; Pancho wags his tail and barks happily."),
        Escena("4 · Amarillo: el sol", "A big smiling cartoon sun shines over the garden; Tito closes his eyes and touches his warm rosy cheeks, smiling; Pancho lies in the sun."),
        Escena("5 · Azul: el mar", "Tito and Pancho at a calm beach shore with clear blue water; small colorful cartoon fish jump out of the water and wave their fins at them; Tito laughs."),
        Escena("6 · Verde: la ranita", "A cute little green frog hops on a lily pad by a pond; Tito and Pancho copy it, hopping together. Playful ribbit sound."),
        Escena("7 · Arcoíris final", "A big bright rainbow appears in the sky over the garden; Tito and Pancho jump with joy and spin around. Uplifting music."),
        Escena("8 · Chau chau", "Tito and Pancho wave goodbye to the camera; Pancho licks Tito's cheek and they both giggle. Gentle ending music."),
    )

    const val LETRA = """¡Colores, colores, vamos a buscar! / Tito y Pancho salen a jugar. / Mirá para arriba, mirá para abajo, / ¿de qué color es? ¡Ya lo encontramos!
Rojo, rojo, la manzana, / la mordemos con muchas ganas. ¡Ñam!
Amarillo es el sol que brilla, / calentito en la mejilla.
Azul, azul, el mar profundo, / los pececitos saludan al mundo.
Verde, verde, la ranita, / salta, salta, chiquitita. ¡Croac!
Rojo, amarillo, azul y verde, / ¡el que juega nunca pierde! ¡Chau, chau, chau!"""
}
