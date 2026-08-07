package com.skydex.api.dto

data class NearbyPhenomenonResponse(
    val phenomenon: String,
    val time: String,
    val temperatureCelsius: Double?,
    val alertLevel: String
)
