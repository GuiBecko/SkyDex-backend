package com.skydex.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class UserController(@Autowired private val passwordEncoder: PasswordEncoder) {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var repository: UserRepository

    @Test
    fun `deve criar um novo usuario e retornar 200 com ID gerado`() {
        repository.deleteAll()

        val novoUser = User(
            nome = "Guilherme",
            email = "email@fake.com.br",
            password = passwordEncoder.encode("123456"),
            dataEntrada = LocalDateTime.now()
        )
        val jsonEnvio = objectMapper.writeValueAsString(novoUser)

        mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON).content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.nome").value("Guilherme"))
            .andExpect(jsonPath("$.email").value("email@fake.com.br"))
    }

    @Test
    fun `deve listar todos os usuarios e retornar 200`() {

        repository.deleteAll()

        val users = listOf(
            User(nome = "Guilherme", dataEntrada = LocalDateTime.now()),
            User(nome = "Camila", dataEntrada = LocalDateTime.of(2026, 2, 3, 15, 32))
        )

        repository.saveAll(users)

        mockMvc.perform(
            get("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].nome").value("Guilherme"))
            .andExpect(jsonPath("$[1].nome").value("Camila"))
    }

    @Test
    fun `deve buscar um novo usuario e retornar 200`() {
        repository.deleteAll()

        val userNovo = User(nome = "Guilherme", dataEntrada = LocalDateTime.now())
        val userSalvo = repository.save(userNovo)
        val idGerado = userSalvo.id!!

        mockMvc.perform(
            get("/api/users/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect {jsonPath("$.id").value(idGerado.toString())}
            .andExpect{jsonPath("$.nome").value("Guilherme")}
    }

    @Test
    fun `deve atualizar um usuario existente e retornar 200`() {

        repository.deleteAll()
        val userAntigo = User(nome = "nome Antigo", dataEntrada = LocalDateTime.of(2026, 2, 3, 15, 32))
        val userSalvo = repository.save(userAntigo)
        val idGerado = userAntigo.id!!


        val dataFixa = LocalDateTime.of(2026, 12, 31, 12, 0, 1)
        val dadosNovos = User(nome = "Guilherme", dataEntrada = dataFixa)
        val jsonEnvio = objectMapper.writeValueAsString(dadosNovos)


        mockMvc.perform(
            put("/api/users/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nome").value("Guilherme"))
            .andExpect(jsonPath("$.dataEntrada").value(dataFixa.toString()))
    }

    @Test
    fun `deve eliminar um evento existente e retornar 204 No Content`() {
        // 1. PREPARAÇÃO
        repository.deleteAll()
        val user = User(nome = "user para apagar", dataEntrada = LocalDateTime.of( 2026, 2, 3, 15, 32 ))
        val userSalvo = repository.save(user)
        val idGerado = userSalvo.id!!

        // 2. AÇÃO (DELETE)
        mockMvc.perform(
            delete("/api/users/{id}", idGerado)
        )
            .andExpect(status().isNoContent) // O status correto para um Delete de sucesso é 204

        // 3. VERIFICAÇÃO EXTRA: O banco de dados deve estar vazio agora!
        val aindaExiste = repository.existsById(idGerado)
        assert(!aindaExiste) // O Kotlin confirma que "aindaExiste" é falso
    }

    @Test
    fun `deve retornar erro 404 Not Found ao procurar um ID que nao existe`() {
        // 1. PREPARAÇÃO: Geramos um UUID aleatório que com certeza não está na base de dados
        val idFalso = UUID.randomUUID()

        // 2. AÇÃO E VERIFICAÇÃO
        mockMvc.perform(
            get("/api/users/{id}", idFalso)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound) // Verifica se o Controller caiu no bloco "else" e retornou 404
    }

}