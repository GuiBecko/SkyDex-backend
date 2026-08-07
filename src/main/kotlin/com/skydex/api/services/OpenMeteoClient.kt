package com.skydex.api.services

import com.skydex.api.dto.OpenMeteoResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * Thin HTTP access to the Open-Meteo forecast API. Interpretation of the response
 * (which phenomenon a weather code means) lives in the services that consume this.
 *
 * Times are requested in UTC so they can be compared against capture instants directly.
 */
@Service
class OpenMeteoClient {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create("https://api.open-meteo.com")

    fun fetchHourlyForecast(latitude: Double, longitude: Double): OpenMeteoResponse? =
        try {
            restClient.get()
                .uri(
                    "/v1/forecast?latitude={lat}&longitude={lon}" +
                        "&hourly=temperature_2m,weather_code&timezone=UTC&past_days=1&forecast_days=2",
                    latitude,
                    longitude
                )
                .retrieve()
                .body(OpenMeteoResponse::class.java)
        } catch (e: Exception) {
            log.warn("Open-Meteo request failed for lat={} lon={}", latitude, longitude, e)
            null
        }
}
