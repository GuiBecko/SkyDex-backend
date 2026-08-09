package com.skydex.api.services

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.domain.levelFor
import com.skydex.api.domain.xpToNextLevel
import com.skydex.api.dto.SkyDexEntryResponse
import com.skydex.api.dto.SkyDexResponse
import com.skydex.api.repositories.WeatherEventRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SkyDexService(private val events: WeatherEventRepository) {

    /**
     * Builds the full species list every time, marking which ones the user has confirmed.
     * Nothing is denormalised onto the user row, so the collection can never drift out of
     * sync with the captures it is derived from.
     */
    fun forUser(userId: UUID): SkyDexResponse {
        val confirmed = events.findByUserIdAndValidationStatus(userId, ValidationStatus.CONFIRMED)
        val bySpecies = confirmed.groupBy { it.phenomenon }

        val entries = Phenomenon.entries.map { species ->
            val captures = bySpecies[species].orEmpty()
            SkyDexEntryResponse(
                phenomenon = species.name,
                displayName = species.displayName,
                rarity = species.rarity.name,
                xpPerCapture = species.rarity.xp,
                captured = captures.isNotEmpty(),
                captureCount = captures.size,
                firstCapturedAt = captures.minOfOrNull { it.capturedAt }
            )
        }

        val totalXp = events.totalXpForUser(userId)

        return SkyDexResponse(
            level = levelFor(totalXp),
            totalXp = totalXp,
            xpToNextLevel = xpToNextLevel(totalXp),
            capturedSpecies = entries.count { it.captured },
            totalSpecies = entries.size,
            entries = entries
        )
    }
}
