package com.skydex.api.controller

import com.skydex.api.dto.HourlyData
import com.skydex.api.dto.OpenMeteoResponse
import com.skydex.api.services.OpenMeteoClient
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.authHeaderFor
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class WeatherControllerTest : IntegrationTestBase() {

    @MockBean
    private lateinit var openMeteoClient: OpenMeteoClient

    @Test
    fun `maps a thunderstorm weather code to a danger-level phenomenon`() {
        val user = persistUser(email = "weather@skydex.com")
        `when`(openMeteoClient.fetchHourlyForecast(-23.55, -46.63)).thenReturn(
            OpenMeteoResponse(
                latitude = -23.55,
                longitude = -46.63,
                hourly = HourlyData(
                    time = listOf("2026-08-07T12:00"),
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
}
