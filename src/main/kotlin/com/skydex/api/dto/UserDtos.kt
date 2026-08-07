package com.skydex.api.dto

import com.skydex.api.models.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val name: String,
    val email: String,
    val joinedAt: Instant
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id!!,
            name = user.name,
            email = user.email,
            joinedAt = user.joinedAt
        )
    }
}

data class UpdateProfileRequest(
    @field:NotBlank(message = "Name is required")
    val name: String,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email format is invalid")
    val email: String
)
