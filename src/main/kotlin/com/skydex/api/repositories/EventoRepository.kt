package com.skydex.api.repositories


import com.skydex.api.models.EventoMetereologico
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EventoRepository: JpaRepository<EventoMetereologico, UUID> {
    fun findByUserId(userId: UUID): List<EventoMetereologico>
}