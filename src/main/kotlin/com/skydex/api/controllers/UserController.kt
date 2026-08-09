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
        // A targeted UPDATE of exactly the two profile columns, NOT `currentUser.name = ...;
        // users.save(currentUser)`. `currentUser` is the entity SecurityFilter loaded at the start
        // of this request, so saving it writes every column back from that snapshot — including the
        // movement trail added in Task 12c. A profile edit racing a capture would restore an older
        // `last_capture_at`, and an older timestamp means a bigger reachable radius: renaming
        // yourself would have been a way to buy travel budget.
        users.updateProfile(currentUser.id!!, request.name, request.email)
        // Built from the request rather than by mutating and re-reading `currentUser`, for the same
        // reason. Under `open-in-view` (on by default in dev) that entity is managed, so assigning
        // to its fields here would make it dirty and Hibernate would flush the very full-entity
        // write this method just avoided.
        return UserResponse(
            id = currentUser.id!!,
            name = request.name,
            email = request.email,
            joinedAt = currentUser.joinedAt
        )
    }

    @Transactional
    @DeleteMapping("/me")
    fun deleteMe(@AuthenticationPrincipal currentUser: User): ResponseEntity<Void> {
        events.deleteAll(events.findByUserIdOrderByCapturedAtDesc(currentUser.id!!))
        users.delete(currentUser)
        return ResponseEntity.noContent().build()
    }
}
