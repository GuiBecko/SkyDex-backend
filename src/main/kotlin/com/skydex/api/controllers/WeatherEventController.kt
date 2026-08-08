package com.skydex.api.controllers

import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.dto.WeatherEventResponse
import com.skydex.api.errors.ForbiddenException
import com.skydex.api.errors.NotFoundException
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
                // Server-stamped, never client-supplied: see this task's opening note.
                capturedAt = Instant.now(),
                latitude = request.latitude,
                longitude = request.longitude,
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
    fun getById(@PathVariable id: UUID): WeatherEventResponse {
        val event = events.findById(id).orElseThrow { NotFoundException("Capture not found") }
        // Author comes from the event, never from the caller: this endpoint has no ownership
        // restriction, so the two genuinely differ here. The test below pins that.
        val author = users.findById(event.userId).orElseThrow { NotFoundException("Capture not found") }
        return WeatherEventResponse.from(event, author)
    }

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal currentUser: User,
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateWeatherEventRequest
    ): WeatherEventResponse {
        val event = events.findById(id).orElseThrow { NotFoundException("Capture not found") }
        if (event.userId != currentUser.id) {
            throw ForbiddenException("You can only modify your own captures")
        }
        event.title = request.title
        event.description = request.description
        event.photoUrl = request.photoUrl
        event.latitude = request.latitude
        event.longitude = request.longitude
        // capturedAt is deliberately NOT updated: editing a title must not move when the
        // capture happened, or Task 12 would revalidate an old photo against a new hour.
        // Safe to pass currentUser as the author ONLY because the guard above proves
        // currentUser.id == event.userId. If that guard is ever relaxed — a moderator edit, a
        // shared album — this must go back to looking the author up from event.userId, or the
        // response will attribute the capture to whoever edited it.
        return WeatherEventResponse.from(events.save(event), currentUser)
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal currentUser: User,
        @PathVariable id: UUID
    ): ResponseEntity<Void> {
        val event = events.findById(id).orElseThrow { NotFoundException("Capture not found") }
        if (event.userId != currentUser.id) {
            throw ForbiddenException("You can only modify your own captures")
        }
        events.delete(event)
        return ResponseEntity.noContent().build()
    }
}
