package com.skydex.api.service

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.services.CaptureValidationService
import com.skydex.api.services.LastKnownPosition
import com.skydex.api.services.OpenMeteoClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant

class CaptureValidationServiceTest {

    private val client = mock(OpenMeteoClient::class.java)
    private val service = CaptureValidationService(client)

    private fun forecast(vararg slots: Pair<String, Int?>) = OpenMeteoResponse(
        latitude = -30.0,
        longitude = -51.0,
        hourly = HourlyData(
            time = slots.map { it.first },
            temperatureCelsius = slots.map { 20.0 },
            weatherCode = slots.map { it.second }
        )
    )

    @Test
    fun `confirms a claim that matches the observed code and awards its rarity xp`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            forecast("2026-08-07T14:00" to 95, "2026-08-07T15:00" to 3)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(95, result.observedWeatherCode)
        assertEquals(Phenomenon.THUNDERSTORM.rarity.xp, result.xpAwarded)
    }

    @Test
    fun `does not confirm a claim that contradicts the observed code`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            forecast("2026-08-07T14:00" to 0)
        )

        val result = service.validate(
            claimed = Phenomenon.HAILSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.observedWeatherCode)
        assertEquals(0, result.xpAwarded)
    }

    @Test
    fun `picks the nearest hourly slot, not the first one`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            forecast(
                "2026-08-07T12:00" to 0,
                "2026-08-07T13:00" to 0,
                "2026-08-07T14:00" to 95
            )
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T13:50:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    @Test
    fun `refuses to confirm when the nearest slot is more than 90 minutes away`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            forecast("2026-08-07T14:00" to 95)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T18:00:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.xpAwarded)
    }

    @Test
    fun `treats an upstream outage as unconfirmed rather than an error`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(null)

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(null, result.observedWeatherCode)
        assertEquals(0, result.xpAwarded)
    }

    /**
     * The concrete risk this guards: `minOf(hourly.time.size, hourly.weatherCode.size)` becoming
     * `maxOf` would walk past the shorter list's end and let an `IndexOutOfBoundsException` escape
     * `validate` — turning an upstream that truncated one array (but not the other) into a 500 that
     * loses the user's capture, instead of the UNCONFIRMED result `validate`'s own contract
     * promises. `time` here is longer than `weatherCode` on purpose, built directly rather than
     * through the `forecast` helper, which always keeps the two lists the same length.
     */
    @Test
    fun `does not throw when the hourly arrays have mismatched lengths`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            OpenMeteoResponse(
                latitude = -30.0,
                longitude = -51.0,
                hourly = HourlyData(
                    time = listOf("2026-08-07T14:00", "2026-08-07T15:00", "2026-08-07T16:00"),
                    temperatureCelsius = listOf(20.0, 20.0, 20.0),
                    weatherCode = listOf(95)
                )
            )
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(95, result.observedWeatherCode)
    }

    @Test
    fun `skips a slot whose timestamp cannot be parsed instead of throwing`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            forecast("not-a-timestamp" to 95)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(null, result.observedWeatherCode)
        assertEquals(0, result.xpAwarded)
    }

    @Test
    fun `does not confirm when the nearest slot's weather code is null`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(
            forecast("2026-08-07T14:00" to null)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(null, result.observedWeatherCode)
        assertEquals(0, result.xpAwarded)
    }

    @Test
    fun `treats an empty hourly block as unconfirmed rather than throwing`() {
        `when`(client.fetchHourlyForecast(-30.0, -51.0)).thenReturn(forecast())

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -30.0,
            longitude = -51.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(null, result.observedWeatherCode)
        assertEquals(0, result.xpAwarded)
    }

    // --- Task 12c: the capture must be somewhere the caller could plausibly be -------------------

    /** Porto Alegre, the fixed origin every travel case below measures from. */
    private val portoAlegre = Pair(-30.0346, -51.2177)

    /** Tokyo. Roughly 18,500 km from [portoAlegre] — about as far as this planet allows. */
    private val tokyo = Pair(35.6762, 139.6503)

    /** A forecast at [tokyo] that agrees with a THUNDERSTORM claim, so only travel can explain a
     *  refusal to confirm. */
    private fun tokyoThunderstormAt(slot: String) {
        `when`(client.fetchHourlyForecast(tokyo.first, tokyo.second)).thenReturn(
            OpenMeteoResponse(
                latitude = tokyo.first,
                longitude = tokyo.second,
                hourly = HourlyData(
                    time = listOf(slot),
                    temperatureCelsius = listOf(19.0),
                    weatherCode = listOf(95)
                )
            )
        )
    }

    @Test
    fun `confirms a capture ten kilometres from the previous position an hour later`() {
        // 0.09 degrees of latitude is a hair over 10 km, so an hour of elapsed time makes this
        // about 10 km/h — a bicycle, not an airliner.
        `when`(client.fetchHourlyForecast(-29.9446, -51.2177)).thenReturn(
            forecast("2026-08-07T14:00" to 95)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = -29.9446,
            longitude = -51.2177,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = LastKnownPosition(
                latitude = portoAlegre.first,
                longitude = portoAlegre.second,
                at = Instant.parse("2026-08-07T13:10:00Z")
            ),
            locationIsMock = false
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
        assertEquals(Phenomenon.THUNDERSTORM.rarity.xp, result.xpAwarded)
    }

    @Test
    fun `does not confirm a capture another continent away minutes after the previous one`() {
        tokyoThunderstormAt("2026-08-07T14:00")

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = tokyo.first,
            longitude = tokyo.second,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = LastKnownPosition(
                latitude = portoAlegre.first,
                longitude = portoAlegre.second,
                at = Instant.parse("2026-08-07T14:05:00Z")
            ),
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.xpAwarded)
        // No observed code, because nothing was observed: the claim was never scored.
        assertEquals(null, result.observedWeatherCode)
        verify(client, never()).fetchHourlyForecast(anyDouble(), anyDouble())
    }

    /**
     * Zero elapsed time is the case a naive `distance / hours` would divide by zero on. It is also
     * a real state: `capturedAt` comes from `Instant.now()`, and two captures can land inside the
     * same tick.
     */
    @Test
    fun `does not throw when the previous position shares this capture's instant`() {
        tokyoThunderstormAt("2026-08-07T14:00")

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = tokyo.first,
            longitude = tokyo.second,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = LastKnownPosition(
                latitude = portoAlegre.first,
                longitude = portoAlegre.second,
                at = Instant.parse("2026-08-07T14:10:00Z")
            ),
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.xpAwarded)
    }

    /**
     * The same instant at the same place is the one zero-elapsed case that is NOT implausible, and
     * it must not be swept up with the one above: a user who captures twice from one spot inside a
     * single tick has done nothing impossible.
     */
    @Test
    fun `confirms a capture at the previous position even with no time between them`() {
        `when`(client.fetchHourlyForecast(portoAlegre.first, portoAlegre.second)).thenReturn(
            forecast("2026-08-07T14:00" to 95)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = portoAlegre.first,
            longitude = portoAlegre.second,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = LastKnownPosition(
                latitude = portoAlegre.first,
                longitude = portoAlegre.second,
                at = Instant.parse("2026-08-07T14:10:00Z")
            ),
            locationIsMock = false
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    /**
     * These two straddle the threshold, and they are the only tests that pin the DISTANCE
     * calculation to a magnitude rather than to an order of magnitude.
     *
     * Every other travel case here is orders of magnitude clear of its budget — 10 km against 900,
     * or 18,500 km against 75 — so a badly broken `greatCircleKm` still lands them all on the right
     * side and the suite stays green. Delete the `Math.toRadians` conversions and the 10 km hop
     * computes as 574 km, comfortably under an hour's 900 km budget, so it still CONFIRMS and its
     * test still passes; Tokyo computes as garbage that is still over 75 km, so it still fails to
     * confirm and its test still passes too. Only a case sitting NEAR the line can tell 800 km from
     * the 5,807 km that same bug produces here. Between them these two pin the distance function
     * and `MAX_SPEED_KMH` together to about ±10%.
     *
     * Both run due north from (0, 0) along a meridian, where the haversine reduces exactly to
     * `EARTH_RADIUS_KM * dLatRadians` — so the expected distances are arithmetic, not measurements,
     * and do not depend on which Earth radius or ellipsoid anyone prefers.
     */
    @Test
    fun `confirms eight hundred kilometres in an hour, just under the speed limit`() {
        // 7.1946 degrees of latitude = 6371 km * 0.12557 rad = 800.0 km.
        `when`(client.fetchHourlyForecast(7.1946, 0.0)).thenReturn(
            forecast("2026-08-07T14:00" to 95)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = 7.1946,
            longitude = 0.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = LastKnownPosition(0.0, 0.0, Instant.parse("2026-08-07T13:10:00Z")),
            locationIsMock = false
        )

        assertEquals(ValidationStatus.CONFIRMED, result.status)
    }

    @Test
    fun `does not confirm a thousand kilometres in an hour, just over the speed limit`() {
        // 8.9932 degrees of latitude = 6371 km * 0.15696 rad = 1000.0 km.
        `when`(client.fetchHourlyForecast(8.9932, 0.0)).thenReturn(
            forecast("2026-08-07T14:00" to 95)
        )

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = 8.9932,
            longitude = 0.0,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = LastKnownPosition(0.0, 0.0, Instant.parse("2026-08-07T13:10:00Z")),
            locationIsMock = false
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.xpAwarded)
    }

    @Test
    fun `does not confirm a capture the client reports as mock-located`() {
        tokyoThunderstormAt("2026-08-07T14:00")

        val result = service.validate(
            claimed = Phenomenon.THUNDERSTORM,
            latitude = tokyo.first,
            longitude = tokyo.second,
            capturedAt = Instant.parse("2026-08-07T14:10:00Z"),
            previous = null,
            locationIsMock = true
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(0, result.xpAwarded)
        assertEquals(null, result.observedWeatherCode)
        verify(client, never()).fetchHourlyForecast(anyDouble(), anyDouble())
    }
}
