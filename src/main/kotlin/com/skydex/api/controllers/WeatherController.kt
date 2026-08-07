package com.skydex.api.controllers

import com.skydex.api.dto.NearbyPhenomenonResponse
import com.skydex.api.services.OpenMeteoClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/weather")
class WeatherController(private val openMeteoClient: OpenMeteoClient) {

    @GetMapping("/nearby")
    fun nearby(
        @RequestParam lat: Double,
        @RequestParam lon: Double
    ): ResponseEntity<List<NearbyPhenomenonResponse>> {
        val forecast = openMeteoClient.fetchHourlyForecast(lat, lon)
        val hourly = forecast?.hourly ?: return ResponseEntity.ok(emptyList())

        val results = mutableListOf<NearbyPhenomenonResponse>()
        val slots = minOf(24, hourly.time.size, hourly.weatherCode.size, hourly.temperatureCelsius.size)
        for (i in 0 until slots) {
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
}
