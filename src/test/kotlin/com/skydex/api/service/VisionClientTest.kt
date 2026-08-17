package com.skydex.api.service

import com.skydex.api.services.VisionClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient

class VisionClientTest {

    // Order matters. `bindTo` installs the mock request factory on the builder as soon as it is
    // called, so the client must be built AFTER it — and the client must not set a request factory
    // of its own, which is why the timeouts live in VisionClientConfig instead.
    private val builder = RestClient.builder().baseUrl(BASE_URL)
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val client = VisionClient(builder.build())

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `parses a successful analysis`() {
        server.expect(requestTo("$BASE_URL/v1/analyze"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """
                    {
                      "outdoor_score": 0.94,
                      "phenomenon_scores": {
                        "CLEAR": 0.02, "CLOUDY": 0.11, "FOG": 0.04,
                        "RAIN": 0.62, "SNOW": 0.01, "STORM": 0.20
                      },
                      "model": "clip-vit-b-32-zeroshot-v1"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        val analysis = client.analyze(jpeg, "storm.jpg")

        assertEquals(0.94, analysis!!.outdoorScore)
        assertEquals(0.62, analysis.phenomenonScores["RAIN"])
        assertEquals("clip-vit-b-32-zeroshot-v1", analysis.model)
        server.verify()
    }

    @Test
    fun `returns null on a server error rather than throwing`() {
        server.expect(requestTo("$BASE_URL/v1/analyze")).andRespond(withServerError())

        // Null, not an exception: the caller decides what an unavailable model means,
        // and every other upstream in this codebase (OpenMeteoClient) behaves the same way.
        assertNull(client.analyze(jpeg, "storm.jpg"))
    }

    @Test
    fun `returns null when the service rejects the image`() {
        server.expect(requestTo("$BASE_URL/v1/analyze"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertNull(client.analyze(jpeg, "storm.jpg"))
    }

    @Test
    fun `returns null on a malformed body`() {
        server.expect(requestTo("$BASE_URL/v1/analyze"))
            .andRespond(withSuccess("not json at all", MediaType.APPLICATION_JSON))

        assertNull(client.analyze(jpeg, "storm.jpg"))
    }

    private companion object {
        const val BASE_URL = "http://vision.test"
    }
}
