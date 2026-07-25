package com.skydex.api.controllers

import com.skydex.api.models.EventoMetereologico
import com.skydex.api.repositories.EventoRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/eventos")
class EventoController(private val repo: EventoRepository) {
    @PostMapping
    fun RegistrarEvento(@RequestBody evento: EventoMetereologico): ResponseEntity<EventoMetereologico> {
        val eventoSalvo = repo.save(evento)
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
    fun updateEvento(@PathVariable id: UUID, @RequestBody evento: EventoMetereologico): ResponseEntity<EventoMetereologico> {
        var eventoBusca = repo.findById(id)

        if (eventoBusca.isPresent) {
            val eventoOriginal = eventoBusca.get()

            eventoOriginal.titulo = evento.titulo
            eventoOriginal.descricao = evento.descricao
            eventoOriginal.urlFoto = evento.urlFoto
            eventoOriginal.dataHoraRegistro = evento.dataHoraRegistro

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