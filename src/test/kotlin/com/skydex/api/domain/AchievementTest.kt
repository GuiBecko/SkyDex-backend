package com.skydex.api.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AchievementTest {

    private fun context(
        confirmed: Int = 0,
        unconfirmed: Int = 0,
        distinctSpecies: Int = 0,
        speciesCounts: Map<Phenomenon, Int> = emptyMap(),
        friends: Int = 0
    ) = AchievementContext(
        confirmedCaptures = confirmed,
        unconfirmedCaptures = unconfirmed,
        distinctSpecies = distinctSpecies,
        totalSpecies = Phenomenon.entries.size,
        speciesCounts = speciesCounts,
        friends = friends
    )

    @Test
    fun `a brand new account has earned nothing`() {
        val earned = Achievement.entries.filter { it.isEarnedBy(context()) }
        assertEquals(emptyList<Achievement>(), earned)
    }

    @Test
    fun `three confirmed captures unlocks the three-capture badge but not the ten`() {
        val ctx = context(confirmed = 3)

        assertTrue(Achievement.FIRST_CAPTURE.isEarnedBy(ctx))
        assertTrue(Achievement.THREE_CAPTURES.isEarnedBy(ctx))
        assertFalse(Achievement.TEN_CAPTURES.isEarnedBy(ctx))
        assertFalse(Achievement.FIFTY_CAPTURES.isEarnedBy(ctx))
    }

    @Test
    fun `capture-count badges are cumulative thresholds`() {
        val ctx = context(confirmed = 50)
        assertTrue(Achievement.FIRST_CAPTURE.isEarnedBy(ctx))
        assertTrue(Achievement.THREE_CAPTURES.isEarnedBy(ctx))
        assertTrue(Achievement.TEN_CAPTURES.isEarnedBy(ctx))
        assertTrue(Achievement.FIFTY_CAPTURES.isEarnedBy(ctx))
    }

    @Test
    fun `species badges depend on how many distinct species are collected`() {
        assertFalse(Achievement.FIVE_SPECIES.isEarnedBy(context(distinctSpecies = 4)))
        assertTrue(Achievement.FIVE_SPECIES.isEarnedBy(context(distinctSpecies = 5)))

        assertFalse(Achievement.ALL_SPECIES.isEarnedBy(context(distinctSpecies = Phenomenon.entries.size - 1)))
        assertTrue(Achievement.ALL_SPECIES.isEarnedBy(context(distinctSpecies = Phenomenon.entries.size)))
    }

    @Test
    fun `species-specific badges require that exact species`() {
        val stormOnly = context(confirmed = 1, speciesCounts = mapOf(Phenomenon.THUNDERSTORM to 1))

        assertTrue(Achievement.THUNDER_CHASER.isEarnedBy(stormOnly))
        assertFalse(Achievement.HAIL_SURVIVOR.isEarnedBy(stormOnly))
        assertFalse(Achievement.SNOW_SEEKER.isEarnedBy(stormOnly))
        assertFalse(Achievement.OBVIOUS_PHOTOGRAPHER.isEarnedBy(stormOnly))
    }

    @Test
    fun `the optimist badge rewards being wrong five times`() {
        assertFalse(Achievement.WEATHER_OPTIMIST.isEarnedBy(context(unconfirmed = 4)))
        assertTrue(Achievement.WEATHER_OPTIMIST.isEarnedBy(context(unconfirmed = 5)))
    }

    @Test
    fun `the network badge needs three friends`() {
        assertFalse(Achievement.WEATHER_NETWORK.isEarnedBy(context(friends = 2)))
        assertTrue(Achievement.WEATHER_NETWORK.isEarnedBy(context(friends = 3)))
    }

    @Test
    fun `every achievement has a name and a description`() {
        Achievement.entries.forEach {
            assert(it.displayName.isNotBlank()) { "$it has no display name" }
            assert(it.description.isNotBlank()) { "$it has no description" }
        }
    }

    @Test
    fun `no two achievements share a display name`() {
        val names = Achievement.entries.map { it.displayName }
        assertEquals(names.size, names.distinct().size, "duplicate badge names: $names")
    }
}
