package com.skydex.api.service

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.VisualGroup
import com.skydex.api.services.PhotoAuthenticityService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class PhotoAuthenticityServiceTest {

    private val service = PhotoAuthenticityService(
        expectedScoreMax = 0.10,
        topScoreMin = 0.70
    )

    /**
     * Scores in which [winner] takes [share] and the rest is split evenly. Any group not named
     * scores below the 0.10 floor whenever [share] is high enough, which is what makes a
     * contradiction "confident".
     */
    private fun confident(winner: VisualGroup, share: Double = 0.80): Map<String, Double> {
        val others = VisualGroup.entries.filter { it != winner }
        val rest = (1.0 - share) / others.size
        return (others.associate { it.name to rest } + (winner.name to share))
    }

    /**
     * The full 36-cell matrix from the spec. `true` means BLOCK.
     *
     * expected \ photo   CLEAR  CLOUDY  FOG   RAIN  SNOW  STORM
     * CLEAR               ok    BLOCK  BLOCK BLOCK BLOCK BLOCK
     * CLOUDY             BLOCK   ok     ok    ok   BLOCK  ok
     * FOG                BLOCK  BLOCK   ok   BLOCK BLOCK BLOCK
     * RAIN               BLOCK   ok     ok    ok   BLOCK  ok
     * SNOW               BLOCK  BLOCK  BLOCK BLOCK  ok   BLOCK
     * STORM              BLOCK   ok     ok    ok   BLOCK  ok
     */
    private val blocks: Map<VisualGroup, Set<VisualGroup>> = mapOf(
        VisualGroup.CLEAR to setOf(
            VisualGroup.CLOUDY, VisualGroup.FOG, VisualGroup.RAIN, VisualGroup.SNOW, VisualGroup.STORM
        ),
        VisualGroup.CLOUDY to setOf(VisualGroup.CLEAR, VisualGroup.SNOW),
        VisualGroup.FOG to setOf(
            VisualGroup.CLEAR, VisualGroup.CLOUDY, VisualGroup.RAIN, VisualGroup.SNOW, VisualGroup.STORM
        ),
        VisualGroup.RAIN to setOf(VisualGroup.CLEAR, VisualGroup.SNOW),
        VisualGroup.SNOW to setOf(
            VisualGroup.CLEAR, VisualGroup.CLOUDY, VisualGroup.FOG, VisualGroup.RAIN, VisualGroup.STORM
        ),
        VisualGroup.STORM to setOf(VisualGroup.CLEAR, VisualGroup.SNOW)
    )

    /** One representative phenomenon per group, for driving the matrix. */
    private val representative = mapOf(
        VisualGroup.CLEAR to Phenomenon.CLEAR_SKY,
        VisualGroup.CLOUDY to Phenomenon.CLOUDS,
        VisualGroup.FOG to Phenomenon.FOG,
        VisualGroup.RAIN to Phenomenon.RAIN,
        VisualGroup.SNOW to Phenomenon.SNOW,
        VisualGroup.STORM to Phenomenon.THUNDERSTORM
    )

    @TestFactory
    fun `the contradiction matrix, all thirty-six cells`(): List<DynamicTest> =
        VisualGroup.entries.flatMap { expected ->
            VisualGroup.entries.map { photo ->
                val shouldBlock = photo in blocks.getValue(expected)
                DynamicTest.dynamicTest("expected $expected, photo says $photo -> ${if (shouldBlock) "BLOCK" else "ok"}") {
                    val result = service.contradicts(
                        expected = representative.getValue(expected),
                        scores = confident(photo),
                        isDay = true
                    )
                    if (shouldBlock) assertTrue(result) else assertFalse(result)
                }
            }
        }

    @Test
    fun `never blocks at night`() {
        // At night nobody can tell an overcast sky from a clear one, model included.
        assertFalse(
            service.contradicts(Phenomenon.THUNDERSTORM, confident(VisualGroup.CLEAR), isDay = false)
        )
    }

    @Test
    fun `never blocks when the winning group is not confident enough`() {
        // CLEAR wins, but only at 0.55 — under the 0.70 bar. An uncertain model gets no vote.
        val scores = mapOf(
            "CLEAR" to 0.55, "CLOUDY" to 0.30, "FOG" to 0.05,
            "RAIN" to 0.04, "SNOW" to 0.03, "STORM" to 0.03
        )

        assertFalse(service.contradicts(Phenomenon.THUNDERSTORM, scores, isDay = true))
    }

    @Test
    fun `never blocks when the expected group still scored above the floor`() {
        // CLEAR wins at 0.75, but STORM held 0.15 — above the 0.10 floor. The model saw
        // something storm-like, so it is not contradicting, only disagreeing about emphasis.
        val scores = mapOf(
            "CLEAR" to 0.75, "CLOUDY" to 0.04, "FOG" to 0.02,
            "RAIN" to 0.02, "SNOW" to 0.02, "STORM" to 0.15
        )

        assertFalse(service.contradicts(Phenomenon.THUNDERSTORM, scores, isDay = true))
    }

    @Test
    fun `never blocks when there is no analysis`() {
        // A photo uploaded before the analysis shipped, or one whose analysis was lost. A check
        // that did not run must not punish the photo it never looked at.
        assertFalse(service.contradicts(Phenomenon.THUNDERSTORM, scores = null, isDay = true))
    }

    @Test
    fun `never blocks when the analysis is empty`() {
        assertFalse(service.contradicts(Phenomenon.THUNDERSTORM, scores = emptyMap(), isDay = true))
    }

    @Test
    fun `never blocks when the winning group is one this build does not know`() {
        // A newer model reporting a group this build has no matrix row for. No opinion, no block.
        val scores = mapOf("TORNADO" to 0.90, "CLEAR" to 0.05, "STORM" to 0.05)

        assertFalse(service.contradicts(Phenomenon.THUNDERSTORM, scores, isDay = true))
    }

    @Test
    fun `every phenomenon in a group behaves like its group`() {
        // Drizzle must block a CLEAR photo exactly as RAIN does; the matrix is defined on
        // groups, and this is what proves the collapse is actually being applied.
        listOf(Phenomenon.DRIZZLE, Phenomenon.RAIN, Phenomenon.RAIN_SHOWER).forEach {
            assertTrue(service.contradicts(it, confident(VisualGroup.CLEAR), isDay = true), "$it")
        }
        listOf(Phenomenon.THUNDERSTORM, Phenomenon.HAILSTORM).forEach {
            assertTrue(service.contradicts(it, confident(VisualGroup.CLEAR), isDay = true), "$it")
        }
    }
}
