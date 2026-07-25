package com.skydex.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.controllers.LoginRequest
import com.skydex.api.controllers.RegisterRequest
import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    // Limpa o banco de dados antes de cada teste para garantir resultados isolados
    @BeforeEach
    fun setup() {
        userRepository.deleteAll()
    }

    // --- TESTES DE REGISTRO ---

    @Test
    fun `deve registrar um novo usuario com sucesso e retornar status 200`() {
        val registerRequest = RegisterRequest(
            nome = "Dev SkyDex",
            email = "dev@skydex.com",
            password = "senha-super-segura"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mensagem").value("Usuário Registrado com sucesso"))
    }

    @Test
    fun `deve retornar erro 400 ao tentar registrar email ja existente`() {
        // 1. Salva um usuário pré-existente no banco
        userRepository.save(
            User(
                id = UUID.randomUUID(),
                nome = "Usuario Antigo",
                email = "conflito@skydex.com",
                password = passwordEncoder.encode("qualquer-senha"),
                dataEntrada = LocalDateTime.now()
            )
        )

        // 2. Tenta registrar outro com o mesmo email
        val registerRequest = RegisterRequest(
            nome = "Novo Tentando Clonar",
            email = "conflito@skydex.com",
            password = "outra-senha"
        )

        mockMvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Usuário com esse email já cadastrado"))
    }

    // --- TESTES DE LOGIN ---

    @Test
    fun `deve realizar login com sucesso e retornar status 200`() {
        // 1. Cria um usuário no banco com a senha já criptografada pelo BCrypt
        val senhaPlana = "minha-senha-secreta"
        userRepository.save(
            User(
                id = UUID.randomUUID(),
                nome = "SkyDex Admin",
                email = "admin@skydex.com",
                password = passwordEncoder.encode(senhaPlana),
                dataEntrada = LocalDateTime.now()
            )
        )

        // 2. Simula o login vindo do aplicativo com a senha pura
        val loginRequest = LoginRequest(
            email = "admin@skydex.com",
            password = senhaPlana
        )

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mensagem").value("Login realizado com sucesso!"))
            .andExpect { jsonPath("$.tokenGerado").exists() }
    }

    @Test
    fun `deve retornar erro 401 ao tentar logar com senha incorreta`() {
        userRepository.save(
            User(
                id = UUID.randomUUID(),
                nome = "SkyDex Admin",
                email = "admin@skydex.com",
                password = passwordEncoder.encode("senha-certa"),
                dataEntrada = LocalDateTime.now()
            )
        )

        val loginRequest = LoginRequest(
            email = "admin@skydex.com",
            password = "senha-errada"
        )

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("email ou senha inválidos"))
    }

    @Test
    fun `deve retornar erro 400 ao tentar logar com email nao existente`() {
        val loginRequest = LoginRequest(
            email = "fantasma@skydex.com",
            password = "senha-qualquer"
        )

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Usuário não encontrado"))
    }
}