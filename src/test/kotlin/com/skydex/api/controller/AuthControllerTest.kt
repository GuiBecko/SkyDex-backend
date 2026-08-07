package com.skydex.api.controller

import com.skydex.api.controllers.LoginRequest
import com.skydex.api.controllers.RegisterRequest
import com.skydex.api.models.User
import com.skydex.api.support.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

class AuthControllerTest : IntegrationTestBase() {

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `registers a new user and returns 200`() {
        val request = RegisterRequest(
            nome = "Dev SkyDex",
            email = "dev@skydex.com",
            password = "super-safe-password"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mensagem").value("Usuário Registrado com sucesso"))
    }

    @Test
    fun `rejects registration with an email that already exists`() {
        userRepository.save(
            User(
                id = UUID.randomUUID(),
                nome = "Existing User",
                email = "conflict@skydex.com",
                password = passwordEncoder.encode("any-password"),
                dataEntrada = LocalDateTime.now()
            )
        )

        val request = RegisterRequest(
            nome = "Impostor",
            email = "conflict@skydex.com",
            password = "another-password"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Usuário com esse email já cadastrado"))
    }

    @Test
    fun `logs in with valid credentials and returns a token`() {
        val plainPassword = "my-secret-password"
        userRepository.save(
            User(
                id = UUID.randomUUID(),
                nome = "SkyDex Admin",
                email = "admin@skydex.com",
                password = passwordEncoder.encode(plainPassword),
                dataEntrada = LocalDateTime.now()
            )
        )

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("admin@skydex.com", plainPassword)))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tokenGerado").exists())
    }

    @Test
    fun `rejects login with the wrong password`() {
        userRepository.save(
            User(
                id = UUID.randomUUID(),
                nome = "SkyDex Admin",
                email = "admin@skydex.com",
                password = passwordEncoder.encode("right-password"),
                dataEntrada = LocalDateTime.now()
            )
        )

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("admin@skydex.com", "wrong-password")))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("email ou senha inválidos"))
    }

    @Test
    fun `rejects login for an unknown email`() {
        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LoginRequest("ghost@skydex.com", "whatever")))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Usuário não encontrado"))
    }
}
