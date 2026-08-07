package com.skydex.api.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "weather_events")
class WeatherEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false)
    var title: String = "",

    @Column(columnDefinition = "TEXT", nullable = false)
    var description: String = "",

    @Column(name = "photo_url", nullable = false)
    var photoUrl: String = "",

    @Column(name = "captured_at", nullable = false)
    var capturedAt: Instant = Instant.now(),

    @Column(name = "user_id", nullable = false)
    var userId: UUID
)
