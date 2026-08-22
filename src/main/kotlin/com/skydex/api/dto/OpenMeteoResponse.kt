package com.skydex.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val hourly: HourlyData?
)

data class HourlyData(
    val time: List<String>,
    @JsonProperty("temperature_2m") val temperatureCelsius: List<Double?>,
    @JsonProperty("weather_code") val weatherCode: List<Int?>,
    /**
     * Open-Meteo's `is_day`: 1 during daylight at that location, 0 at night.
     *
     * Defaulted to empty, which matters twice. It keeps every existing `HourlyData(...)` in the
     * test suite compiling, and it makes a response that predates this field — a cached one, a
     * proxied one — parse rather than fail. Callers read a missing entry as daylight, which is the
     * permissive choice: night only ever *skips* the photo check, so defaulting to day keeps the
     * check running rather than silently disabling it.
     */
    @JsonProperty("is_day") val isDay: List<Int?> = emptyList()
)
