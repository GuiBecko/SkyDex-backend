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

    // --- slot selection: the loop that picks the nearest hourly entry -------------------------

    @Test
    fun `picks the nearest hourly slot, not the first one`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0,
                longitude = -51.0,
                hourly = HourlyData(
                    time = listOf("2026-08-16T12:00", "2026-08-16T13:00", "2026-08-16T14:00"),
                    temperatureCelsius = listOf(20.0, 20.0, 20.0),
                    weatherCode = listOf(0, 0, 95)
                )
            )
        )

        val result = validate()

        assertEquals(Phenomenon.THUNDERSTORM, result.phenomenon)
        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    /**
     * The concrete risk this guards: `minOf(hourly.time.size, hourly.weatherCode.size)` becoming
     * `maxOf` would walk past the shorter list's end and let an `IndexOutOfBoundsException` escape
     * `validate`, turning an upstream that truncated one array (but not the other) into a 500. Built
     * directly rather than through the `forecast` helper, which always keeps the two lists the same
     * length. A usable slot survives within the shared bounds, so this still confirms rather than
     * throwing.
     */
    @Test
    fun `does not throw when the hourly arrays have mismatched lengths`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0,
                longitude = -51.0,
                hourly = HourlyData(
                    time = listOf("2026-08-16T14:00", "2026-08-16T15:00", "2026-08-16T16:00"),
                    temperatureCelsius = listOf(20.0, 20.0, 20.0),
                    weatherCode = listOf(95)
                )
            )
        )

        val result = validate()

        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(95, result.observedWeatherCode)
    }

    @Test
    fun `skips a slot whose timestamp cannot be parsed and confirms from the next one`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0,
                longitude = -51.0,
                hourly = HourlyData(
                    time = listOf("not-a-timestamp", "2026-08-16T14:00"),
                    temperatureCelsius = listOf(20.0, 20.0),
                    // The first slot's code is deliberately unusable (null): it must never be read,
                    // because the timestamp above it fails to parse and the loop should skip past it.
                    weatherCode = listOf(null, 95)
                )
            )
        )

        val result = validate()

        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(95, result.observedWeatherCode)
    }

    @Test
    fun `refuses a capture when the hourly block is empty`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0,
                longitude = -51.0,
                hourly = HourlyData(time = emptyList(), temperatureCelsius = emptyList(), weatherCode = emptyList())
            )
        )

        // nearestIndex never leaves -1 with no slots to scan, so this is the same 503 as any other
        // unusable hourly block rather than the UNCONFIRMED result the old design returned.
        assertThrows<ServiceUnavailableException> { validate() }
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

    // --- travel plausibility: edge cases and the speed boundary -------------------------------

    @Test
    fun `confirms a capture ten kilometres from the previous position an hour later`() {
        // 0.09 degrees of latitude is a hair over 10 km, so an hour of elapsed time makes this
        // about 10 km/h -- a bicycle, not an airliner.
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(
            previous = LastKnownPosition(-30.09, -51.0, at.minusSeconds(3600))
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(Phenomenon.THUNDERSTORM.rarity.xp, result.xpAwarded)
    }

    /**
     * Zero elapsed time is the case a naive `distance / hours` would divide by zero on. It is also
     * a real state: `capturedAt` comes from `Instant.now()`, and two captures can land inside the
     * same tick. Tokyo and this capture's fixed point are a continent apart, so nothing but the
     * zero-elapsed guard explains a refusal here.
     */
    @Test
    fun `does not throw when the previous position shares this capture's instant`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(
            previous = LastKnownPosition(35.6762, 139.6503, at)
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(UnconfirmedReason.IMPLAUSIBLE_TRAVEL, result.unconfirmedReason)
        assertEquals(0, result.xpAwarded)
    }

    /**
     * The same instant at the same place is the one zero-elapsed case that is NOT implausible, and
     * it must not be swept up with the one above: a user who captures twice from one spot inside a
     * single tick has done nothing impossible.
     */
    @Test
    fun `confirms a capture at the previous position even with no time between them`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(
            previous = LastKnownPosition(-30.0, -51.0, at)
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    /**
     * These two straddle the threshold, and they are the only tests here that pin the DISTANCE
     * calculation to a magnitude rather than to an order of magnitude. Both run north-south from the
     * fixed capture point along its meridian, where the haversine reduces exactly to
     * `EARTH_RADIUS_KM * dLatRadians` regardless of the starting latitude -- so the expected
     * distances below are arithmetic, not measurements.
     */
    @Test
    fun `confirms eight hundred kilometres in an hour, just under the speed limit`() {
        // 7.1946 degrees of latitude = 6371 km * 0.12557 rad = 800.0 km.
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(
            previous = LastKnownPosition(-37.1946, -51.0, at.minusSeconds(3600))
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    @Test
    fun `does not confirm a thousand kilometres in an hour, just over the speed limit`() {
        // 8.9932 degrees of latitude = 6371 km * 0.15696 rad = 1000.0 km.
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast(code = 95))

        val result = validate(
            previous = LastKnownPosition(-38.9932, -51.0, at.minusSeconds(3600))
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(UnconfirmedReason.IMPLAUSIBLE_TRAVEL, result.unconfirmedReason)
        assertEquals(0, result.xpAwarded)
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
