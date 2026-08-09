package com.skydex.api.repositories

import com.skydex.api.models.Friendship
import com.skydex.api.models.FriendshipStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface FriendshipRepository : JpaRepository<Friendship, UUID> {

    fun findByRequesterIdAndAddresseeId(requesterId: UUID, addresseeId: UUID): Friendship?

    fun findByAddresseeIdAndStatus(addresseeId: UUID, status: FriendshipStatus): List<Friendship>

    @Query(
        "SELECT f FROM Friendship f " +
            "WHERE f.status = :status AND (f.requesterId = :userId OR f.addresseeId = :userId)"
    )
    fun findAllByUserAndStatus(
        @Param("userId") userId: UUID,
        @Param("status") status: FriendshipStatus
    ): List<Friendship>

    /**
     * Removes every friendship [userId] is part of, on **either** side. Used only by account
     * deletion.
     *
     * Both sides, because a friendship is one row with the user in one of two columns depending on
     * who sent the invite. Sweeping only `requester_id` would leave every invite the deleted user
     * received — and those rows are not inert: [FriendshipService.friendIds] counts them, so the
     * surviving friend keeps a phantom friend, an inflated count on their profile, and progress
     * toward `Achievement.WEATHER_NETWORK` from an account that no longer exists.
     */
    @Modifying
    @Query("delete from Friendship f where f.requesterId = :userId or f.addresseeId = :userId")
    fun deleteAllInvolving(@Param("userId") userId: UUID)
}
