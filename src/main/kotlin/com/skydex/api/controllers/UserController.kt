package com.skydex.api.controllers

import com.skydex.api.dto.ProfileResponse
import com.skydex.api.dto.UpdateProfileRequest
import com.skydex.api.dto.UserResponse
import com.skydex.api.errors.ConflictException
import com.skydex.api.models.User
import com.skydex.api.repositories.FriendshipRepository
import com.skydex.api.repositories.UploadedPhotoRepository
import com.skydex.api.repositories.UserBadgeRepository
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import com.skydex.api.services.ProfileService
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
    private val events: WeatherEventRepository,
    private val profiles: ProfileService,
    // The remaining three tables that carry a user id. Injected for `deleteMe` only — see its
    // KDoc for why account deletion has to sweep them by hand.
    private val friendships: FriendshipRepository,
    private val badges: UserBadgeRepository,
    private val photos: UploadedPhotoRepository
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal currentUser: User): UserResponse =
        UserResponse.from(currentUser)

    @GetMapping("/me/profile")
    fun myProfile(@AuthenticationPrincipal currentUser: User): ProfileResponse =
        profiles.forUser(currentUser)

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

    /**
     * Deletes the account and every row in the schema that references it.
     *
     * **Four tables, and the list has to be maintained by hand.** `weather_events`,
     * `uploaded_photos`, `friendships` and `user_badges` all reference `users` by a plain UUID
     * column with no foreign key, so the database neither refuses this delete nor cascades it.
     * Anything left behind points at a user id that no longer resolves, and only this method
     * prevents that. Add a table that carries a user id and you must add it here too.
     *
     * The friendship sweep covers **both** sides, and is the one whose omission was actually
     * visible to users rather than merely untidy: `FriendshipService.friendIds` counts rows, so a
     * dead account inflated its former friend's friend count and fed
     * `Achievement.WEATHER_NETWORK`, while `friends()` resolves each id and quietly drops the one
     * that no longer exists — leaving the count and the list contradicting each other.
     *
     * What this does NOT do is delete the photo **files** from disk; it deletes only their rows.
     * There is no delete-photo endpoint and no sweep yet, and orphaned-JPEG cleanup is a known,
     * separate, server-side backlog item.
     */
    @Transactional
    @DeleteMapping("/me")
    fun deleteMe(@AuthenticationPrincipal currentUser: User): ResponseEntity<Void> {
        val userId = currentUser.id!!
        events.deleteAll(events.findByUserIdOrderByCapturedAtDesc(userId))
        friendships.deleteAllInvolving(userId)
        badges.deleteByUserId(userId)
        photos.deleteByUploaderId(userId)
        users.delete(currentUser)
        return ResponseEntity.noContent().build()
    }
}
