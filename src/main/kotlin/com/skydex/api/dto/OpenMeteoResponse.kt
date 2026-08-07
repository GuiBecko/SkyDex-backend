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
    @JsonProperty("weather_code") val weatherCode: List<Int?>
)
