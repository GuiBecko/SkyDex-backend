package com.skydex.api.controllers

import com.skydex.api.dto.ErrorResponse
import com.skydex.api.dto.UpdateProfileRequest
import com.skydex.api.dto.UserResponse
import com.skydex.api.dto.WeatherEventResponse
import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserController(
    private val users: UserRepository,
    private val events: WeatherEventRepository
) {

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): ResponseEntity<UserResponse> {
        val user = users.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(UserResponse.from(user))
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateProfileRequest
    ): ResponseEntity<UserResponse> {
        val user = users.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        user.name = request.name
        user.email = request.email
        return ResponseEntity.ok(UserResponse.from(users.save(user)))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        val user = users.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        users.delete(user)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/events")
    fun listEvents(@PathVariable id: UUID): ResponseEntity<Any> {
        val user = users.findById(id).orElse(null)
            ?: return ResponseEntity.status(404).body(ErrorResponse("User not found"))
        val list = events.findByUserIdOrderByCapturedAtDesc(id)
        return ResponseEntity.ok(list.map { WeatherEventResponse.from(it, user) })
    }
}
