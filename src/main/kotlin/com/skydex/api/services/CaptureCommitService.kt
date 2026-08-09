package com.skydex.api.services

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
 * HikariCP connection for the duration of an outbound HTTP request that has no configured
 * timeouts. One slow upstream would then drain the pool and take down every endpoint, which is a
 * far worse failure than the one it would be preventing.
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
     * Re-reading the row under [UserRepository.findByIdForUpdate] serialises those captures: each
     * one sees the trail the previous one left, and the second of a simultaneous pair is downgraded
     * exactly as it would have been had it arrived a moment later.
     *
     * **Why the lock is safe here specifically.** This service exists to keep a transaction off the
     * Open-Meteo call — `OpenMeteoClient` has no timeouts, and a pool connection held across it
     * would take down every endpoint under one slow upstream. That argument does not extend to the
     * lock, and it is worth being precise about why: read-check-write only has to be atomic with
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
        val locked = users.findByIdForUpdate(event.userId)
        val reachable = TravelPlausibility.isReachable(
            previous = locked?.lastKnownPosition(),
            latitude = event.latitude,
            longitude = event.longitude,
            at = event.capturedAt
        )
        if (!reachable) {
            // observedWeatherCode is deliberately left alone. The weather really was observed and
            // really did match; what failed is presence, not the claim, and blanking the code would
            // erase a true fact about the row to make the verdict look tidier.
            event.validationStatus = ValidationStatus.UNCONFIRMED
            event.xpAwarded = 0
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
}
