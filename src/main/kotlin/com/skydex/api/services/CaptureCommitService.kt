package com.skydex.api.services

import com.skydex.api.domain.UnconfirmedReason
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.models.WeatherEvent
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The one atomic step at the end of creating a capture: spend the photo, insert the row and move
 * the author's movement trail, or do none of the three.
 *
 * It exists as its own service purely to own that transaction boundary, and it is kept
 * deliberately tiny and free of network I/O. `WeatherEventController.create` must NOT be
 * `@Transactional` itself: it calls Open-Meteo, and a transaction spanning that call would pin a
 * HikariCP connection for the duration of an outbound HTTP request. `OpenMeteoClient` now bounds
 * that duration (a 5s read timeout, added with the production-readiness sweep), which caps the
 * damage but does not remove it — under a slow upstream every concurrent capture would still hold
 * a pool connection for whole seconds, and the pool is far smaller than the request-thread pool.
 * Draining it takes down every endpoint, which is a far worse failure than the one it would be
 * preventing.
 */
@Service
class CaptureCommitService(
    private val photoProvenance: PhotoProvenanceService,
    private val events: WeatherEventRepository,
    private val users: UserRepository
) {

    /**
     * Consumes [photoId], re-checks that [event] is somewhere its author could have reached, saves
     * it, and moves their movement trail to where it claims to be. The consume goes first so that
     * losing the race throws before anything is written or locked, and being in one transaction
     * means a failed insert rolls the stamp back rather than burning the caller's photo.
     *
     * **Why travel is checked again here.** `CaptureValidationService` already checked it, but
     * against a trail read with no synchronisation at the start of the request. N concurrent
     * captures therefore all measure themselves against the SAME budget and all pass it, which is
     * only harmless while that budget is small. It is not small for long: after about twenty hours
     * of not capturing, the reachable radius is the whole planet, and fifty pre-photographed
     * captures fired at once would every one of them confirm. That turns the rate limit
     * [TravelPlausibility.MAX_SPEED_KMH] advertises from "one intercontinental hop a day" into
     * "unlimited, in one burst, once a day" — the collect-several-in-an-afternoon attack the whole
     * task exists to stop, just issued in parallel instead of in series.
     *
     * Re-reading the row under [UserRepository.lockTrailForUpdate] serialises those captures: each
     * one sees the trail the previous one left, and the second of a simultaneous pair is downgraded
     * exactly as it would have been had it arrived a moment later. That read returns columns rather
     * than a `User` for reasons that are entirely about correctness, not taste — see its KDoc before
     * changing it.
     *
     * **Why the lock is safe here specifically.** This service exists to keep a transaction off the
     * Open-Meteo call — a pool connection held across it would take down every endpoint under one
     * slow upstream, and `OpenMeteoClient`'s read timeout bounds that wait at seconds rather than
     * removing it. That argument does not extend to the lock, and it is worth being precise about
     * why: read-check-write only has to be atomic with
     * respect to ITSELF. All three statements are here, all three are local database work, and the
     * lock is held for the microseconds they take — never across the network call, which finished
     * before this method was entered.
     *
     * **The trail moves on EVERY capture that gets this far**, whatever the validation decided and
     * whatever the re-check decides. It records where the client claimed to be, not where it was
     * believed to be — which is the point: if only CONFIRMED captures advanced it, a cheater could
     * park the trail wherever they liked with a capture they never meant to have confirmed, and
     * then jump from there. It moves inside this transaction so a capture and the trail it leaves
     * cannot diverge.
     */
    @Transactional
    fun commit(event: WeatherEvent, photoId: UUID, now: Instant): WeatherEvent {
        photoProvenance.consume(photoId, now)

        // Lock ordering is photo-then-user everywhere, so two captures by one user cannot deadlock
        // against each other by grabbing the pair in opposite orders.
        val reachable = TravelPlausibility.isReachable(
            previous = lockedTrail(event.userId),
            latitude = event.latitude,
            longitude = event.longitude,
            at = event.capturedAt
        )
        if (!reachable) {
            // observedWeatherCode is deliberately left alone. The weather really was observed and
            // really is what the row records; what failed is presence, not the reading, and
            // blanking the code would erase a true fact to make the verdict look tidier.
            event.validationStatus = ValidationStatus.UNCONFIRMED
            event.xpAwarded = 0
            // Overwrite only when there is nothing more specific to protect. A row with no reason
            // yet, or PHOTO_CONTRADICTS_WEATHER, gets IMPLAUSIBLE_TRAVEL: this check is the
            // authoritative one for those cases, and a row that says PHOTO_CONTRADICTS_WEATHER when
            // what actually sank it was an impossible journey sends the user to fix the wrong thing.
            // MOCK_LOCATION is different: it is already the more specific and more actionable
            // diagnosis, and a mocked position failing the travel re-check is a consequence of the
            // mocking, not an independent finding, so it is left alone rather than relabelled.
            if (event.unconfirmedReason == null ||
                event.unconfirmedReason == UnconfirmedReason.PHOTO_CONTRADICTS_WEATHER
            ) {
                event.unconfirmedReason = UnconfirmedReason.IMPLAUSIBLE_TRAVEL
            }
        }

        val saved = events.save(event)
        users.recordLastCapture(
            id = saved.userId,
            latitude = saved.latitude,
            longitude = saved.longitude,
            at = saved.capturedAt
        )
        return saved
    }

    /**
     * The trail for [userId], read as scalars under an exclusive row lock held until this
     * transaction ends.
     *
     * Null when the user has never captured — and also, harmlessly, if the row has vanished, which
     * an authenticated request cannot actually produce. Both mean the same thing to the caller:
     * nothing to be inconsistent with.
     *
     * See [UserRepository.lockTrailForUpdate] for why this must not be an entity read.
     */
    private fun lockedTrail(userId: UUID): LastKnownPosition? {
        val row = users.lockTrailForUpdate(userId) ?: return null
        val latitude = row.latitude ?: return null
        val longitude = row.longitude ?: return null
        val at = row.at ?: return null
        return LastKnownPosition(latitude, longitude, at)
    }
}
