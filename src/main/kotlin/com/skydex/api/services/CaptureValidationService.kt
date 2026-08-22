package com.skydex.api.services

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.UnconfirmedReason
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.errors.ServiceUnavailableException
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs

data class ValidationResult(
    val phenomenon: Phenomenon,
    val status: ValidationStatus,
    val observedWeatherCode: Int,
    val xpAwarded: Int,
    val unconfirmedReason: UnconfirmedReason?
)

/**
 * Decides what a capture *is* and whether it earns XP.
 *
 * ## What changed, and why it is not the same service it was
 *
 * This used to check a phenomenon the **user** claimed against Open-Meteo's record. It no longer
 * does, because the user no longer claims one: Open-Meteo's weather code IS the phenomenon. That
 * turns a check that could fail softly into a lookup that cannot fail at all — `weather_events
 * .phenomenon` is NOT NULL, so a missing answer is not an UNCONFIRMED capture, it is no capture.
 * Every path that used to return "unconfirmed, we could not tell" now throws
 * [ServiceUnavailableException] and the controller answers 503.
 *
 * That is cheap and it is the reason this ordering is safe: `PhotoProvenanceService.consume` runs
 * inside `CaptureCommitService.commit`, which is reached only *after* this returns. A capture that
 * dies here has spent nothing, so the client retries with the same photo for the remainder of its
 * thirty-minute `MAX_AGE`.
 *
 * ## The Open-Meteo call is no longer optional
 *
 * The previous version ran the position checks first, so an implausible capture cost no upstream
 * request. It cannot any more: even an implausible capture needs a phenomenon to be stored under.
 * The saving is gone and the ordering is inverted — weather first, verdict second.
 *
 * ## `locationIsMock` is still worth exactly what the client's honesty is worth
 *
 * It is the CLIENT's report that Android flagged the fix as coming from a mock provider, so it
 * stops a casual mock-GPS app installed alongside our unmodified client and nothing more. It earns
 * its place because casual mock-GPS is what most cheating actually looks like and it costs one
 * boolean. Do not write code elsewhere that treats it as proof of anything.
 *
 * [previous] is read without synchronisation, so the travel verdict reached here is provisional and
 * this is NOT the place that enforces it. `CaptureCommitService.commit` re-checks travel against the
 * trail re-read under a row lock and can downgrade a CONFIRMED result on the way out.
 */
@Service
class CaptureValidationService(
    private val openMeteoClient: OpenMeteoClient,
    private val authenticity: PhotoAuthenticityService
) {

    /**
     * The phenomenon Open-Meteo recorded for this place and time, and whether the capture earns XP.
     *
     * @param photoScores the cached `phenomenon_scores` from the photo's upload, or null for a
     *   photo that was never analysed. Null skips stage 2 rather than failing it.
     * @throws ServiceUnavailableException Open-Meteo did not answer, answered with no usable slot
     *   near [capturedAt], or answered with a code no [Phenomenon] covers.
     */
    fun validate(
        latitude: Double,
        longitude: Double,
        capturedAt: Instant,
        previous: LastKnownPosition?,
        locationIsMock: Boolean,
        photoScores: Map<String, Double>?
    ): ValidationResult {
        val hourly = openMeteoClient.fetchHourlyForecast(latitude, longitude)?.hourly
            ?: throw ServiceUnavailableException("The weather service is unavailable right now")

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
            throw ServiceUnavailableException("The weather service is unavailable right now")
        }

        val observedCode = hourly.weatherCode[nearestIndex]
            ?: throw ServiceUnavailableException("The weather service is unavailable right now")

        // Every code Open-Meteo documents maps to a species, so a null here is an upstream anomaly
        // and not something the user did. There is no honest row to write without a phenomenon.
        val phenomenon = Phenomenon.fromWeatherCode(observedCode)
            ?: throw ServiceUnavailableException("The weather service is unavailable right now")

        // Absent means daylight. Night only ever *skips* the photo check, so defaulting the other
        // way would silently disable stage 2 for every capture the moment the field went missing.
        val isDay = hourly.isDay.getOrNull(nearestIndex)?.let { it == 1 } ?: true

        val reason = when {
            locationIsMock -> UnconfirmedReason.MOCK_LOCATION
            !TravelPlausibility.isReachable(previous, latitude, longitude, capturedAt) ->
                UnconfirmedReason.IMPLAUSIBLE_TRAVEL
            authenticity.contradicts(phenomenon, photoScores, isDay) ->
                UnconfirmedReason.PHOTO_CONTRADICTS_WEATHER
            else -> null
        }

        return ValidationResult(
            phenomenon = phenomenon,
            status = if (reason == null) ValidationStatus.CONFIRMED else ValidationStatus.UNCONFIRMED,
            observedWeatherCode = observedCode,
            xpAwarded = if (reason == null) phenomenon.rarity.xp else 0,
            unconfirmedReason = reason
        )
    }

    /** Open-Meteo returns "2026-08-16T14:00" with no offset; we requested timezone=UTC. */
    private fun parseSlot(raw: String): Instant? = try {
        LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        /**
         * Covers hourly granularity plus slack for a truncated or gap-ridden upstream response —
         * NOT phone clock skew. There is no phone clock in this path: `capturedAt` is stamped by
         * the server before this is ever called, so a client's clock can never influence it.
         */
        val MAX_SKEW: Duration = Duration.ofMinutes(90)
    }
}
