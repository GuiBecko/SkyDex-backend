package com.skydex.api.controllers

import com.skydex.api.models.EventoMetereologico
import com.skydex.api.repositories.EventoRepository
import com.skydex.api.repositories.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.UUID
import kotlin.collections.mapOf

data class EventoRequest (
    @field:NotBlank(message = "Titulo nao deve ser vazio.")
    var titulo: String,

    @field:NotBlank(message = "Descrição nao deve ser vazio.")
    var descricao: String,

    @field:NotBlank(message = "Url da foto nao deve ser vazio.")
    var urlFoto: String
)


@RestController
@RequestMapping("/api/eventos")
class EventoController(private val repo: EventoRepository, private val userRepository: UserRepository) {

    @PostMapping
    fun RegistrarEvento(@Valid @RequestBody evento: EventoRequest): ResponseEntity<out Any?> {
        val emailDoUsuarioLogado = SecurityContextHolder.getContext().authentication.name
        val usuario = userRepository.findByEmail(emailDoUsuarioLogado)

        if(usuario == null) {
            return ResponseEntity.status(401).body(mapOf("error" to "Usuário não encontrado ou token inválido"))
        }

        val eventoNovo = EventoMetereologico(
            id = UUID.randomUUID(),
            titulo = evento.titulo,
            descricao = evento.descricao,
            urlFoto = evento.urlFoto,
            dataHoraRegistro = LocalDateTime.now(),
            user_id = usuario.id
        )
        val eventoSalvo = repo.save(eventoNovo)

        return ResponseEntity.ok(eventoSalvo)
    }

    @GetMapping
    fun MostrarEventos(): ResponseEntity< List<EventoMetereologico> > {
        val eventos = repo.findAll()
        return ResponseEntity.ok(eventos)
    }

    @GetMapping("/{id}")
    fun buscarEventoPorId(@PathVariable id: UUID): ResponseEntity<EventoMetereologico> {
        val evento = repo.findById(id)

        if (evento.isPresent) {
            return ResponseEntity.ok(evento.get())
        }else {
            return ResponseEntity.notFound().build()
        }
    }

    @PutMapping("/{id}")
    fun updateEvento(@PathVariable id: UUID, @Valid @RequestBody evento: EventoRequest): ResponseEntity<EventoMetereologico> {
        var eventoBusca = repo.findById(id)

        if (eventoBusca.isPresent) {
            val eventoOriginal = eventoBusca.get()

            eventoOriginal.titulo = evento.titulo
            eventoOriginal.descricao = evento.descricao
            eventoOriginal.urlFoto = evento.urlFoto

            val eventoSalvo = repo.save(eventoOriginal)
            return ResponseEntity.ok(eventoSalvo)
        } else {
            return ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping
    fun deleteEventos(): ResponseEntity<String> {
        val eventos = repo.deleteAll()
        return ResponseEntity.ok("Deletado com sucesso!")
    }

    @DeleteMapping("/{id}")
    fun deleteEvento(@PathVariable id: UUID): ResponseEntity<String> {
        val evento = repo.findById(id)

        return if (evento.isPresent) {
            repo.delete(evento.get())
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}