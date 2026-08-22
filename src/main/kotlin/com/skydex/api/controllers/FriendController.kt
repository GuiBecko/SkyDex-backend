package com.skydex.api.controllers

import com.skydex.api.dto.FriendRequestBody
import com.skydex.api.dto.FriendRequestResponse
import com.skydex.api.dto.FriendResponse
import com.skydex.api.dto.PendingRequestCountResponse
import com.skydex.api.models.User
import com.skydex.api.services.FriendshipService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/friends")
class FriendController(private val friendships: FriendshipService) {

    @PostMapping("/requests")
    fun sendRequest(
        @AuthenticationPrincipal currentUser: User,
        @Valid @RequestBody body: FriendRequestBody
    ): ResponseEntity<FriendRequestResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(friendships.request(currentUser, body.email))

    @GetMapping("/requests")
    fun incomingRequests(@AuthenticationPrincipal currentUser: User): List<FriendRequestResponse> =
        friendships.incoming(currentUser)

    /**
     * The number behind the invite badge. Separate from [incomingRequests] because every screen
     * carrying the bottom bar asks for this on navigation and needs a number, not a guest list.
     */
    @GetMapping("/requests/count")
    fun pendingRequestCount(
        @AuthenticationPrincipal currentUser: User
    ): PendingRequestCountResponse =
        PendingRequestCountResponse(friendships.pendingCount(currentUser))

    @PostMapping("/requests/{id}/accept")
    fun acceptRequest(
        @AuthenticationPrincipal currentUser: User,
        @PathVariable id: UUID
    ): FriendResponse = friendships.accept(currentUser, id)

    /**
     * Declines a pending request **and** removes an accepted friendship — one route, because the
     * operation is the same delete and either party may perform it. See `FriendshipService.decline`.
     * The id comes from `FriendRequestResponse.id` in the first case and
     * `FriendResponse.friendshipId` in the second; both are the `friendships` row id.
     */
    @DeleteMapping("/requests/{id}")
    fun declineRequest(
        @AuthenticationPrincipal currentUser: User,
        @PathVariable id: UUID
    ): ResponseEntity<Void> {
        friendships.decline(currentUser, id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping
    fun myFriends(@AuthenticationPrincipal currentUser: User): List<FriendResponse> =
        friendships.friends(currentUser)
}
