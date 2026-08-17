package com.skydex.api.services

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.VisualGroup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Stage 2 of photo validation: does the photograph contradict the weather Open-Meteo recorded?
 *
 * Pure — no network, no database, no model. Everything it needs arrives as arguments, which is what
 * lets `PhotoAuthenticityServiceTest` pin all thirty-six matrix cells in milliseconds.
 *
 * ## What it is NOT for
 *
 * It does not decide what the phenomenon is. Open-Meteo does that, and by the time this is called
 * the phenomenon is already settled. This answers one narrower question: is the photograph
 * incompatible with it?
 *
 * ## Innocent until confidently proven otherwise
 *
 * Three separate gates have to agree before this returns true, and each one exists to protect an
 * honest user from a model that is merely uncertain:
 *
 * 1. **Night skips the whole check.** No human can separate overcast from clear in the dark either.
 * 2. **The pair must be in the matrix.** A CLOUDY sky scoring as RAIN is not a contradiction —
 *    rain falls out of grey cloud and the photographs are the same.
 * 3. **The model must be confident in both directions.** The expected group has to have scored
 *    below a floor AND the winning group above a ceiling. A model that gives the expected group
 *    even a modest score has seen something consistent with it, and gets no vote.
 *
 * Anything it cannot evaluate — no analysis, an empty analysis, a group name from a newer model —
 * returns false. A check that did not run must never cost a user their capture.
 */
@Service
class PhotoAuthenticityService(
    @Value("\${skydex.vision.expected-score-max:0.10}") private val expectedScoreMax: Double,
    @Value("\${skydex.vision.top-score-min:0.70}") private val topScoreMin: Double
) {

    /**
     * True when [scores] confidently contradict [expected] and the pair is one that cannot be
     * reconciled.
     *
     * @param expected the phenomenon Open-Meteo recorded for the capture's place and time.
     * @param scores the cached `phenomenon_scores` from the photo's upload, or null if none.
     * @param isDay whether Open-Meteo reported daylight at that slot.
     */
    fun contradicts(expected: Phenomenon, scores: Map<String, Double>?, isDay: Boolean): Boolean {
        if (!isDay) return false
        if (scores.isNullOrEmpty()) return false

        val top = scores.maxByOrNull { it.value } ?: return false
        val observed = VisualGroup.fromNameOrNull(top.key) ?: return false

        val expectedGroup = VisualGroup.of(expected)
        if (observed in RECONCILABLE.getValue(expectedGroup)) return false

        val expectedScore = scores[expectedGroup.name] ?: 0.0
        return expectedScore < expectedScoreMax && top.value > topScoreMin
    }

    private companion object {

        /**
         * For each group Open-Meteo can report, the groups a photograph may plausibly score as.
         * The complement of this is the BLOCK matrix in the design document.
         *
         * The rule behind the shape: **when the weather is a distinctive phenomenon (CLEAR, FOG,
         * SNOW) the photograph must show it; when the weather is ordinary (CLOUDY, RAIN, STORM) the
         * photograph may look like anything in that neighbourhood.**
         *
         * - CLEAR admits only CLEAR. If the sky is clear, a photograph of it shows a clear sky.
         *   There is no "I photographed the cloudy part" — that would readmit every recycled
         *   storm photo taken on a sunny day.
         * - CLOUDY admits FOG (dense low cloud reads as mist), RAIN and STORM (a heavy overcast
         *   reads as both). It refuses CLEAR and SNOW.
         * - FOG admits only FOG. Fog is unmistakable and is the class CLIP is most reliable on.
         * - RAIN admits CLOUDY (the drops do not show; the grey sky does), FOG (heavy rain kills
         *   visibility) and STORM.
         * - SNOW admits only SNOW. Snow is EPIC rarity — the highest-value fraud target — and snow
         *   on the ground is among the easiest things in this list for a model to see.
         * - STORM shares RAIN's neighbourhood. Lightning is rarely in frame; a storm photographs
         *   as a dark sky or as rain.
         *
         * The asymmetry is intentional: CLOUDY admits FOG but FOG does not admit CLOUDY. Being
         * generous about what an ordinary sky may look like costs nothing; being generous about
         * what a distinctive phenomenon may look like gives away the whole check.
         */
        val RECONCILABLE: Map<VisualGroup, Set<VisualGroup>> = mapOf(
            VisualGroup.CLEAR to setOf(VisualGroup.CLEAR),
            VisualGroup.CLOUDY to setOf(
                VisualGroup.CLOUDY, VisualGroup.FOG, VisualGroup.RAIN, VisualGroup.STORM
            ),
            VisualGroup.FOG to setOf(VisualGroup.FOG),
            VisualGroup.RAIN to setOf(
                VisualGroup.RAIN, VisualGroup.CLOUDY, VisualGroup.FOG, VisualGroup.STORM
            ),
            VisualGroup.SNOW to setOf(VisualGroup.SNOW),
            VisualGroup.STORM to setOf(
                VisualGroup.STORM, VisualGroup.CLOUDY, VisualGroup.FOG, VisualGroup.RAIN
            )
        )
    }
}
