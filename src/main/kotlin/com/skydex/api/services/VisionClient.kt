package com.skydex.api.services

import com.skydex.api.dto.VisionAnalysis
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

/**
 * Thin HTTP access to `skydex-vision`. Interpretation of the scores lives in
 * [PhotoAuthenticityService]; nothing here knows what a phenomenon is.
 *
 * The failure contract is deliberately [OpenMeteoClient]'s: **every** failure comes back as null
 * rather than as an exception. The caller ([PhotoAnalysisService]) turns a null into a 503, at a
 * point where nothing has been written and no photo has been spent.
 *
 * Timeouts live in [com.skydex.api.config.VisionClientConfig] — see its KDoc for why they are not
 * configured here.
 */
@Service
class VisionClient(
    @Qualifier("visionRestClient") private val restClient: RestClient
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Scores [bytes], or null if the service could not answer.
     *
     * [filename] is sent as the multipart part's filename only. Nothing downstream reads it — the
     * service identifies the image from its content — but a multipart file part without one is
     * rejected by some servers, so it is worth passing the real name through.
     */
    fun analyze(bytes: ByteArray, filename: String): VisionAnalysis? =
        try {
            val body = LinkedMultiValueMap<String, Any>().apply {
                add("file", object : ByteArrayResource(bytes) {
                    override fun getFilename() = filename
                })
            }

            restClient.post()
                .uri("/v1/analyze")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(VisionAnalysis::class.java)
        } catch (e: Exception) {
            log.warn("Vision analysis failed for {}", filename, e)
            null
        }
}
