package com.skydex.api.models

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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

    @Column(nullable = false)
    var latitude: Double = 0.0,

    @Column(nullable = false)
    var longitude: Double = 0.0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var phenomenon: Phenomenon = Phenomenon.CLOUDS,

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 16)
    var validationStatus: ValidationStatus = ValidationStatus.UNCONFIRMED,

    @Column(name = "observed_weather_code")
    var observedWeatherCode: Int? = null,

    @Column(name = "xp_awarded", nullable = false)
    var xpAwarded: Int = 0,

    @Column(name = "user_id", nullable = false)
    var userId: UUID
)
