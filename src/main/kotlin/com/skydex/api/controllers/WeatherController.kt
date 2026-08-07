package com.skydex.api.controllers

import com.skydex.api.dto.NearbyPhenomenonResponse
import com.skydex.api.services.OpenMeteoClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/api/weather")
class WeatherController(private val openMeteoClient: OpenMeteoClient) {

    @GetMapping("/nearby")
    fun nearby(
        @RequestParam lat: Double,
        @RequestParam lon: Double
    ): ResponseEntity<List<NearbyPhenomenonResponse>> {
        val hourly = openMeteoClient.fetchHourlyForecast(lat, lon)?.hourly
            ?: return ResponseEntity.ok(emptyList())

        // OpenMeteoClient asks for past_days=1, so these arrays BEGIN YESTERDAY at 00:00 UTC
        // and run 72 slots. Select by timestamp, never by array index: an index window here
        // would make an endpoint named "nearby" report the past.
        val now = Instant.now()
        val results = mutableListOf<NearbyPhenomenonResponse>()
        val slots = minOf(hourly.time.size, hourly.weatherCode.size, hourly.temperatureCelsius.size)
        for (i in 0 until slots) {
            if (results.size >= FORECAST_HOURS) break
            val slotTime = parseUtcSlot(hourly.time[i]) ?: continue
            if (slotTime.isBefore(now)) continue
            val code = hourly.weatherCode[i] ?: continue
            val alertLevel = when (code) {
                45, 48 -> "Interessante"
                65, 80, 81, 82 -> "Atenção"
                71, 73, 75, 77, 85, 86 -> "Interessante"
                95 -> "Perigo"
                96, 99 -> "Perigo Extremo!"
                else -> "Tranquilo"
            }
            val name = when (code) {
                0, 1 -> "Céu Limpo"
                2, 3 -> "Nublado"
                45, 48 -> "Nevoeiro Intenso"
                51, 53, 55, 56, 57 -> "Garoa"
                61, 63, 65, 66, 67 -> "Chuva"
                71, 73, 75, 77, 85, 86 -> "Neve"
                80, 81, 82 -> "Pancada de Chuva"
                95 -> "Tempestade com Trovões"
                96, 99 -> "Tempestade Severa com Granizo"
                else -> continue
            }
            results.add(
                NearbyPhenomenonResponse(
                    phenomenon = name,
                    time = hourly.time[i],
                    temperatureCelsius = hourly.temperatureCelsius[i],
                    alertLevel = alertLevel
                )
            )
        }
        return ResponseEntity.ok(results)
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
