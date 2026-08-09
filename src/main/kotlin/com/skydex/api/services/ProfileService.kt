package com.skydex.api.services

import com.skydex.api.domain.Achievement
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.dto.BadgeResponse
import com.skydex.api.dto.ProfileResponse
import com.skydex.api.dto.UserResponse
import com.skydex.api.models.User
import com.skydex.api.repositories.WeatherEventRepository
import org.springframework.stereotype.Service

@Service
class ProfileService(
    private val events: WeatherEventRepository,
    private val friendships: FriendshipService,
    private val skyDex: SkyDexService,
    private val badges: BadgeService
) {

    fun forUser(user: User): ProfileResponse {
        val userId = user.id!!

        // Award anything newly earned before reporting, so the profile is never stale.
        val held = badges.syncFor(user).associateBy { it.achievement }

        // Level, XP and species progress come from SkyDexService so the two screens can
        // never disagree about what level the user is.
        val collection = skyDex.forUser(userId)

        val badgeResponses = Achievement.entries.map { BadgeResponse.from(it, held[it]) }

        return ProfileResponse(
            user = UserResponse.from(user),
            level = collection.level,
            totalXp = collection.totalXp,
            xpToNextLevel = collection.xpToNextLevel,
            confirmedCaptures = events
                .countByUserIdAndValidationStatus(userId, ValidationStatus.CONFIRMED)
                .toInt(),
            totalCaptures = events.countByUserId(userId).toInt(),
            capturedSpecies = collection.capturedSpecies,
            totalSpecies = collection.totalSpecies,
            friends = friendships.friendIds(userId).size,
            unlockedBadges = badgeResponses.count { it.unlocked },
            totalBadges = badgeResponses.size,
            badges = badgeResponses
        )
    }
}
