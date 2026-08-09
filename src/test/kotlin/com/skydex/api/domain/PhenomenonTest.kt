package com.skydex.api.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PhenomenonTest {

    @Test
    fun `maps the WMO codes this app cares about`() {
        assertEquals(Phenomenon.CLEAR_SKY, Phenomenon.fromWeatherCode(0))
        assertEquals(Phenomenon.CLOUDS, Phenomenon.fromWeatherCode(3))
        assertEquals(Phenomenon.FOG, Phenomenon.fromWeatherCode(45))
        assertEquals(Phenomenon.RAIN, Phenomenon.fromWeatherCode(65))
        assertEquals(Phenomenon.RAIN_SHOWER, Phenomenon.fromWeatherCode(82))
        assertEquals(Phenomenon.SNOW, Phenomenon.fromWeatherCode(75))
        assertEquals(Phenomenon.THUNDERSTORM, Phenomenon.fromWeatherCode(95))
        assertEquals(Phenomenon.HAILSTORM, Phenomenon.fromWeatherCode(99))
    }

    @Test
    fun `returns null for a code outside the catalog`() {
        assertNull(Phenomenon.fromWeatherCode(4))
        assertNull(Phenomenon.fromWeatherCode(-1))
    }

    @Test
    fun `no weather code belongs to two species`() {
        val seen = mutableMapOf<Int, Phenomenon>()
        Phenomenon.entries.forEach { phenomenon ->
            phenomenon.weatherCodes.forEach { code ->
                val previous = seen.put(code, phenomenon)
                if (previous != null) {
                    throw AssertionError("code $code claimed by both $previous and $phenomenon")
                }
            }
        }
    }

    @Test
    fun `rarity tiers award increasing xp`() {
        val xpByTier = Rarity.entries.map { it.xp }
        assertEquals(xpByTier.sorted(), xpByTier, "Rarity entries must be declared cheapest first")
    }

    @Test
    fun `every species has a non-empty display name and at least one code`() {
        Phenomenon.entries.forEach {
            assert(it.displayName.isNotBlank()) { "$it has no display name" }
            assert(it.weatherCodes.isNotEmpty()) { "$it has no weather codes" }
        }
    }
}
