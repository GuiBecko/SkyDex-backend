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
