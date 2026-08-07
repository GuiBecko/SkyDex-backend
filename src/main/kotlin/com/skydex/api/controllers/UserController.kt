package com.skydex.api.controllers

import com.skydex.api.dto.UpdateProfileRequest
import com.skydex.api.dto.UserResponse
import com.skydex.api.errors.ConflictException
import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(
    private val users: UserRepository,
    private val events: WeatherEventRepository
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal currentUser: User): UserResponse =
        UserResponse.from(currentUser)

    @PutMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal currentUser: User,
        @Valid @RequestBody request: UpdateProfileRequest
    ): UserResponse {
        val existing = users.findByEmail(request.email)
        if (existing != null && existing.id != currentUser.id) {
            throw ConflictException("Email already registered")
        }
        currentUser.name = request.name
        currentUser.email = request.email
        return UserResponse.from(users.save(currentUser))
    }

    @Transactional
    @DeleteMapping("/me")
    fun deleteMe(@AuthenticationPrincipal currentUser: User): ResponseEntity<Void> {
        events.deleteAll(events.findByUserIdOrderByCapturedAtDesc(currentUser.id!!))
        users.delete(currentUser)
        return ResponseEntity.noContent().build()
    }
}
