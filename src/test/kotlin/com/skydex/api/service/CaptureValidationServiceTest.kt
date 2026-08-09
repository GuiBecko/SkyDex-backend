package com.skydex.api.service

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.services.CaptureValidationService
import com.skydex.api.services.OpenMeteoClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
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
            capturedAt = Instant.parse("2026-08-07T14:10:00Z")
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
            capturedAt = Instant.parse("2026-08-07T14:10:00Z")
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
            capturedAt = Instant.parse("2026-08-07T13:50:00Z")
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
            capturedAt = Instant.parse("2026-08-07T18:00:00Z")
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
            capturedAt = Instant.parse("2026-08-07T14:10:00Z")
        )

        assertEquals(ValidationStatus.UNCONFIRMED, result.status)
        assertEquals(null, result.observedWeatherCode)
        assertEquals(0, result.xpAwarded)
    }
}
