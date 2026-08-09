package com.skydex.api.models

import com.skydex.api.domain.Achievement
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

/**
 * One row per badge a user has unlocked — the many side of user-to-badges. The unique
 * constraint is what makes BadgeService.syncFor safe to call on every capture and every
 * profile read: a second award simply cannot be inserted.
 */
@Entity
@Table(
    name = "user_badges",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "achievement"])]
)
class UserBadge(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: java.util.UUID? = null,

    @Column(name = "user_id", nullable = false)
    var userId: java.util.UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    var achievement: Achievement,

    @Column(name = "unlocked_at", nullable = false)
    var unlockedAt: Instant = Instant.now()
)
