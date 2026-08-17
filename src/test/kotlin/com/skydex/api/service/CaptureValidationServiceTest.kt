package com.skydex.api.service

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.UnconfirmedReason
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.errors.ServiceUnavailableException
import com.skydex.api.services.CaptureValidationService
import com.skydex.api.services.LastKnownPosition
import com.skydex.api.services.OpenMeteoClient
import com.skydex.api.services.PhotoAuthenticityService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class CaptureValidationServiceTest {

    private val client = mock(OpenMeteoClient::class.java)
    private val authenticity = PhotoAuthenticityService(expectedScoreMax = 0.10, topScoreMin = 0.70)
    private val service = CaptureValidationService(client, authenticity)

    private val at = Instant.parse("2026-08-16T14:10:00Z")

    private fun forecast(
        code: Int?,
        isDay: Int? = 1,
        slot: String = "2026-08-16T14:00"
    ) = OpenMeteoResponse(
        latitude = -30.0,
        longitude = -51.0,
        hourly = HourlyData(
            time = listOf(slot),
            temperatureCelsius = listOf(20.0),
            weatherCode = listOf(code),
            isDay = listOfNotNull(isDay)
        )
    )

    /** Scores in which [winner] takes 0.80 and everything else splits the remainder. */
    private fun photoSaying(winner: String) = mapOf(
        "CLEAR" to 0.04, "CLOUDY" to 0.04, "FOG" to 0.04,
        "RAIN" to 0.04, "SNOW" to 0.04, "STORM" to 0.04
    ) + (winner to 0.80)

    private fun validate(
        code: Int? = 95,
        isDay: Int? = 1,
        previous: LastKnownPosition? = null,
        locationIsMock: Boolean = false,
        photoScores: Map<String, Double>? = photoSaying("STORM")
    ) = service.validate(
        latitude = -30.0,
        longitude = -51.0,
        capturedAt = at,
        previous = previous,
        locationIsMock = locationIsMock,
        photoScores = photoScores
    )

    // --- the happy path -----------------------------------------------------------------------

    @Test
    fun `takes the phenomenon from open-meteo and confirms a consistent photo`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate()

        assertEquals(Phenomenon.THUNDERSTORM, result.phenomenon)
        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(95, result.observedWeatherCode)
        assertEquals(Phenomenon.THUNDERSTORM.rarity.xp, result.xpAwarded)
        assertNull(result.unconfirmedReason)
    }

    @Test
    fun `confirms even when the photo scored a neighbouring group`() {
        // Open-Meteo says thunderstorm, the photo reads as rain. Lightning is rarely in frame,
        // so this is the ordinary case, not a contradiction.
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(photoScores = photoSaying("RAIN"))

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    // --- the three ways to be unconfirmed -----------------------------------------------------

    @Test
    fun `stores the phenomenon but awards nothing when the photo contradicts the weather`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(photoScores = photoSaying("CLEAR"))

        // The phenomenon is still Open-Meteo's — the weather really was a thunderstorm, and
        // blanking that to make the verdict tidier would erase a true fact about the row.
        assertEquals(Phenomenon.THUNDERSTORM, result.phenomenon)
        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.xpAwarded)
        assertEquals(UnconfirmedReason.PHOTO_CONTRADICTS_WEATHER, result.unconfirmedReason)
    }

    @Test
    fun `reports a mocked location`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(locationIsMock = true)

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(UnconfirmedReason.MOCK_LOCATION, result.unconfirmedReason)
        // The phenomenon is still recorded: the weather is a public fact independent of who
        // claims to have been standing in it.
        assertEquals(Phenomenon.THUNDERSTORM, result.phenomenon)
    }

    @Test
    fun `reports an implausible journey`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(
            previous = LastKnownPosition(-3.1, -60.0, at.minusSeconds(60))
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(UnconfirmedReason.IMPLAUSIBLE_TRAVEL, result.unconfirmedReason)
    }

    // --- night and missing analysis -----------------------------------------------------------

    @Test
    fun `does not hold a photo against a night capture`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95, isDay = 0))

        val result = validate(photoScores = photoSaying("CLEAR"))

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    @Test
    fun `treats a missing is_day series as daylight`() {
        // A response from before is_day was requested. Defaulting to night would silently
        // disable the photo check for every capture.
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95, isDay = null))

        val result = validate(photoScores = photoSaying("CLEAR"))

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
    }

    @Test
    fun `confirms a photo that was never analysed`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        assertEquals(ValidationStatus.CONFIRMED, validate(photoScores = null).status)
    }

    // --- 503 -----------------------------------------------------------------------------------

    @Test
    fun `refuses the capture when open-meteo cannot be reached`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(null)

        // Not UNCONFIRMED: without Open-Meteo there is no phenomenon, and the column is NOT NULL.
        // The caller turns this into a 503, before any photo is spent, so the retry is free.
        assertThrows<ServiceUnavailableException> { validate() }
    }

    @Test
    fun `refuses a capture outside the forecast window`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0))
            .thenReturn(forecast(code = 95, slot = "2026-08-10T14:00"))

        assertThrows<ServiceUnavailableException> { validate() }
    }

    @Test
    fun `refuses a slot with no weather code`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = null))

        assertThrows<ServiceUnavailableException> { validate() }
    }

    @Test
    fun `refuses a weather code no species covers`() {
        // Every code Open-Meteo documents maps to a Phenomenon, so this is an upstream anomaly
        // rather than a user problem — and there is no row that can honestly be written for it.
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 4))

        assertThrows<ServiceUnavailableException> { validate() }
    }
}
