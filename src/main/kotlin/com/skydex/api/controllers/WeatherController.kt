package com.skydex.api.controllers

import com.skydex.api.dto.NearbyPhenomenonResponse
import com.skydex.api.services.NearbyPhenomenaService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/weather")
class WeatherController(private val nearbyPhenomena: NearbyPhenomenaService) {

    @GetMapping("/nearby")
    fun nearby(
        @RequestParam lat: Double,
        @RequestParam lon: Double
    ): List<NearbyPhenomenonResponse> = nearbyPhenomena.forCoordinates(lat, lon)
}
