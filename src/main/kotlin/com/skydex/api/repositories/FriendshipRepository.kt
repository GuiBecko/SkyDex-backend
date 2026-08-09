package com.skydex.api.repositories

import com.skydex.api.models.Friendship
import com.skydex.api.models.FriendshipStatus
import org.springframework.data.jpa.repository.JpaRepository
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
}
