package com.skydex.api.support

import com.skydex.api.domain.Phenomenon
import com.skydex.api.domain.ValidationStatus
import com.skydex.api.models.UploadedPhoto
import com.skydex.api.models.User
import com.skydex.api.models.WeatherEvent
import java.time.Instant
import java.util.UUID

/**
 * Fixture helpers shared by every integration test. They live as extension functions on the
 * test base so they can reach the injected repositories.
 */
fun IntegrationTestBase.persistUser(
    name: String = "Test Pilot",
    email: String = "pilot@skydex.com",
    password: String = "test-password"
): User = userRepository.save(
    User(
        id = null,
        name = name,
        email = email,
        passwordHash = passwordEncoder.encode(password),
        joinedAt = Instant.now()
    )
)

fun IntegrationTestBase.persistEvent(
    owner: User,
    title: String = "Aurora",
    description: String = "Green lights in the night sky",
    // Relative, like everything `POST /api/photos` issues and the only shape `POST /api/events`
    // now accepts. This helper writes straight to the repository and so bypasses validation
    // entirely — which is exactly why the default has to be kept honest by hand: a fixture that
    // no longer resembles a real row would let a read-path test pass on data the API could never
    // have produced.
    photoUrl: String = "/api/photos/test.jpg",
    capturedAt: Instant = Instant.now(),
    latitude: Double = -23.55,
    longitude: Double = -46.63,
    phenomenon: Phenomenon = Phenomenon.RAIN,
    validationStatus: ValidationStatus = ValidationStatus.CONFIRMED,
    xpAwarded: Int = Phenomenon.RAIN.rarity.xp
): WeatherEvent = weatherEventRepository.save(
    WeatherEvent(
        id = null,
        title = title,
        description = description,
        photoUrl = photoUrl,
        capturedAt = capturedAt,
        latitude = latitude,
        longitude = longitude,
        phenomenon = phenomenon,
        validationStatus = validationStatus,
        // A real code only when the status says the claim was confirmed. `create` can never write
        // a row where an UNCONFIRMED status sits next to a code that matches the claim — that
        // combination would mean the claim was actually observed but scored as if it wasn't — so a
        // fixture defaulting to `phenomenon.weatherCodes.first()` unconditionally could produce a
        // row `create` itself could never produce.
        observedWeatherCode = if (validationStatus == ValidationStatus.CONFIRMED) {
            phenomenon.weatherCodes.first()
        } else {
            null
        },
        xpAwarded = xpAwarded,
        userId = owner.id!!
    )
)

/**
 * Persists an `UploadedPhoto` row the way `POST /api/photos` would have, so a test can then cite
 * [filename] from `POST /api/events` and have `PhotoProvenanceService` recognize it as [owner]'s.
 */
fun IntegrationTestBase.persistUploadedPhoto(
    owner: User,
    filename: String = "${UUID.randomUUID()}.jpg",
    uploadedAt: Instant = Instant.now()
): UploadedPhoto = uploadedPhotoRepository.save(
    UploadedPhoto(
        id = null,
        filename = filename,
        uploaderId = owner.id!!,
        uploadedAt = uploadedAt,
        consumedAt = null
    )
)

/** Builds a ready-to-send `Authorization` header value for the given user. */
fun IntegrationTestBase.authHeaderFor(user: User): String =
    "Bearer " + tokenService.generateToken(user)
