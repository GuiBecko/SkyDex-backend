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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ValidationResult(
    val status: ValidationStatus,
    val observedWeatherCode: Int?,
    val xpAwarded: Int
)

/**
 * Where a user's previous capture claimed to be, and when. Absent for a user who has never
 * captured, which is why every caller handles a null.
 *
 * This is a snapshot of the trail on the `users` row, NOT of a capture row — see the note on
 * [com.skydex.api.models.User.lastCaptureLatitude] for why that distinction is the whole point.
 */
data class LastKnownPosition(
    val latitude: Double,
    val longitude: Double,
    val at: Instant
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
        if (!isReachable(previous, latitude, longitude, capturedAt)) return unconfirmed(null)

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

    /**
     * Whether a traveller at [previous] could have reached ([latitude], [longitude]) by
     * [capturedAt] without exceeding [MAX_SPEED_KMH]. A caller with no trail — anyone's first
     * capture — is always reachable; there is nothing to be inconsistent with.
     *
     * Expressed as "distance covered <= distance reachable" rather than as a speed, which is not
     * merely style: `distanceKm / hours` divides by zero when two captures land inside the same
     * tick, and `Instant.now()` produces that often enough to matter. Multiplying instead makes
     * the zero-elapsed case fall out correctly with no special-casing — nothing is reachable in no
     * time, so the same spot still passes and anywhere else does not. Elapsed time is floored at
     * zero so that a clock stepped backwards by NTP degrades to that same case rather than making
     * every distance implausible.
     */
    private fun isReachable(
        previous: LastKnownPosition?,
        latitude: Double,
        longitude: Double,
        capturedAt: Instant
    ): Boolean {
        if (previous == null) return true

        val elapsedHours = Duration.between(previous.at, capturedAt).toMillis()
            .toDouble()
            .div(MILLIS_PER_HOUR)
            .coerceAtLeast(0.0)

        val travelledKm = greatCircleKm(previous.latitude, previous.longitude, latitude, longitude)
        return travelledKm <= MAX_SPEED_KMH * elapsedHours
    }

    /** Great-circle distance in kilometres (haversine), on a sphere of radius [EARTH_RADIUS_KM]. */
    private fun greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        // asin's argument is clamped: `a` can drift a hair above 1 through floating-point error at
        // antipodal points, and asin(1.0000000001) is NaN, which would compare false against every
        // threshold and silently wave the most implausible journey there is straight through.
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a).coerceAtMost(1.0))
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

        /**
         * A little above airliner cruise, so no real journey trips it.
         *
         * Be clear about what this buys, because it is less than it looks. It bounds the RATE of
         * long-distance hopping; it does not prevent hopping. Porto Alegre to Tokyo is roughly
         * 18,500 km, so a cheater who waits about 20 hours between the two clears this gate — one
         * intercontinental hop a day still passes, forever. It is also completely blind to
         * positions faked a few kilometres apart, which no plausible-speed rule can ever catch.
         *
         * What it does kill is the cheap version of the exploit, and that is the version that
         * actually threatens the game: scanning the globe for wherever a rare phenomenon is
         * happening right now and collecting several of them in an afternoon.
         */
        const val MAX_SPEED_KMH = 900.0

        /** Mean Earth radius. The haversine formula assumes a sphere; the ~0.3% error against the
         *  real ellipsoid is irrelevant at a threshold this coarse. */
        const val EARTH_RADIUS_KM = 6371.0

        const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
