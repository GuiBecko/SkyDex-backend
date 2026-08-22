package com.skydex.api.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.skydex.api.dto.VisionAnalysis
import com.skydex.api.errors.ServiceUnavailableException
import com.skydex.api.errors.UnprocessableContentException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Stage 1 of photo validation, plus the persistence format for stage 2's inputs.
 *
 * ## Why this runs at upload and not at capture
 *
 * Three reasons, all load-bearing:
 *
 * - **The latency disappears.** The client fires the upload the moment the shutter closes and the
 *   user then spends several seconds typing a title, so the quarter-second forward pass is free.
 * - **The result is cached** on the `uploaded_photos` row, so the model runs exactly once per
 *   photograph however many times a capture is retried against it.
 * - **An outage costs nothing.** This point in the flow is before any photo row exists, before any
 *   photo is spent, and before any Open-Meteo call is paid for. A 503 here is a clean retry.
 *
 * ## Why a rejection is 422 and not 400
 *
 * The upload is well-formed and the caller is entitled to make it. What is wrong is the picture.
 * The Android error presenter reads a 400 as "re-check what you typed", which is the wrong
 * instruction for someone who needs to point the camera somewhere else.
 */
@Service
class PhotoAnalysisService(
    private val vision: VisionClient,
    private val objectMapper: ObjectMapper,
    @Value("\${skydex.vision.outdoor-min:0.60}") private val outdoorMin: Double
) {

    /**
     * Scores [bytes], or refuses the upload.
     *
     * @throws ServiceUnavailableException the model could not be reached, or rejected the bytes.
     *   Both are the server's problem from the caller's side: they uploaded a JPEG that
     *   `PhotoStorageService` already verified the magic bytes of, so a model that will not read it
     *   is a model that is misbehaving.
     * @throws UnprocessableContentException the model does not believe this is an outdoor sky.
     */
    fun analyze(bytes: ByteArray, filename: String): VisionAnalysis {
        val analysis = vision.analyze(bytes, filename)
            ?: throw ServiceUnavailableException("Photo analysis is unavailable right now")

        if (analysis.outdoorScore < outdoorMin) {
            log.info(
                "Rejecting an upload: outdoor score {} is below {} (model {})",
                analysis.outdoorScore, outdoorMin, analysis.model
            )
            throw UnprocessableContentException("This photo does not look like the sky")
        }

        return analysis
    }

    fun serialise(analysis: VisionAnalysis): String =
        objectMapper.writeValueAsString(analysis.phenomenonScores)

    /**
     * The stored scores, or null when there are none to read.
     *
     * Unparseable JSON also yields null rather than throwing. This is read on the capture path, and
     * a stored blob that cannot be parsed is a bug in something that already happened — failing the
     * user's capture over it would punish them for it twice.
     */
    fun deserialise(json: String?): Map<String, Double>? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue<Map<String, Double>>(json)
        } catch (e: Exception) {
            log.warn("Stored vision scores could not be parsed; treating as no analysis", e)
            null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(PhotoAnalysisService::class.java)
    }
}
