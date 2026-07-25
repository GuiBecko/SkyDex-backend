package com.skydex.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.models.EventoMetereologico
import com.skydex.api.repositories.EventoRepository
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.TypeReference.listOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class EventoController {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var repository: EventoRepository

    @Test
    fun `deve registrar um novo evento e retornar 200 com ID gerado`(){
        val novoEvento = EventoMetereologico(
            titulo = "Aurora Boreal",
            descricao = "Luzes verdes brilhantes no céu noturno.",
            urlFoto = "https://link-da-foto.com/aurora.jpg"
        )

        val jsonEnvio = objectMapper.writeValueAsString(novoEvento)

        mockMvc.perform(
            post("/api/eventos")
                .contentType(MediaType.APPLICATION_JSON).content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.titulo").value("Aurora Boreal"))
    }

    @Test
    fun `deve listar todos os eventos e retornar 200`() {

        repository.deleteAll()

        val eventos = listOf(
            EventoMetereologico(titulo = "Aurora Boreal", descricao = "luzes", urlFoto = "http://foto1.jpg"),
            EventoMetereologico(titulo = "Eclipse", descricao = "eclipse lunar", urlFoto = "http://foto2.jpg")
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
    fun `deve buscar um novo evento e retornar 200`() {
        repository.deleteAll()

        val eventoNovo = EventoMetereologico(titulo = "Aurora Boreal", descricao = "luzes", urlFoto = "http://foto1.jpg")
        val eventoSalvo = repository.save(eventoNovo)
        val idGerado = eventoSalvo.id!!

        mockMvc.perform(
            get("/api/eventos/{id}", idGerado)
            .contentType(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk)
        .andExpect {jsonPath("$.id").value(idGerado.toString())}
        .andExpect{jsonPath("$.titulo").value("Aurora Boreal")}
    }

    @Test
    fun `deve atualizar um evento existente e retornar 200`() {
        // 1. PREPARAÇÃO: Salva um evento antigo no banco
        repository.deleteAll()
        val eventoAntigo = EventoMetereologico(titulo = "Titulo Antigo", descricao = "Descricao Antiga", urlFoto = "url1.jpg")
        val eventoSalvo = repository.save(eventoAntigo)
        val idGerado = eventoSalvo.id!!

        // Cria os dados novos que o "telemóvel" vai enviar
        val dadosNovos = EventoMetereologico(titulo = "Tornado Confirmado", descricao = "Tornado tocou o solo", urlFoto = "url2.jpg")
        val jsonEnvio = objectMapper.writeValueAsString(dadosNovos)

        // 2. AÇÃO E VERIFICAÇÃO (PUT)
        mockMvc.perform(
            put("/api/eventos/{id}", idGerado)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonEnvio)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.titulo").value("Tornado Confirmado")) // Verifica se o título mudou
            .andExpect(jsonPath("$.urlFoto").value("url2.jpg")) // Verifica se a foto mudou
    }

    @Test
    fun `deve eliminar um evento existente e retornar 204 No Content`() {
        // 1. PREPARAÇÃO
        repository.deleteAll()
        val evento = EventoMetereologico(titulo = "Evento para apagar", descricao = "...", urlFoto = "url.jpg")
        val eventoSalvo = repository.save(evento)
        val idGerado = eventoSalvo.id!!

        // 2. AÇÃO (DELETE)
        mockMvc.perform(
            delete("/api/eventos/{id}", idGerado)
        )
            .andExpect(status().isNoContent) // O status correto para um Delete de sucesso é 204

        // 3. VERIFICAÇÃO EXTRA: O banco de dados deve estar vazio agora!
        val aindaExiste = repository.existsById(idGerado)
        assert(!aindaExiste) // O Kotlin confirma que "aindaExiste" é falso
    }

    @Test
    fun `deve retornar erro 404 Not Found ao procurar um ID que nao existe`() {
        // 1. PREPARAÇÃO: Geramos um UUID aleatório que com certeza não está na base de dados
        val idFalso = java.util.UUID.randomUUID()

        // 2. AÇÃO E VERIFICAÇÃO
        mockMvc.perform(
            get("/api/eventos/{id}", idFalso)
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound) // Verifica se o Controller caiu no bloco "else" e retornou 404
    }

}