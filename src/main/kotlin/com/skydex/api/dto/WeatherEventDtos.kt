package com.skydex.api.dto

import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateWeatherEventRequest(
    @field:NotBlank(message = "Title is required")
    val title: String,

    @field:NotBlank(message = "Description is required")
    val description: String,

    @field:NotBlank(message = "Photo URL is required")
    val photoUrl: String,

    @field:DecimalMin(value = "-90.0", message = "must be between -90 and 90")
    @field:DecimalMax(value = "90.0", message = "must be between -90 and 90")
    val latitude: Double,

    @field:DecimalMin(value = "-180.0", message = "must be between -180 and 180")
    @field:DecimalMax(value = "180.0", message = "must be between -180 and 180")
    val longitude: Double
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
    val authorName: String
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
            authorName = author.name
        )
    }
}
