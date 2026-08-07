package com.skydex.api.controller

import com.skydex.api.dto.LoginRequest
import com.skydex.api.dto.RegisterRequest
import com.skydex.api.support.IntegrationTestBase
import com.skydex.api.support.persistUser
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class AuthControllerTest : IntegrationTestBase() {

    @Test
    fun `registers a new user and returns 201`() {
        val request = RegisterRequest(
            name = "Dev SkyDex",
            email = "dev@skydex.com",
            password = "super-safe-password"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.name").value("Dev SkyDex"))
            .andExpect(jsonPath("$.email").value("dev@skydex.com"))
    }

    @Test
    fun `rejects registration with an email that already exists`() {
        persistUser(name = "Existing User", email = "conflict@skydex.com", password = "any-password")

        val request = RegisterRequest(
            name = "Impostor",
            email = "conflict@skydex.com",
            password = "another-password"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Email already registered"))
    }

    @Test
    fun `rejects registration with a password shorter than 8 characters`() {
        val request = RegisterRequest(
            name = "Too Short",
            email = "short@skydex.com",
            password = "short"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("password: Password must be at least 8 characters long"))
    }

    @Test
    fun `logs in with valid credentials and returns a token`() {
        val plainPassword = "my-secret-password"
        persistUser(name = "SkyDex Admin", email = "admin@skydex.com", password = plainPassword)

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("admin@skydex.com", plainPassword)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.userId").exists())
            .andExpect(jsonPath("$.name").value("SkyDex Admin"))
    }

    @Test
    fun `rejects login with the wrong password`() {
        persistUser(name = "SkyDex Admin", email = "admin@skydex.com", password = "right-password")

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("admin@skydex.com", "wrong-password")))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("Invalid email or password"))
    }

    @Test
    fun `rejects login for an unknown email`() {
        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("ghost@skydex.com", "whatever")))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("Invalid email or password"))
    }
}
