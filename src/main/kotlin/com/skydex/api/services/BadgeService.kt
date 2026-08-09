package com.skydex.api.services

import com.skydex.api.domain.Achievement
import com.skydex.api.domain.AchievementContext
import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.models.User
import com.skydex.api.models.UserBadge
import com.skydex.api.repositories.UserBadgeRepository
import com.skydex.api.repositories.WeatherEventRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class BadgeService(
    private val badges: UserBadgeRepository,
    private val events: WeatherEventRepository,
    private val friendships: FriendshipService
) {

    /**
     * Awards every achievement the user now qualifies for and returns their full badge list.
     * Idempotent: already-held badges are skipped, so this is safe to call after each capture
     * and on every profile read. Badges are never revoked — they record that something
     * happened, not that it is still true.
     */
    fun syncFor(user: User): List<UserBadge> {
        val userId = user.id!!
        val alreadyHeld = badges.findByUserId(userId)
        val heldAchievements = alreadyHeld.map { it.achievement }.toSet()

        val context = contextFor(userId)
        val newlyEarned = Achievement.entries
            .filter { it !in heldAchievements && it.isEarnedBy(context) }
            .map { UserBadge(id = null, userId = userId, achievement = it) }

        if (newlyEarned.isEmpty()) return alreadyHeld

        return try {
            alreadyHeld + badges.saveAll(newlyEarned)
        } catch (e: DataIntegrityViolationException) {
            // Another request for the same user crossed the same threshold at the same moment and
            // inserted first. The unique constraint on (user_id, achievement) is what stops the
            // duplicate row, but it stops it by THROWING — and this call sits after the capture
            // has already been committed, so letting it propagate would answer a successful
            // capture with a 500. Re-read instead: the badge exists, which is the outcome we
            // wanted, and the loser of the race simply reports the winner's row.
            //
            // This is not a swallowed error. It is the standard idempotent-insert recovery, and
            // it is narrow: only this exception, only after a constraint that exists precisely to
            // make the duplicate impossible. Anything else still propagates.
            badges.findByUserId(userId)
        }
    }

    fun badgesFor(userId: UUID): List<UserBadge> = badges.findByUserId(userId)

    private fun contextFor(userId: UUID): AchievementContext {
        val confirmed = events.findByUserIdAndValidationStatus(userId, ValidationStatus.CONFIRMED)
        val speciesCounts = confirmed.groupingBy { it.phenomenon }.eachCount()

        return AchievementContext(
            confirmedCaptures = confirmed.size,
            unconfirmedCaptures = events
                .countByUserIdAndValidationStatus(userId, ValidationStatus.UNCONFIRMED)
                .toInt(),
            distinctSpecies = speciesCounts.size,
            totalSpecies = Phenomenon.entries.size,
            speciesCounts = speciesCounts,
            friends = friendships.friendIds(userId).size
        )
    }
}
