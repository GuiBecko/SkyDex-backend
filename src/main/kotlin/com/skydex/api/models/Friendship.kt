package com.skydex.api.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

enum class FriendshipStatus { PENDING, ACCEPTED }

/**
 * One row per relationship, stored in the direction it was requested. Both users see the
 * friendship once it is ACCEPTED — see FriendshipService.friends.
 */
@Entity
@Table(
    name = "friendships",
    uniqueConstraints = [UniqueConstraint(columnNames = ["requester_id", "addressee_id"])]
)
class Friendship(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(name = "requester_id", nullable = false)
    var requesterId: UUID,

    @Column(name = "addressee_id", nullable = false)
    var addresseeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: FriendshipStatus = FriendshipStatus.PENDING,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
