package com.skydex.api.dto

import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class CreateWeatherEventRequest(
    @field:NotBlank(message = "Title is required")
    val title: String,

    @field:NotBlank(message = "Description is required")
    val description: String,

    @field:NotBlank(message = "Photo URL is required")
    val photoUrl: String
)

data class WeatherEventResponse(
    val id: UUID,
    val title: String,
    val description: String,
    val photoUrl: String,
    val capturedAt: Instant,
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
            userId = event.userId,
            authorName = author.name
        )
    }
}
