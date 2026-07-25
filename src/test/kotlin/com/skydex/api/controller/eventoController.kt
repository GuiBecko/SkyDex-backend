package com.skydex.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.controllers.EventoRequest
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
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class EventoControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var repository: EventoRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var usuarioTesteId: UUID
    private val emailTeste = "teste@skydex.com"


    // Antes de cada teste, limpamos o banco e criamos um usuário para associar aos eventos
    @BeforeEach
    fun setup() {
        repository.deleteAll()
        userRepository.deleteAll()

        val user = User(
            id = null, //banco gera no .save()
            nome = "Piloto de Testes",
            email = emailTeste,
            password = "senha-criptografada-fake",
            dataEntrada = LocalDateTime.now()
        )
        userRepository.save(user)
        usuarioTesteId = user.id!!
    }

    @Test
    @WithMockUser(username = "teste@skydex.com")
    fun `deve registrar um novo evento e retornar 200 com ID gerado`() {

        val novoEventoRequest = EventoRequest(
            titulo = "Aurora Boreal",
            descricao = "Luzes verdes brilhantes no céu noturno.",
            urlFoto = "https://link-da-foto.com/aurora.jpg"
        )

        val jsonEnvio = objectMapper.writeValueAsString(novoEventoRequest)

        mockMvc.perform(
            post("/api/eventos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.titulo").value("Aurora Boreal"))
            .andExpect(jsonPath("$.user_id").value(usuarioTesteId.toString()))
    }

    @Test
    @WithMockUser
    fun `deve listar todos os eventos e retornar 200`() {
        val eventos = listOf(
            EventoMetereologico(
                id = UUID.randomUUID(), titulo = "Aurora Boreal", descricao = "luzes", urlFoto = "http://foto1.jpg",
                dataHoraRegistro = LocalDateTime.now(), user_id = usuarioTesteId
            ),
            EventoMetereologico(
                id = UUID.randomUUID(), titulo = "Eclipse", descricao = "eclipse lunar", urlFoto = "http://foto2.jpg",
                dataHoraRegistro = LocalDateTime.now(), user_id = usuarioTesteId
            )
        )

        repository.saveAll(eventos)

        mockMvc.perform(
            get("/api/eventos")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].titulo").value("Aurora Boreal"))
            .andExpect(jsonPath("$[1].titulo").value("Eclipse"))
    }

    @Test
    @WithMockUser
    fun `deve buscar um novo evento e retornar 200`() {
        val eventoNovo = EventoMetereologico(
            id = UUID.randomUUID(), titulo = "Aurora Boreal", descricao = "luzes", urlFoto = "http://foto1.jpg",
            dataHoraRegistro = LocalDateTime.now(), user_id = usuarioTesteId
        )
        val eventoSalvo = repository.save(eventoNovo)
        val idGerado = eventoSalvo.id!!

        mockMvc.perform(
            get("/api/eventos/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(idGerado.toString()))
            .andExpect(jsonPath("$.titulo").value("Aurora Boreal"))
    }

    @Test
    @WithMockUser
    fun `deve atualizar um evento existente e retornar 200`() {
        val eventoAntigo = EventoMetereologico(
            id = UUID.randomUUID(), titulo = "Titulo Antigo", descricao = "Descricao Antiga", urlFoto = "url1.jpg",
            dataHoraRegistro = LocalDateTime.now(), user_id = usuarioTesteId
        )
        val eventoSalvo = repository.save(eventoAntigo)
        val idGerado = eventoSalvo.id!!

        // Enviando DTO (Request) no PUT
        val dadosNovos = EventoRequest(
            titulo = "Tornado Confirmado",
            descricao = "Tornado tocou o solo",
            urlFoto = "url2.jpg"
        )
        val jsonEnvio = objectMapper.writeValueAsString(dadosNovos)

        mockMvc.perform(
            put("/api/eventos/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.titulo").value("Tornado Confirmado"))
            .andExpect(jsonPath("$.urlFoto").value("url2.jpg"))
    }

    @Test
    @WithMockUser
    fun `deve eliminar um evento existente e retornar 204 No Content`() {
        val evento = EventoMetereologico(
            id = UUID.randomUUID(), titulo = "Evento para apagar", descricao = "...", urlFoto = "url.jpg",
            dataHoraRegistro = LocalDateTime.now(), user_id = usuarioTesteId
        )
        val eventoSalvo = repository.save(evento)
        val idGerado = eventoSalvo.id!!

        mockMvc.perform(
            delete("/api/eventos/{id}", idGerado)
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
            get("/api/eventos/{id}", idFalso)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
    }
}