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
        fun from(event: WeatherEvent, author: User) = WeatherEventResponse(
            id = event.id!!,
            title = event.title,
            description = event.description,
            photoUrl = event.photoUrl,
            capturedAt = event.capturedAt,
            latitude = event.latitude,
            longitude = event.longitude,
            userId = event.userId,
            authorName = author.name
        )
    }
}

/**
 * Turns the stored relative `photoUrl` into something a client can actually fetch.
 *
 * Applied at the controller boundary rather than inside [WeatherEventResponse.Companion.from], so
 * the base URL never reaches the persistence path — what gets written to `weather_events.photo_url`
 * stays host-independent, and a row (which is immutable) can never go stale because the server
 * moved. If this is ever forgotten on a new endpoint the failure is loud and local — the image
 * simply does not render — rather than a row written with a host that will be wrong forever.
 *
 * The `startsWith("/")` guard makes it idempotent and leaves any already-absolute value alone,
 * which matters for the externally-hosted URLs captures could carry before uploads existed.
 */
fun WeatherEventResponse.withAbsolutePhotoUrl(baseUrl: String): WeatherEventResponse =
    if (photoUrl.startsWith("/")) copy(photoUrl = baseUrl.trimEnd('/') + photoUrl) else this
