package com.skydex.api.controllers

import com.skydex.api.domain.Phenomenon
import com.skydex.api.dto.CreateWeatherEventRequest
import com.skydex.api.dto.WeatherEventResponse
import com.skydex.api.errors.ForbiddenException
import com.skydex.api.errors.NotFoundException
import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import com.skydex.api.repositories.UserRepository
import com.skydex.api.repositories.WeatherEventRepository
import com.skydex.api.services.BadUploadException
import com.skydex.api.services.CaptureCommitService
import com.skydex.api.services.CaptureValidationService
import com.skydex.api.services.PhotoProvenanceService
import com.skydex.api.services.lastKnownPosition
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/events")
class WeatherEventController(
    private val events: WeatherEventRepository,
    private val users: UserRepository,
    private val validation: CaptureValidationService,
    private val photoProvenance: PhotoProvenanceService,
    private val captureCommit: CaptureCommitService,
    // Read-side only. `photo_url` is persisted relative so a stored row never carries a host that
    // can go stale; the absolute URL is composed on the way out by `WeatherEventResponse.from`,
    // which takes this as a required third argument. Nothing on the write path may use it.
    @Value("\${skydex.photos.public-base-url}") private val publicBaseUrl: String
) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: User,
        @Valid @RequestBody request: CreateWeatherEventRequest
    ): ResponseEntity<WeatherEventResponse> {
        val claimed = Phenomenon.fromNameOrNull(request.phenomenon)
            ?: throw BadUploadException("Unknown phenomenon: ${request.phenomenon}")

        // One stamp, used for BOTH the validation and the stored row. Reading Instant.now()
        // twice could straddle an hour boundary and validate against a slot the capture is
        // then not recorded in — rare, but it would be an unreproducible "why is this
        // UNCONFIRMED" bug. The client never supplies this; see Task 6.
        val capturedAt = Instant.now()

        // Checked before validation, on the same stamp handed to it below: a photo that is not
        // the caller's own, already spent, or expired costs no Open-Meteo call at all. This is a
        // read; the photo is not spent until the commit below, after scoring.
        val photo = photoProvenance.verify(request.photoUrl, currentUser.id!!, capturedAt)

        val result = validation.validate(
            claimed = claimed,
            latitude = request.latitude,
            longitude = request.longitude,
            capturedAt = capturedAt,
            previous = currentUser.lastKnownPosition(),
            locationIsMock = request.locationIsMock
        )

        // Spending the photo and inserting the capture are one transaction, and they come AFTER
        // the Open-Meteo call so that no database connection is held across it. The conditional
        // update inside is what actually enforces single use: verify() above cannot, because
        // between its read and this write sits a network round trip that two concurrent requests
        // can both be inside of.
        val saved = captureCommit.commit(
            WeatherEvent(
                id = null,
                title = request.title,
                description = request.description,
                // Rebuilt from the row that was verified, not echoed from the request. The two are
                // identical today only because the @Pattern on photoUrl forbids `/` in the
                // filename — a regex three layers away from here. Loosen it (a subdirectory, a
                // size-variant suffix) and echoing the request would let a capture be scored
                // against one photo and stored pointing at another.
                photoUrl = "/api/photos/${photo.filename}",
                capturedAt = capturedAt,
                latitude = request.latitude,
                longitude = request.longitude,
                phenomenon = claimed,
                validationStatus = result.status,
                observedWeatherCode = result.observedWeatherCode,
                xpAwarded = result.xpAwarded,
                userId = currentUser.id!!
            ),
            photoId = photo.id!!,
            now = capturedAt
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(WeatherEventResponse.from(saved, currentUser, publicBaseUrl))
    }

    @GetMapping("/mine")
    fun listMine(@AuthenticationPrincipal currentUser: User): ResponseEntity<List<WeatherEventResponse>> {
        val mine = events.findByUserIdOrderByCapturedAtDesc(currentUser.id!!)
        return ResponseEntity.ok(
            mine.map { WeatherEventResponse.from(it, currentUser, publicBaseUrl) }
        )
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): WeatherEventResponse {
        val event = events.findById(id).orElseThrow { NotFoundException("Capture not found") }
        // Author comes from the event, never from the caller: this endpoint has no ownership
        // restriction, so the two genuinely differ here. The test below pins that.
        val author = users.findById(event.userId).orElseThrow { NotFoundException("Capture not found") }
        return WeatherEventResponse.from(event, author, publicBaseUrl)
    }

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal currentUser: User,
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateWeatherEventRequest
    ): WeatherEventResponse {
        val event = events.findById(id).orElseThrow { NotFoundException("Capture not found") }
        if (event.userId != currentUser.id) {
            throw ForbiddenException("You can only modify your own captures")
        }
        event.title = request.title
        event.description = request.description
        // Neither capturedAt, the coordinates, NOR (as of Task 12b) photoUrl are updated here, and
        // for related reasons. Task 12 scores a capture against the real weather at an instant AND
        // a place. Freezing the time while leaving the pin editable just moves the cheat one axis
        // over — create a capture now, find where a storm is happening at that frozen instant, PUT
        // the coordinates there, collect the rare badge. Nothing legitimate is lost: the
        // coordinates are client-supplied at creation because the server cannot see the phone, and
        // letting them move afterwards only buys a second, better-informed attempt.
        // photoUrl joins the same pattern one step earlier in the chain: Task 12b makes creation
        // bind a capture to a real, fresh, single-use photo the caller uploaded, via
        // PhotoProvenanceService.claim. Leaving photoUrl editable here would let a capture be
        // scored against that photo and then have the evidence swapped for something else
        // afterwards — the update path never calls claim, so a swapped-in photo would not even
        // need to be the caller's own.
        // `CreateWeatherEventRequest` still carries latitude, longitude AND photoUrl, and this
        // handler silently ignores all three — the same accepted-and-ignored shape as capturedAt,
        // and pinned by the tests below.
        // Safe to pass currentUser as the author ONLY because the guard above proves
        // currentUser.id == event.userId. If that guard is ever relaxed — a moderator edit, a
        // shared album — this must go back to looking the author up from event.userId, or the
        // response will attribute the capture to whoever edited it.
        return WeatherEventResponse.from(events.save(event), currentUser, publicBaseUrl)
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal currentUser: User,
        @PathVariable id: UUID
    ): ResponseEntity<Void> {
        val event = events.findById(id).orElseThrow { NotFoundException("Capture not found") }
        if (event.userId != currentUser.id) {
            throw ForbiddenException("You can only modify your own captures")
        }
        events.delete(event)
        return ResponseEntity.noContent().build()
    }
}
