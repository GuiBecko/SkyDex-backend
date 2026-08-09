package com.skydex.api.services

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs

data class ValidationResult(
    val status: ValidationStatus,
    val observedWeatherCode: Int?,
    val xpAwarded: Int
)

@Service
class CaptureValidationService(private val openMeteoClient: OpenMeteoClient) {

    /**
     * Checks a capture claim against Open-Meteo's hourly record for that place and time.
     * Never throws: an unreachable upstream or a capture outside the forecast window comes
     * back UNCONFIRMED with zero XP, so a user never loses a photo to someone else's outage.
     */
    fun validate(
        claimed: Phenomenon,
        latitude: Double,
        longitude: Double,
        capturedAt: Instant
    ): ValidationResult {
        val hourly = openMeteoClient.fetchHourlyForecast(latitude, longitude)?.hourly
            ?: return unconfirmed(null)

        var nearestIndex = -1
        var nearestDistance = Long.MAX_VALUE

        val slots = minOf(hourly.time.size, hourly.weatherCode.size)
        for (i in 0 until slots) {
            val slotInstant = parseSlot(hourly.time[i]) ?: continue
            val distance = abs(Duration.between(slotInstant, capturedAt).toMillis())
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = i
            }
        }

        if (nearestIndex < 0 || nearestDistance > MAX_SKEW.toMillis()) {
            return unconfirmed(null)
        }

        val observedCode = hourly.weatherCode[nearestIndex] ?: return unconfirmed(null)
        val observed = Phenomenon.fromWeatherCode(observedCode)

        return if (observed == claimed) {
            ValidationResult(ValidationStatus.CONFIRMED, observedCode, claimed.rarity.xp)
        } else {
            ValidationResult(ValidationStatus.UNCONFIRMED, observedCode, 0)
        }
    }

    /** Open-Meteo returns "2026-08-07T14:00" with no offset; we requested timezone=UTC. */
    private fun parseSlot(raw: String): Instant? = try {
        LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun unconfirmed(observedCode: Int?) =
        ValidationResult(ValidationStatus.UNCONFIRMED, observedCode, 0)

    private companion object {
        /**
         * Covers hourly granularity plus slack for a truncated or gap-ridden upstream response —
         * NOT phone clock skew. There is no phone clock in this path: [capturedAt] is stamped by
         * the server (`WeatherEventController.create` reads `Instant.now()` once, before this is
         * ever called), so a client's clock can never influence it.
         */
        val MAX_SKEW: Duration = Duration.ofMinutes(90)
    }
}
