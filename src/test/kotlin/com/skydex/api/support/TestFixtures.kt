package com.skydex.api.support

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
    photoUrl: String = "http://localhost:8080/api/photos/test.jpg",
    capturedAt: Instant = Instant.now()
): WeatherEvent = weatherEventRepository.save(
    WeatherEvent(
        id = null,
        title = title,
        description = description,
        photoUrl = photoUrl,
        capturedAt = capturedAt,
        userId = owner.id!!
    )
)

/** Builds a ready-to-send `Authorization` header value for the given user. */
fun IntegrationTestBase.authHeaderFor(user: User): String =
    "Bearer " + tokenService.generateToken(user)
