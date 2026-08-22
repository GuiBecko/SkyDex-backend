package com.skydex.api.services

import com.skydex.api.dto.FriendRequestResponse
import com.skydex.api.dto.FriendResponse
import com.skydex.api.errors.BadRequestException
import com.skydex.api.errors.ConflictException
import com.skydex.api.errors.ForbiddenException
import com.skydex.api.errors.NotFoundException
import com.skydex.api.models.Friendship
import com.skydex.api.models.FriendshipStatus
import com.skydex.api.models.User
import com.skydex.api.repositories.FriendshipRepository
import com.skydex.api.repositories.UserRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FriendshipService(
    private val friendships: FriendshipRepository,
    private val users: UserRepository
) {

    /**
     * *The duplicate check below is not atomic.* This reads both directions and then inserts, and
     * the unique constraint on `friendships` only covers `(requester_id, addressee_id)`, so it
     * cannot stop the mirrored pair. If Alice and Bob request each other at the same instant, both
     * reads miss, both inserts succeed, and the pair ends up with two ACCEPTED-able rows —
     * [friends] and [friendIds] would then list the same person twice. This is accepted for the
     * MVP: the symptom is guarded (see their `distinctBy`/`distinct`) rather than the race itself.
     * No locking and no normalised `(low_id, high_id)` column ordering — the window is two humans
     * acting within milliseconds of each other, the damage is a duplicated list row, and the feed
     * is unaffected because `IN (:ids)` does not care about repeats.
     */
    fun request(requester: User, email: String): FriendRequestResponse {
        val addressee = users.findByEmail(email.trim())
            ?: throw NotFoundException("No user with that email")

        if (addressee.id == requester.id) throw BadRequestException("You cannot add yourself")

        val existing = friendships.findByRequesterIdAndAddresseeId(requester.id!!, addressee.id!!)
            ?: friendships.findByRequesterIdAndAddresseeId(addressee.id!!, requester.id!!)
        if (existing != null) {
            throw ConflictException("You already have a pending or accepted request with this user")
        }

        val saved = friendships.save(
            Friendship(
                id = null,
                requesterId = requester.id!!,
                addresseeId = addressee.id!!,
                status = FriendshipStatus.PENDING
            )
        )
        return toRequestResponse(saved, requester)
    }

    fun incoming(user: User): List<FriendRequestResponse> =
        friendships.findByAddresseeIdAndStatus(user.id!!, FriendshipStatus.PENDING)
            .mapNotNull { friendship ->
                val requester = users.findById(friendship.requesterId).orElse(null)
                requester?.let { toRequestResponse(friendship, it) }
            }

    /**
     * How many requests are waiting for [user] to answer — the number behind the invite badge.
     *
     * Deliberately not `incoming(user).size`: [incoming] resolves every requester through
     * `UserRepository` to build a response the badge throws away, and it is called on every
     * navigation the bottom bar survives. This is one `COUNT`.
     *
     * It counts rows, so it can exceed the number of distinct people if the mirrored-request race
     * in [request]'s KDoc ever fires. A badge reading 2 for one duplicated invite is a cosmetic
     * miss on an accepted race, and the list the user then opens is deduplicated.
     */
    fun pendingCount(user: User): Long =
        friendships.countByAddresseeIdAndStatus(user.id!!, FriendshipStatus.PENDING)

    fun accept(user: User, requestId: UUID): FriendResponse {
        val friendship = friendships.findById(requestId).orElseThrow {
            NotFoundException("Friend request not found")
        }
        if (friendship.addresseeId != user.id) {
            throw ForbiddenException("This request was not sent to you")
        }

        friendship.status = FriendshipStatus.ACCEPTED
        val saved = friendships.save(friendship)

        val requester = users.findById(saved.requesterId).orElseThrow {
            NotFoundException("Friend request not found")
        }
        return FriendResponse(
            friendshipId = saved.id!!,
            userId = requester.id!!,
            name = requester.name,
            email = requester.email,
            friendsSince = saved.createdAt
        )
    }

    /**
     * Deletes the row regardless of its status. This is both "decline" (the addressee refuses a
     * pending request) and "unfriend" (either party removes an accepted one) — the same endpoint
     * serves both, by design, for either party.
     *
     * The unfriend half went unreachable from the app until `FriendResponse.friendshipId` existed:
     * the friends list carried only the other user's id, so nothing on the client had a value to
     * put in this route. If that field is ever dropped, this branch goes dark again.
     */
    fun decline(user: User, requestId: UUID) {
        val friendship = friendships.findById(requestId).orElseThrow {
            NotFoundException("Friend request not found")
        }
        if (friendship.addresseeId != user.id && friendship.requesterId != user.id) {
            throw ForbiddenException("This request is not yours")
        }
        friendships.delete(friendship)
    }

    /** See [request]'s KDoc for why this is `distinctBy`, not just a map. */
    fun friends(user: User): List<FriendResponse> =
        friendships.findAllByUserAndStatus(user.id!!, FriendshipStatus.ACCEPTED)
            .mapNotNull { friendship ->
                val otherId = otherSide(friendship, user.id!!)
                users.findById(otherId).orElse(null)?.let {
                    FriendResponse(
                        friendshipId = friendship.id!!,
                        userId = it.id!!,
                        name = it.name,
                        email = it.email,
                        friendsSince = friendship.createdAt
                    )
                }
            }
            .distinctBy { it.userId }

    /**
     * Ids of everyone [userId] is actually friends with. Used to scope the feed (Task 16). See
     * [request]'s KDoc for why this is `.distinct()`.
     */
    fun friendIds(userId: UUID): List<UUID> =
        friendships.findAllByUserAndStatus(userId, FriendshipStatus.ACCEPTED)
            .map { otherSide(it, userId) }
            .distinct()

    private fun otherSide(friendship: Friendship, userId: UUID): UUID =
        if (friendship.requesterId == userId) friendship.addresseeId else friendship.requesterId

    private fun toRequestResponse(friendship: Friendship, requester: User) = FriendRequestResponse(
        id = friendship.id!!,
        requesterId = requester.id!!,
        requesterName = requester.name,
        requesterEmail = requester.email,
        createdAt = friendship.createdAt
    )
}
