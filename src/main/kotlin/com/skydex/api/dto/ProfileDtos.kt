package com.skydex.api.dto

import com.skydex.api.domain.Achievement
import com.skydex.api.models.UserBadge
import java.time.Instant

data class BadgeResponse(
    val achievement: String,
    val displayName: String,
    val description: String,
    val unlocked: Boolean,
    val unlockedAt: Instant?
) {
    companion object {
        fun from(achievement: Achievement, badge: UserBadge?) = BadgeResponse(
            achievement = achievement.name,
            displayName = achievement.displayName,
            description = achievement.description,
            unlocked = badge != null,
            unlockedAt = badge?.unlockedAt
        )
    }
}

data class ProfileResponse(
    val user: UserResponse,
    val level: Int,
    val totalXp: Int,
    val xpToNextLevel: Int,
    val confirmedCaptures: Int,
    val totalCaptures: Int,
    val capturedSpecies: Int,
    val totalSpecies: Int,
    val friends: Int,
    val unlockedBadges: Int,
    val totalBadges: Int,
    val badges: List<BadgeResponse>
)
