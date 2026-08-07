package com.skydex.api.controllers

import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.dto.WeatherEventResponse
import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/events")
class WeatherEventController(
    private val events: WeatherEventRepository,
    private val users: UserRepository
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: User,
        @Valid @RequestBody request: CreateWeatherEventRequest
    ): ResponseEntity<WeatherEventResponse> {
        val saved = events.save(
            WeatherEvent(
                id = null,
                title = request.title,
                description = request.description,
                photoUrl = request.photoUrl,
                capturedAt = Instant.now(),
                userId = currentUser.id!!
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(WeatherEventResponse.from(saved, currentUser))
    }

    @GetMapping("/mine")
    fun listMine(@AuthenticationPrincipal currentUser: User): ResponseEntity<List<WeatherEventResponse>> {
        val mine = events.findByUserIdOrderByCapturedAtDesc(currentUser.id!!)
        return ResponseEntity.ok(mine.map { WeatherEventResponse.from(it, currentUser) })
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): ResponseEntity<WeatherEventResponse> {
        val event = events.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val author = users.findById(event.userId).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(WeatherEventResponse.from(event, author))
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateWeatherEventRequest
    ): ResponseEntity<WeatherEventResponse> {
        val event = events.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val author = users.findById(event.userId).orElse(null) ?: return ResponseEntity.notFound().build()
        event.title = request.title
        event.description = request.description
        event.photoUrl = request.photoUrl
        return ResponseEntity.ok(WeatherEventResponse.from(events.save(event), author))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        val event = events.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        events.delete(event)
        return ResponseEntity.noContent().build()
    }
}
