package com.skydex.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.controllers.UserRequest // Confirme se o import do seu DTO está correto
import com.skydex.api.models.EventoMetereologico
import com.skydex.api.models.User
import com.skydex.api.repositories.EventoRepository
import com.skydex.api.repositories.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest { // MUDANÇA: Corrigido o nome da classe para UserControllerTest

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var repository: UserRepository

    @Autowired
    private lateinit var eventoRepository: EventoRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var usuarioTesteId: UUID
    private val emailTeste = "skydex@gmail.com"

    // Limpa o banco antes de cada teste para evitar conflitos de email
    @BeforeEach
    fun setup() {
        repository.deleteAll()
        val user = User(
            id = UUID.randomUUID(),
            nome = "Piloto de Testes",
            email = "skydex@gmail.com",
            password = "senha-criptografada-fake",
            dataEntrada = LocalDateTime.now()
        )

        // CAPTURE O RETORNO DO SAVE: O Hibernate define o ID oficial aqui
        val userSalvo = repository.save(user)
        usuarioTesteId = userSalvo.id!!
    }

    @Test
    @WithMockUser // MUDANÇA: Simulando usuário logado
    fun `deve criar um novo usuario e retornar 200 com ID gerado`() {
        // MUDANÇA: Agora usamos o DTO (UserRequest) em vez da Entidade
        val novoUserRequest = UserRequest(
            username = "Guilherme",
            email = "email@fake.com.br",
            password = "senha-segura" // O Controller vai criptografar isso
        )
        val jsonEnvio = objectMapper.writeValueAsString(novoUserRequest)

        mockMvc.perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.nome").value("Guilherme"))
            .andExpect(jsonPath("$.email").value("email@fake.com.br"))
    }

    @Test
    @WithMockUser
    fun `deve listar todos os usuarios e retornar 200`() {
        repository.deleteAll()

        val users = listOf(
            User(id = UUID.randomUUID(), nome = "Guilherme", email = "gui@teste.com", password = "123", dataEntrada = LocalDateTime.now()),
            User(id = UUID.randomUUID(), nome = "Camila", email = "camila@teste.com", password = "123", dataEntrada = LocalDateTime.now())
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
    @WithMockUser
    fun `deve buscar um novo usuario e retornar 200`() {
        val userNovo = User(id = UUID.randomUUID(), nome = "Guilherme", email = "busca@teste.com", password = "123", dataEntrada = LocalDateTime.now())
        val userSalvo = repository.save(userNovo)
        val idGerado = userSalvo.id!!

        mockMvc.perform(
            get("/api/users/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(idGerado.toString()))
            .andExpect(jsonPath("$.nome").value("Guilherme"))
    }

    @Test
    @WithMockUser
    fun `deve atualizar um usuario existente e retornar 200`() {
        val userAntigo = User(id = UUID.randomUUID(), nome = "Nome Antigo", email = "antigo@teste.com", password = "123", dataEntrada = LocalDateTime.now())
        val userSalvo = repository.save(userAntigo)
        val idGerado = userSalvo.id!!

        // MUDANÇA: Enviando o UserRequest em vez da entidade completa
        val dadosNovos = UserRequest(
            username = "Guilherme",
            email = "novo@fake.com.br",
            password = "senha-nova"
        )
        val jsonEnvio = objectMapper.writeValueAsString(dadosNovos)

        mockMvc.perform(
            put("/api/users/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nome").value("Guilherme"))
            .andExpect(jsonPath("$.email").value("novo@fake.com.br"))
    }

    @Test
    @WithMockUser
    fun `deve eliminar um usuario existente e retornar 204 No Content`() {
        val user = User(id = UUID.randomUUID(), nome = "user para apagar", email = "delete@teste.com", password = "123", dataEntrada = LocalDateTime.now())
        val userSalvo = repository.save(user)
        val idGerado = userSalvo.id!!

        mockMvc.perform(
            delete("/api/users/{id}", idGerado)
        )
            .andExpect(status().isNoContent)

        val aindaExiste = repository.existsById(idGerado)
        assert(!aindaExiste)
    }

    @Test
    @WithMockUser
    fun `deve retornar erro 404 Not Found ao procurar um ID que nao existe`() {
        val idFalso = UUID.randomUUID()

        mockMvc.perform(
            get("/api/users/{id}", idFalso)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @WithMockUser
    fun `deve listar eventos do usuario e retornar 200`() {

        val eventos = listOf(
            EventoMetereologico(
                id = UUID.randomUUID(),
                titulo = "Chuva Forte",
                descricao = "Temporal na região",
                urlFoto = "http://foto1.jpg",
                dataHoraRegistro = LocalDateTime.now(),
                userId = usuarioTesteId
            ),
            EventoMetereologico(
                id = UUID.randomUUID(),
                titulo = "Granizo",
                descricao = "Pedras de gelo pequenas",
                urlFoto = "http://foto2.jpg",
                dataHoraRegistro = LocalDateTime.now(),
                userId = usuarioTesteId
            )
        )

        for (evento in eventos) {
            eventoRepository.save(evento)
        }

        mockMvc.perform(
            get("/api/users/{id}/eventos", usuarioTesteId)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].titulo").value("Chuva Forte"))
            .andExpect(jsonPath("$[1].titulo").value("Granizo"))
    }
}