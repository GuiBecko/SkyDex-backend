package com.skydex.api.controller

import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.services.OpenMeteoClient
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class WeatherControllerTest : IntegrationTestBase() {

    @MockBean
    private lateinit var openMeteoClient: OpenMeteoClient

    /** Formats an hourly slot the way Open-Meteo does: no offset, truncated to the hour, UTC. */
    private fun slotAt(hoursFromNow: Long): String =
        LocalDateTime.ofInstant(Instant.now().plus(hoursFromNow, ChronoUnit.HOURS), ZoneOffset.UTC)
            .withMinute(0).withSecond(0).withNano(0)
            .toString()

    @Test
    fun `maps a thunderstorm weather code to a danger-level phenomenon`() {
        val user = persistUser(email = "weather@skydex.com")
        `when`(openMeteoClient.fetchHourlyForecast(-23.55, -46.63)).thenReturn(
            OpenMeteoResponse(
                latitude = -23.55,
                longitude = -46.63,
                hourly = HourlyData(
                    time = listOf(slotAt(1)),
                    temperatureCelsius = listOf(21.5),
                    weatherCode = listOf(95)
                )
            )
        )

        mockMvc.perform(
            get("/api/weather/nearby")
                .param("lat", "-23.55")
                .param("lon", "-46.63")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].phenomenon").value("Tempestade com Trovões"))
            .andExpect(jsonPath("$[0].alertLevel").value("Perigo"))
            .andExpect(jsonPath("$[0].temperatureCelsius").value(21.5))
    }

    @Test
    fun `returns an empty list when Open-Meteo is unreachable`() {
        val user = persistUser(email = "offline@skydex.com")
        `when`(openMeteoClient.fetchHourlyForecast(1.0, 2.0)).thenReturn(null)

        mockMvc.perform(
            get("/api/weather/nearby")
                .param("lat", "1.0")
                .param("lon", "2.0")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `never reports a slot that has already elapsed`() {
        val user = persistUser(email = "past-day@skydex.com")
        // OpenMeteoClient requests past_days=1, so the real API prepends yesterday's 24 hours.
        // A slot in the past must be filtered out even though it is earlier in the array
        // than the future slot.
        `when`(openMeteoClient.fetchHourlyForecast(10.0, 20.0)).thenReturn(
            OpenMeteoResponse(
                latitude = 10.0,
                longitude = 20.0,
                hourly = HourlyData(
                    time = listOf(slotAt(-2), slotAt(3)),
                    temperatureCelsius = listOf(18.0, 19.0),
                    weatherCode = listOf(95, 61)
                )
            )
        )

        mockMvc.perform(
            get("/api/weather/nearby")
                .param("lat", "10.0")
                .param("lon", "20.0")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].phenomenon").value("Chuva"))
            .andExpect(jsonPath("$[0].temperatureCelsius").value(19.0))
    }
}
