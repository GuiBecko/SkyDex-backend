package com.skydex.api.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VisualGroupTest {

    @Test
    fun `every phenomenon maps to a group`() {
        // An unmapped phenomenon would throw at capture time, in production, on whatever
        // rare species somebody finally photographed. The `when` in VisualGroup.of is
        // exhaustive, so this only fails if someone adds a phenomenon and a null branch.
        Phenomenon.entries.forEach { assertNotNull(VisualGroup.of(it)) }
    }

    @Test
    fun `the rain family collapses to one group`() {
        // Drizzle, rain and rain showers are the same photograph. Asking the model to
        // separate them would be asking it to guess.
        assertEquals(VisualGroup.RAIN, VisualGroup.of(Phenomenon.DRIZZLE))
        assertEquals(VisualGroup.RAIN, VisualGroup.of(Phenomenon.RAIN))
        assertEquals(VisualGroup.RAIN, VisualGroup.of(Phenomenon.RAIN_SHOWER))
    }

    @Test
    fun `both storms collapse to one group`() {
        assertEquals(VisualGroup.STORM, VisualGroup.of(Phenomenon.THUNDERSTORM))
        assertEquals(VisualGroup.STORM, VisualGroup.of(Phenomenon.HAILSTORM))
    }

    @Test
    fun `the distinctive phenomena keep their own group`() {
        assertEquals(VisualGroup.CLEAR, VisualGroup.of(Phenomenon.CLEAR_SKY))
        assertEquals(VisualGroup.CLOUDY, VisualGroup.of(Phenomenon.CLOUDS))
        assertEquals(VisualGroup.FOG, VisualGroup.of(Phenomenon.FOG))
        assertEquals(VisualGroup.SNOW, VisualGroup.of(Phenomenon.SNOW))
    }

    @Test
    fun `an unknown group name resolves to null rather than throwing`() {
        // A newer vision model could report a group this build has never heard of. That must
        // degrade to "no opinion", never to a 500 on the capture path.
        assertNull(VisualGroup.fromNameOrNull("TORNADO"))
        assertEquals(VisualGroup.STORM, VisualGroup.fromNameOrNull("storm"))
    }
}
