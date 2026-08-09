package com.skydex.api.services

import com.skydex.api.models.WeatherEvent
import com.skydex.api.repositories.WeatherEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The one atomic step at the end of creating a capture: spend the photo and insert the row, or do
 * neither.
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
    private val events: WeatherEventRepository
) {

    /**
     * Consumes [photoId] and saves [event] together. The consume goes first so that losing the
     * race throws before anything is written, and being in one transaction means a failed insert
     * rolls the stamp back rather than burning the caller's photo.
     */
    @Transactional
    fun commit(event: WeatherEvent, photoId: UUID, now: Instant): WeatherEvent {
        photoProvenance.consume(photoId, now)
        return events.save(event)
    }
}
