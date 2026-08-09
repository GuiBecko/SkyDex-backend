package com.skydex.api.controller

import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.models.User
import com.skydex.api.services.OpenMeteoClient
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUploadedPhoto
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The travel checks, run under `open-in-view=true` — a configuration the application does **not**
 * ship with, and that is the point.
 *
 * As of Task 13 `spring.jpa.open-in-view=false` in every profile: `application.properties` and
 * `application-test.properties` both set it. So this class is not covering production's persistence
 * configuration (it once was, before Task 13, which is what the original version of this comment
 * described). It covers the *other* one.
 *
 * OSIV on means one EntityManager bound to the whole request, entities that stay managed across it,
 * and an identity map that can serve a previously-loaded instance in place of a fresh read. The
 * anti-cheat logic in `CaptureCommitService` turns on exactly the question that difference decides:
 * "are these values the ones in the row I just locked?" `UserRepository.lockTrailForUpdate` answers
 * it with a scalar projection, which is correct under either setting *because* it is not an entity
 * read — and that independence is a claim worth testing rather than asserting.
 *
 * So these tests exist to keep the guarantee from resting on a config flag. Flipping
 * `open-in-view` back on — a plausible future change, and one nobody would think to re-derive the
 * concurrency argument for — cannot silently break the trail checks while this class passes. It is
 * small on purpose: not a second copy of the capture suite, only the parts whose correctness could
 * plausibly depend on whether the persistence context is request-scoped.
 */
@TestPropertySource(properties = ["spring.jpa.open-in-view=true"])
class CaptureTrailOpenInViewTest : IntegrationTestBase() {

    @MockBean
    private lateinit var openMeteoClient: OpenMeteoClient

    private val portoAlegre = Pair(-30.0346, -51.2177)
    private val tokyo = Pair(35.6762, 139.6503)

    private fun currentSlotLabel(): String =
        LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.HOURS), ZoneOffset.UTC).toString()

    private fun thunderstormAt(latitude: Double, longitude: Double) {
        `when`(openMeteoClient.fetchHourlyForecast(latitude, longitude)).thenReturn(
            forecastAt(latitude, longitude)
        )
    }

    private fun forecastAt(latitude: Double, longitude: Double) = OpenMeteoResponse(
        latitude = latitude,
        longitude = longitude,
        hourly = HourlyData(
            time = listOf(currentSlotLabel()),
            temperatureCelsius = listOf(19.0),
            weatherCode = listOf(95)
        )
    )

    private fun freshPhotoFor(owner: User): String =
        "/api/photos/" + persistUploadedPhoto(owner).filename

    private fun thunderstormCapture(photoUrl: String, at: Pair<Double, Double>): String =
        objectMapper.writeValueAsString(
            CreateWeatherEventRequest(
                title = "Tempestade",
                description = "Raios sobre o bairro",
                photoUrl = photoUrl,
                latitude = at.first,
                longitude = at.second,
                phenomenon = "THUNDERSTORM"
            )
        )

    private fun postCapture(user: User, body: String) = mockMvc.perform(
        post("/api/events")
            .header("Authorization", authHeaderFor(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
    )

    private fun recordTrail(user: User, latitude: Double, longitude: Double, at: Instant) {
        userRepository.recordLastCapture(user.id!!, latitude, longitude, at)
    }

    /**
     * The single most important assertion in this class.
     *
     * `SecurityFilter` loads this user by email at the start of every request. Under OSIV that
     * instance can still be managed when `CaptureCommitService` runs, so a locked read that
     * returned the `User` ENTITY could be served the already-loaded copy — carrying the trail as it
     * was before the request, not as the locked row has it. The re-check would then measure against
     * a stale trail and confirm the teleport it exists to refuse.
     *
     * Reading the trail as scalars removes the possibility rather than relying on it not happening.
     * This test is what would notice if someone turned that projection back into an entity query.
     */
    @Test
    fun `downgrades a capture whose trail moved mid-request, with an entity manager open for the whole request`() {
        val user = persistUser(email = "osiv-interleaved@skydex.com")

        `when`(openMeteoClient.fetchHourlyForecast(tokyo.first, tokyo.second)).thenAnswer {
            // A concurrent capture of this user's commits while we wait on the network.
            recordTrail(user, portoAlegre.first, portoAlegre.second, Instant.now())
            forecastAt(tokyo.first, tokyo.second)
        }

        postCapture(user, thunderstormCapture(freshPhotoFor(user), tokyo))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("UNCONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(0))
    }

    /** The burst attack, under the shipped persistence configuration. */
    @Test
    fun `confirms only one of a simultaneous burst, with an entity manager open for the whole request`() {
        val user = persistUser(email = "osiv-burst@skydex.com")
        recordTrail(user, portoAlegre.first, portoAlegre.second, Instant.now().minusSeconds(86_400))

        val destinations = listOf(tokyo, Pair(64.1466, -21.9426), Pair(-33.8688, 151.2093), Pair(51.5072, -0.1276))
        destinations.forEach { thunderstormAt(it.first, it.second) }
        val payloads = destinations.map { thunderstormCapture(freshPhotoFor(user), it) }

        val pool = Executors.newFixedThreadPool(destinations.size)
        val startLine = CountDownLatch(1)
        val statuses = try {
            val inFlight = payloads.map { payload ->
                pool.submit<String> {
                    startLine.await()
                    objectMapper.readTree(
                        postCapture(user, payload).andReturn().response.contentAsString
                    ).get("validationStatus").asText()
                }
            }
            startLine.countDown()
            inFlight.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            1,
            statuses.count { it == "CONFIRMED" },
            "expected exactly one of a simultaneous burst to confirm, got $statuses"
        )
    }

    /** A capture still confirms normally here; the class must not pass by refusing everything. */
    @Test
    fun `confirms a plausible capture, with an entity manager open for the whole request`() {
        val user = persistUser(email = "osiv-honest@skydex.com")
        thunderstormAt(portoAlegre.first, portoAlegre.second)

        postCapture(user, thunderstormCapture(freshPhotoFor(user), portoAlegre))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validationStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$.xpAwarded").value(60))
    }
}
