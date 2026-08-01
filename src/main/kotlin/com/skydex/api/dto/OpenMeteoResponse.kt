package com.skydex.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class OpenMeteoResponse (
    val latitude: Double,
    val longitude: Double,
    val hourly: HourlyData?,
    val nivelAlerta: String?
)

data class HourlyData(
    val time: List<String>,
    val temperature_2m: List<Double?>,
    val weather_code: List<Int?>
)
