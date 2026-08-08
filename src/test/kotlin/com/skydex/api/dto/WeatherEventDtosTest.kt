package com.skydex.api.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `photo_url` is persisted relative; this is the one place that composes a host onto it. Pinned
 * directly rather than only through the controller, because the two edge cases that matter — a
 * base URL with a trailing slash, and a value that is already absolute — are cheap here and would
 * each need a whole integration test otherwise.
 */
class WeatherEventDtosTest {

    private fun response(photoUrl: String) = WeatherEventResponse(
        id = UUID.randomUUID(),
        title = "Aurora",
        description = "lights",
        photoUrl = photoUrl,
        capturedAt = Instant.now(),
        latitude = -23.55,
        longitude = -46.63,
        userId = UUID.randomUUID(),
        authorName = "Test Pilot"
    )

    @Test
    fun `composes the base url onto a stored relative path`() {
        val composed = response("/api/photos/abc.jpg").withAbsolutePhotoUrl("http://<host>:8080")

        assertEquals("http://<host>:8080/api/photos/abc.jpg", composed.photoUrl)
    }

    @Test
    fun `a trailing slash on the base url is not doubled`() {
        val composed = response("/api/photos/abc.jpg").withAbsolutePhotoUrl("http://localhost:8080/")

        assertEquals("http://localhost:8080/api/photos/abc.jpg", composed.photoUrl)
    }

    /**
     * Captures created before uploads existed carry externally-hosted URLs. Rewriting those would
     * point them at a host that never had the file. The guard also makes the call idempotent, so a
     * second application on an already-composed response is harmless.
     */
    @Test
    fun `an already-absolute url is left alone`() {
        val composed = response("https://photo-link.com/aurora.jpg").withAbsolutePhotoUrl("http://localhost:8080")

        assertEquals("https://photo-link.com/aurora.jpg", composed.photoUrl)
    }
}
