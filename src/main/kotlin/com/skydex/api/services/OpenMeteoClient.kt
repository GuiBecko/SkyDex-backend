package com.skydex.api.services

import com.skydex.api.dto.OpenMeteoResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.ClientHttpRequestFactories
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Thin HTTP access to the Open-Meteo forecast API. Interpretation of the response
 * (which phenomenon a weather code means) lives in the services that consume this.
 *
 * Times are requested in UTC so they can be compared against capture instants directly.
 */
@Service
class OpenMeteoClient(
    @Value("\${skydex.open-meteo.base-url:https://api.open-meteo.com}")
    baseUrl: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Built with explicit timeouts rather than via `RestClient.create(...)`, which inherits the
     * JDK default of **no read timeout at all**.
     *
     * That default is the dangerous one. This call runs on a Tomcat request thread, so an upstream
     * that accepts a connection and then stops answering parks one thread per capture attempt for
     * as long as the TCP connection survives — and takes down every endpoint, including the ones
     * that never touch Open-Meteo, once the pool is exhausted. `CaptureCommitService` already
     * reasons at length about not holding a database connection across this call; that mitigated
     * the second-order effect while leaving the first-order one open.
     *
     * The values are chosen against what this call is worth rather than what it might need.
     * Open-Meteo answers a forecast in well under a second normally, and the whole call is
     * optional: on failure [fetchHourlyForecast] returns null and the capture is scored
     * UNCONFIRMED, which is a mildly annoying outcome for one user. Tying up a request thread is
     * an outage for all of them. Five seconds is therefore already generous for the read, and
     * three is ample to complete a TCP+TLS handshake with a healthy host.
     */
    private val restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                    .withConnectTimeout(CONNECT_TIMEOUT)
                    .withReadTimeout(READ_TIMEOUT)
            )
        )
        .build()

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

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
