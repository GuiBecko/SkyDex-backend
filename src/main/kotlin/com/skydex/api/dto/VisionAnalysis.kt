package com.skydex.api.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * What `skydex-vision` says about one photograph.
 *
 * Evidence, not a verdict. The service has no notion of a `Phenomenon`, a threshold or a
 * `ValidationStatus` — deciding what these numbers mean is [com.skydex.api.services
 * .PhotoAuthenticityService]'s job, and keeping the split there is what lets a threshold change
 * ship without touching Python.
 *
 * [phenomenonScores] is keyed by [com.skydex.api.domain.VisualGroup] name and sums to 1. It is
 * deliberately a plain `Map<String, Double>` rather than a map keyed by the enum: an unknown key
 * from a newer model must not fail deserialisation of an otherwise usable response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class VisionAnalysis(
    @JsonProperty("outdoor_score") val outdoorScore: Double,
    @JsonProperty("phenomenon_scores") val phenomenonScores: Map<String, Double>,
    val model: String
)
