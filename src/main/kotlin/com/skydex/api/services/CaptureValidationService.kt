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

/**
 * Decides whether a capture earns XP: the claim has to match the weather record for that place and
 * time, and the place has to be one the caller could plausibly be.
 *
 * The `locationIsMock` half is worth stating plainly, because it is weaker than it sounds. It is
 * the CLIENT's own report that Android flagged the fix as coming from a mock provider, so it stops
 * a casual mock-GPS app installed alongside our unmodified client and nothing more — a modified
 * client simply sends `false`. It earns its place because casual mock-GPS is what most cheating
 * actually looks like, and it costs one boolean. It becomes trustworthy only if device attestation
 * (Play Integrity) is ever added, at which point the flag would be worth acting on more harshly
 * than "no XP". Do not write code elsewhere that treats it as proof of anything.
 */
@Service
class CaptureValidationService(private val openMeteoClient: OpenMeteoClient) {

    /**
     * Checks a capture claim against Open-Meteo's hourly record for that place and time, and the
     * claimed position against where the caller could plausibly have got to.
     *
     * Never throws: an unreachable upstream, a capture outside the forecast window, an implausible
     * position or a mocked one all come back UNCONFIRMED with zero XP. Nothing here rejects a
     * capture — the user keeps the row and the photo, they just earn nothing for it — because the
     * same status also means "our upstream was down", and losing a real capture to that would be
     * worse than paying nothing for a fake one.
     *
     * The two position checks run FIRST, before any network call, so a capture that cannot be
     * confirmed on position alone costs no Open-Meteo request. They return a null
     * `observedWeatherCode` for the same reason: nothing was observed, because nothing was asked.
     *
     * [previous] is read without synchronisation, so the travel verdict reached here is provisional
     * and this is NOT the place that enforces it. `CaptureCommitService.commit` re-checks travel
     * against the trail re-read under a row lock, and can downgrade a CONFIRMED result on the way
     * out. Doing it here as well is an optimisation, not a duplicate: it is what keeps the
     * overwhelmingly common implausible case from paying for an Open-Meteo call first.
     */
    fun validate(
        claimed: Phenomenon,
        latitude: Double,
        longitude: Double,
        capturedAt: Instant,
        previous: LastKnownPosition?,
        locationIsMock: Boolean
    ): ValidationResult {
        if (locationIsMock) return unconfirmed(null)
        if (!TravelPlausibility.isReachable(previous, latitude, longitude, capturedAt)) {
            return unconfirmed(null)
        }

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
