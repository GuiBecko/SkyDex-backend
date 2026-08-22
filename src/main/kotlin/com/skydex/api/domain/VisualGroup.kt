package com.skydex.api.domain

/**
 * The six classes a photograph can actually be sorted into, as opposed to the nine [Phenomenon]
 * values Open-Meteo's weather codes resolve to.
 *
 * The collapse is not a simplification for convenience. Drizzle, rain and a rain shower produce the
 * same image — raindrops are near-invisible to a phone camera and what shows is grey sky — and a
 * hailstorm photographs exactly like a thunderstorm, because the hail is on the ground and the
 * frame is pointed up. Asking a model to separate them would be asking it to guess, and then acting
 * on the guess.
 *
 * These names are a contract with `skydex-vision`: they are the keys of
 * [com.skydex.api.dto.VisionAnalysis.phenomenonScores]. Renaming one here without renaming it in
 * `app/prompts.py` silently turns every score for that group into "unknown group", which
 * [com.skydex.api.services.PhotoAuthenticityService] treats as no opinion — so the failure is a
 * quiet loss of enforcement, not an error anybody sees.
 */
enum class VisualGroup {
    CLEAR,
    CLOUDY,
    FOG,
    RAIN,
    SNOW,
    STORM;

    companion object {
        fun of(phenomenon: Phenomenon): VisualGroup = when (phenomenon) {
            Phenomenon.CLEAR_SKY -> CLEAR
            Phenomenon.CLOUDS -> CLOUDY
            Phenomenon.FOG -> FOG
            Phenomenon.DRIZZLE, Phenomenon.RAIN, Phenomenon.RAIN_SHOWER -> RAIN
            Phenomenon.SNOW -> SNOW
            Phenomenon.THUNDERSTORM, Phenomenon.HAILSTORM -> STORM
        }

        /** Null for a group name this build does not know — see the class KDoc. */
        fun fromNameOrNull(name: String): VisualGroup? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
