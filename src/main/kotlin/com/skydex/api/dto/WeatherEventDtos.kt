package com.skydex.api.dto

import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class CreateWeatherEventRequest(
    @field:NotBlank(message = "Title is required")
    val title: String,

    @field:NotBlank(message = "Description is required")
    val description: String,

    // Constrained to a path this server itself issued, not merely non-blank. `photoUrl` is
    // client-supplied, persisted verbatim, and then rendered by every friend who opens the feed —
    // so an unconstrained string lets any user plant `http://attacker.example/pixel.jpg` and
    // collect the IP and view time of everyone who scrolls past it. Requiring a relative
    // /api/photos/ path makes a foreign host unrepresentable rather than merely discouraged, and
    // excluding `/` from the filename blocks traversal into other endpoints on our own origin.
    @field:Pattern(
        regexp = "^/api/photos/[A-Za-z0-9._-]+\\.(jpg|png)$",
        message = "Photo URL must be a path returned by POST /api/photos"
    )
    val photoUrl: String,

    @field:DecimalMin(value = "-90.0", message = "must be between -90 and 90")
    @field:DecimalMax(value = "90.0", message = "must be between -90 and 90")
    val latitude: Double,

    @field:DecimalMin(value = "-180.0", message = "must be between -180 and 180")
    @field:DecimalMax(value = "180.0", message = "must be between -180 and 180")
    val longitude: Double,

    @field:NotBlank(message = "Phenomenon is required")
    val phenomenon: String,

    /**
     * The client's own report that the coordinates above came from a mock location provider
     * (Android's `Location.isFromMockProvider`). True means the capture cannot be CONFIRMED.
     *
     * Defaulted, and it has to stay defaulted: the shipped Android client does not send this field
     * yet (Task 14 adds it), and making it required would 400 every capture from an app already in
     * users' hands. The default is the honest one — an old client genuinely has not asserted
     * anything about mocking.
     *
     * Being client-asserted, this is worth exactly what the client's honesty is worth; see
     * `CaptureValidationService` for what it does and does not buy.
     */
    val locationIsMock: Boolean = false
)

data class WeatherEventResponse(
    val id: UUID,
    val title: String,
    val description: String,
    val photoUrl: String,
    val capturedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val userId: UUID,
    val authorName: String,
    val phenomenon: String,
    val phenomenonName: String,
    val rarity: String,
    val validationStatus: String,
    val xpAwarded: Int
) {
    companion object {
        /**
         * [baseUrl] is required, not optional, and that is the whole point.
         *
         * `weather_events.photo_url` is persisted **relative** so a stored row never carries a host
         * that can go stale — a new DHCP lease or a real deployment would otherwise leave every
         * historical capture pointing at an address that no longer serves those bytes. Composing
         * the host is therefore a read-side concern, and this is the single place it happens.
         *
         * An earlier revision applied it at the controller boundary via a `withAbsolutePhotoUrl`
         * extension — since removed, so do not go looking for it. That design cannot cover every
         * caller: a service that builds finished responses itself (the feed) has no mapping step
         * in its controller to hang the extension on, and forgetting it produced a silently
         * relative URL that renders as a broken image. As a required parameter, forgetting it is
         * a compile error instead — and no caller pays extra, since a response has no access to
         * configuration either way.
         *
         * The `startsWith("/")` guard keeps this idempotent and leaves an already-absolute value
         * alone, which matters for the externally-hosted URLs captures could carry before uploads
         * existed.
         */
        fun from(event: WeatherEvent, author: User, baseUrl: String) = WeatherEventResponse(
            id = event.id!!,
            title = event.title,
            description = event.description,
            photoUrl = if (event.photoUrl.startsWith("/")) baseUrl.trimEnd('/') + event.photoUrl
                       else event.photoUrl,
            capturedAt = event.capturedAt,
            latitude = event.latitude,
            longitude = event.longitude,
            userId = event.userId,
            authorName = author.name,
            phenomenon = event.phenomenon.name,
            phenomenonName = event.phenomenon.displayName,
            rarity = event.phenomenon.rarity.name,
            validationStatus = event.validationStatus.name,
            xpAwarded = event.xpAwarded
        )
    }
}
