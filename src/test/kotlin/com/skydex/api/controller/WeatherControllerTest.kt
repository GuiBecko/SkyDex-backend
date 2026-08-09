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
    fun `maps a thunderstorm weather code to its catalog entry`() {
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
            .andExpect(jsonPath("$[0].phenomenon").value("THUNDERSTORM"))
            .andExpect(jsonPath("$[0].phenomenonName").value("Tempestade com Trovões"))
            .andExpect(jsonPath("$[0].rarity").value("RARE"))
            .andExpect(jsonPath("$[0].alertLevel").value("Perigo"))
            .andExpect(jsonPath("$[0].temperatureCelsius").value(21.5))
    }

    @Test
    fun `skips hours whose weather code is not in the catalog`() {
        val user = persistUser(email = "gaps@skydex.com")
        `when`(openMeteoClient.fetchHourlyForecast(1.0, 2.0)).thenReturn(
            OpenMeteoResponse(
                latitude = 1.0,
                longitude = 2.0,
                hourly = HourlyData(
                    time = listOf(slotAt(1), slotAt(2), slotAt(3)),
                    temperatureCelsius = listOf(20.0, 21.0, 22.0),
                    weatherCode = listOf(4, null, 45)
                )
            )
        )

        mockMvc.perform(
            get("/api/weather/nearby")
                .param("lat", "1.0")
                .param("lon", "2.0")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].phenomenon").value("FOG"))
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
            .andExpect(jsonPath("$[0].phenomenon").value("RAIN"))
            .andExpect(jsonPath("$[0].phenomenonName").value("Chuva"))
            .andExpect(jsonPath("$[0].temperatureCelsius").value(19.0))
    }

    // The whole reason this service truncates the window to the hour. Under the OLD controller's
    // bare `Instant.now()` the slot covering the current hour is in the past and gets skipped;
    // under `truncatedTo(HOURS)` it is included. That difference is not cosmetic: the capture
    // screen renders this list while Task 12's validator scores the photo against the slot nearest
    // its timestamp — the same current-hour slot. Without this test the truncation is revertible
    // in silence, and the symptom would surface much later as a spurious UNCONFIRMED.
    // `slotAt(0)` truncates to the current hour, which is the discriminating value.
    @Test
    fun `includes the slot covering the current hour`() {
        val user = persistUser(email = "current-hour@skydex.com")
        `when`(openMeteoClient.fetchHourlyForecast(5.0, 6.0)).thenReturn(
            OpenMeteoResponse(
                latitude = 5.0,
                longitude = 6.0,
                hourly = HourlyData(
                    time = listOf(slotAt(0)),
                    temperatureCelsius = listOf(23.0),
                    weatherCode = listOf(95)
                )
            )
        )

        mockMvc.perform(
            get("/api/weather/nearby")
                .param("lat", "5.0")
                .param("lon", "6.0")
                .header("Authorization", authHeaderFor(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].phenomenon").value("THUNDERSTORM"))
    }
}
