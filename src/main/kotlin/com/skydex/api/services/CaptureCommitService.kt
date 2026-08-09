package com.skydex.api.services

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
     * Consumes [photoId], saves [event], and moves the author's movement trail to where [event]
     * claims to be. The consume goes first so that losing the race throws before anything is
     * written, and being in one transaction means a failed insert rolls the stamp back rather than
     * burning the caller's photo.
     *
     * The trail moves on EVERY capture that gets this far, whatever the validation decided. It
     * records where the client claimed to be, not where it was believed to be — which is the point:
     * if only CONFIRMED captures advanced it, a cheater could park the trail wherever they liked
     * with a capture they never meant to have confirmed, and then jump from there.
     *
     * It moves in this transaction rather than after it so that a capture and the trail it leaves
     * cannot diverge. A trail written separately and then rolled back would leave the user's
     * recorded position pointing at a capture that does not exist.
     */
    @Transactional
    fun commit(event: WeatherEvent, photoId: UUID, now: Instant): WeatherEvent {
        photoProvenance.consume(photoId, now)
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
