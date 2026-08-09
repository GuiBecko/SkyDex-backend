package com.skydex.api.domain

/**
 * The SkyDex species list. Each entry owns a disjoint set of WMO weather codes
 * (https://open-meteo.com/en/docs — "Weather variable documentation"), which is how a
 * capture claim gets checked against what the sky was actually doing.
 *
 * Rarity is tuned for Brazil: snow is an expedition, hail with a thunderstorm is the trophy.
 */
enum class Phenomenon(
    val displayName: String,
    val rarity: Rarity,
    val alertLevel: String,
    val weatherCodes: Set<Int>
) {
    CLEAR_SKY("Céu Limpo", Rarity.COMMON, "Tranquilo", setOf(0, 1)),
    CLOUDS("Nublado", Rarity.COMMON, "Tranquilo", setOf(2, 3)),
    FOG("Nevoeiro Intenso", Rarity.UNCOMMON, "Interessante", setOf(45, 48)),
    DRIZZLE("Garoa", Rarity.COMMON, "Tranquilo", setOf(51, 53, 55, 56, 57)),
    RAIN("Chuva", Rarity.COMMON, "Atenção", setOf(61, 63, 65, 66, 67)),
    RAIN_SHOWER("Pancada de Chuva", Rarity.UNCOMMON, "Atenção", setOf(80, 81, 82)),
    SNOW("Neve", Rarity.EPIC, "Interessante", setOf(71, 73, 75, 77, 85, 86)),
    THUNDERSTORM("Tempestade com Trovões", Rarity.RARE, "Perigo", setOf(95)),
    HAILSTORM("Tempestade Severa com Granizo", Rarity.LEGENDARY, "Perigo Extremo!", setOf(96, 99));

    companion object {
        private val byWeatherCode: Map<Int, Phenomenon> =
            entries.flatMap { phenomenon -> phenomenon.weatherCodes.map { it to phenomenon } }.toMap()

        fun fromWeatherCode(code: Int): Phenomenon? = byWeatherCode[code]

        fun fromNameOrNull(name: String): Phenomenon? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
