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
 * How many pending requests are waiting for the caller to answer.
 *
 * A whole endpoint for one number, rather than letting the client count `GET /api/friends/requests`,
 * because the caller is the *badge*: every screen with the bottom bar asks for this on navigation,
 * and it must not drag a list of names and e-mails along to render a dot. An object rather than a
 * bare `Long` so the field can gain siblings without breaking the client's parser.
 */
data class PendingRequestCountResponse(val count: Long)

/**
 * [friendsSince] is the moment the friend *request* was sent, not the moment it was accepted —
 * there is no `accepted_at` column, and the MVP does not need one. The field name suggests
 * otherwise, so this is called out explicitly rather than discovered later: do not add the
 * column, and do not rename the field, since Task 17's UI binds to this name.
 *
 * [friendshipId] is the id of the `friendships` **row**, not of either user, and it is what
 * `DELETE /api/friends/requests/{id}` takes. Without it the client held only [userId] and so could
 * list friends but never remove one — the endpoint accepted the operation from either party the
 * whole time and no caller could address it.
 */
data class FriendResponse(
    val friendshipId: UUID,
    val userId: UUID,
    val name: String,
    val email: String,
    val friendsSince: Instant
)
