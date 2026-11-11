package com.example.hesba.domain

/**
 * Salutation de l'Accueil selon l'heure du téléphone.
 * Chaque langue choisit son texte pour chaque moment (en français, « Bonjour » le matin et
 * l'après-midi, « Bonsoir » à partir de 18 h).
 */
object Greeting {

    enum class Period { MORNING, AFTERNOON, EVENING }

    /** [hour] de 0 à 23. Matin : 5 h – 11 h 59 ; après-midi : 12 h – 17 h 59 ; soir : 18 h – 4 h 59. */
    fun period(hour: Int): Period = when (hour) {
        in 5..11 -> Period.MORNING
        in 12..17 -> Period.AFTERNOON
        else -> Period.EVENING
    }
}
