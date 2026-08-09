package com.skydex.api.services

import com.skydex.api.models.User
import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
 * The caller's movement trail as a value, or null if they have never captured.
 *
 * All three columns are written together by [CaptureCommitService], so in practice they are either
 * all set or all null; requiring all three here means a half-written row degrades into "no trail"
 * rather than into a position with a missing coordinate.
 */
fun User.lastKnownPosition(): LastKnownPosition? {
    val latitude = lastCaptureLatitude ?: return null
    val longitude = lastCaptureLongitude ?: return null
    val at = lastCaptureAt ?: return null
    return LastKnownPosition(latitude, longitude, at)
}

/**
 * Could the user have physically got there in time?
 *
 * Lives on its own, as pure arithmetic over its arguments, because it is asked twice per capture
 * and the two askings are deliberately different. [CaptureValidationService] asks it up front,
 * against the trail as the request first saw it, so that an implausible capture costs no
 * Open-Meteo call. [CaptureCommitService] asks it again at the end, against the trail re-read
 * under a row lock, because the first answer was computed from an unsynchronised read and a burst
 * of concurrent captures would otherwise all be measured against the same stale budget.
 */
object TravelPlausibility {

    /**
     * A little above airliner cruise, so no real journey trips it.
     *
     * Be clear about what this buys, because it is less than it looks. It bounds the RATE of
     * long-distance hopping; it does not prevent hopping. Porto Alegre to Tokyo is roughly
     * 18,500 km, so a cheater who waits about 20 hours between the two clears this gate — one
     * intercontinental hop a day still passes, forever. It is also completely blind to positions
     * faked a few kilometres apart, which no plausible-speed rule can ever catch.
     *
     * What it does kill is the cheap version of the exploit, and that is the version that actually
     * threatens the game: scanning the globe for wherever a rare phenomenon is happening right now
     * and collecting several of them in an afternoon. That claim only holds because the check is
     * re-run under a lock at commit time — without that, the same burst just has to be issued
     * concurrently instead of serially. See [CaptureCommitService.commit].
     */
    const val MAX_SPEED_KMH = 900.0

    /**
     * Mean Earth radius. The haversine below assumes a sphere; the ~0.3% error against the real
     * ellipsoid is irrelevant at a threshold this coarse.
     */
    const val EARTH_RADIUS_KM = 6371.0

    private const val MILLIS_PER_HOUR = 3_600_000.0

    /**
     * Whether a traveller at [previous] could have reached ([latitude], [longitude]) by [at]
     * without exceeding [MAX_SPEED_KMH]. A caller with no trail — anyone's first capture — is
     * always reachable; there is nothing to be inconsistent with.
     *
     * Expressed as "distance covered <= distance reachable" rather than as a speed, which is not
     * merely style: `distanceKm / hours` divides by zero when two captures land inside the same
     * tick, and `Instant.now()` produces that often enough to matter. Multiplying instead makes
     * the zero-elapsed case fall out correctly with no special-casing — nothing is reachable in no
     * time, so the same spot still passes and anywhere else does not. Elapsed time is floored at
     * zero so that a clock stepped backwards by NTP degrades to that same case rather than making
     * every distance implausible.
     *
     * The `isFinite` conjunct is the fail-closed rule for the whole function: a NaN distance is
     * false against `<=` just as it is against `>`, so without it a NaN from anywhere would read
     * as "reachable" and wave the journey through. [greatCircleKm] is written not to produce one,
     * but "the arithmetic cannot go wrong" is a worse guarantee than "if it does, we refuse".
     */
    fun isReachable(
        previous: LastKnownPosition?,
        latitude: Double,
        longitude: Double,
        at: Instant
    ): Boolean {
        if (previous == null) return true

        val elapsedHours = Duration.between(previous.at, at).toMillis()
            .toDouble()
            .div(MILLIS_PER_HOUR)
            .coerceAtLeast(0.0)

        val travelledKm = greatCircleKm(previous.latitude, previous.longitude, latitude, longitude)
        return travelledKm.isFinite() && travelledKm <= MAX_SPEED_KMH * elapsedHours
    }

    /** Great-circle distance in kilometres (haversine), on a sphere of radius [EARTH_RADIUS_KM]. */
    private fun greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        // asin's argument is clamped because `a` can drift a hair above 1 through floating-point
        // error at antipodal points, and asin(1.0000000001) is NaN. Note that `coerceAtMost` is
        // `if (this > max) max else this`, so it does NOT itself catch a NaN arriving from
        // elsewhere — that is what `isReachable`'s isFinite check is for.
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a).coerceAtMost(1.0))
    }
}
