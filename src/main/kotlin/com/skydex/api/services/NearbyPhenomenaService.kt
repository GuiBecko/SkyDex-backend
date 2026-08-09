package com.skydex.api.services

import com.skydex.api.domain.Phenomenon
import com.skydex.api.dto.NearbyPhenomenonResponse
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

@Service
class NearbyPhenomenaService(private val openMeteoClient: OpenMeteoClient) {

    /**
     * The next [FORECAST_HOURS] hourly slots that map to a species in the catalog.
     *
     * OpenMeteoClient asks for past_days=1, so the arrays BEGIN YESTERDAY at 00:00 UTC and run
     * 72 slots. Slots are therefore selected by timestamp, never by array index — slicing
     * `0 until 24` would make this service report the 24 hours that already elapsed. Verified
     * against the live API: index 0 is yesterday 00:00 UTC, index 24 is today 00:00 UTC.
     */
    fun forCoordinates(latitude: Double, longitude: Double): List<NearbyPhenomenonResponse> {
        val hourly = openMeteoClient.fetchHourlyForecast(latitude, longitude)?.hourly
            ?: return emptyList()

        // Truncated to the hour so the slot COVERING the current hour is included, not skipped.
        // This matters beyond cosmetics: the capture screen shows this list, and Task 12's
        // validator scores a capture against the hourly slot nearest its timestamp — which is
        // that same current-hour slot. Comparing against a bare Instant.now() would offer the
        // user next hour's phenomenon while validating their photo against this hour's.
        val windowStart = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val slots = minOf(hourly.time.size, hourly.weatherCode.size, hourly.temperatureCelsius.size)
        val results = mutableListOf<NearbyPhenomenonResponse>()

        for (i in 0 until slots) {
            if (results.size >= FORECAST_HOURS) break
            val slotTime = parseUtcSlot(hourly.time[i]) ?: continue
            if (slotTime.isBefore(windowStart)) continue

            val code = hourly.weatherCode[i] ?: continue
            val phenomenon = Phenomenon.fromWeatherCode(code) ?: continue
            results.add(
                NearbyPhenomenonResponse.from(phenomenon, hourly.time[i], hourly.temperatureCelsius[i])
            )
        }
        return results
    }

    /** Open-Meteo returns "2026-08-07T14:00" with no offset; the client requested timezone=UTC. */
    private fun parseUtcSlot(raw: String): Instant? = try {
        LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        const val FORECAST_HOURS = 24
    }
}
