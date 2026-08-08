package com.skydex.api.dto

import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * `photo_url` is persisted relative; `from` is the one place that composes a host onto it. Pinned
 * directly rather than only through the controller, because the two edge cases that matter — a
 * base URL with a trailing slash, and a value that is already absolute — are cheap here and would
 * each need a whole integration test otherwise.
 */
class WeatherEventDtosTest {

    private fun event(photoUrl: String) = WeatherEvent(
        id = UUID.randomUUID(),
        title = "Aurora",
        description = "lights",
        photoUrl = photoUrl,
        capturedAt = Instant.now(),
        latitude = -23.55,
        longitude = -46.63,
        userId = UUID.randomUUID()
    )

    private val author = User(id = UUID.randomUUID(), name = "Test Pilot", email = "pilot@skydex.com")

    @Test
    fun `composes the base url onto a stored relative path`() {
        val response = WeatherEventResponse.from(event("/api/photos/abc.jpg"), author, "http://<host>:8080")

        assertEquals("http://<host>:8080/api/photos/abc.jpg", response.photoUrl)
    }

    @Test
    fun `a trailing slash on the base url is not doubled`() {
        val response = WeatherEventResponse.from(event("/api/photos/abc.jpg"), author, "http://localhost:8080/")

        assertEquals("http://localhost:8080/api/photos/abc.jpg", response.photoUrl)
    }

    /**
     * Captures created before uploads existed carry externally-hosted URLs. Rewriting those would
     * point them at a host that never had the file. The guard also makes the mapping idempotent, so
     * a value that already went through `from` survives a second pass unchanged.
     */
    @Test
    fun `an already-absolute url is left alone`() {
        val response = WeatherEventResponse.from(event("https://photo-link.com/aurora.jpg"), author, "http://localhost:8080")

        assertEquals("https://photo-link.com/aurora.jpg", response.photoUrl)
    }
}
