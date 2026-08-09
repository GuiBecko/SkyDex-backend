package com.skydex.api.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class FriendRequestBody(
    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email format is invalid")
    val email: String
)

data class FriendRequestResponse(
    val id: UUID,
    val requesterId: UUID,
    val requesterName: String,
    val requesterEmail: String,
    val createdAt: Instant
)

/**
 * [friendsSince] is the moment the friend *request* was sent, not the moment it was accepted —
 * there is no `accepted_at` column, and the MVP does not need one. The field name suggests
 * otherwise, so this is called out explicitly rather than discovered later: do not add the
 * column, and do not rename the field, since Task 17's UI binds to this name.
 */
data class FriendResponse(
    val userId: UUID,
    val name: String,
    val email: String,
    val friendsSince: Instant
)
