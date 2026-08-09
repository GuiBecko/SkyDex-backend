package com.skydex.api.security

import com.auth0.jwt.JWT
import com.skydex.api.models.User
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

/**
 * A pure unit test: [TokenService] takes its secret as a constructor argument, so pinning the
 * token's lifetime needs no Spring context and no database.
 *
 * The zone juggling is the whole point. `generateExpirationDate` used to build a `LocalDateTime`
 * from the **system default** zone and then reinterpret it as a fixed `-03:00`, which silently
 * makes the token's lifetime a function of where the host thinks it is: two hours on a
 * `America/Sao_Paulo` developer laptop, five on a UTC container, eleven in Tokyo. A test that
 * simply asserted "about two hours" would therefore have passed on the machine this code was
 * written on and failed in production, so this one drives the default zone itself and demands the
 * same answer from every one of them.
 */
class TokenServiceTest {

    private val service = TokenService("unit-test-secret-do-not-use-in-production")
    private val originalZone: TimeZone = TimeZone.getDefault()

    private fun user() = User(
        id = null,
        name = "Test Pilot",
        email = "pilot@skydex.com",
        passwordHash = "irrelevant",
        joinedAt = Instant.now()
    )

    @AfterEach
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalZone)
    }

    /**
     * The lifetime is bounded on both sides against instants read either side of the call, so the
     * only slack allowed is the second of precision a JWT `exp` claim has (it is stored as epoch
     * seconds, so the value is truncated).
     */
    private fun assertTokenLastsTwoHours(zoneId: String) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))

        val before = Instant.now()
        val token = service.generateToken(user())
        val after = Instant.now()

        val expiresAt = JWT.decode(token).expiresAtAsInstant

        assertFalse(
            expiresAt.isBefore(before.plusSeconds(TWO_HOURS_IN_SECONDS - 1)),
            "under $zoneId the token expires too early: $expiresAt, issued around $before"
        )
        assertFalse(
            expiresAt.isAfter(after.plusSeconds(TWO_HOURS_IN_SECONDS + 1)),
            "under $zoneId the token lives too long: $expiresAt, issued around $after"
        )
    }

    @Test
    fun `a token lasts two hours on a host running UTC`() {
        // Every default container image. This is the case the old implementation got wrong, by
        // three hours.
        assertTokenLastsTwoHours("UTC")
    }

    @Test
    fun `a token lasts two hours on a host running the project's own timezone`() {
        assertTokenLastsTwoHours("America/Sao_Paulo")
    }

    @Test
    fun `a token lasts two hours on a host east of UTC`() {
        // The other side of the old bug: reinterpreting a Tokyo wall clock as -03:00 handed out a
        // token that had already expired before it was issued.
        assertTokenLastsTwoHours("Asia/Tokyo")
    }

    @Test
    fun `a generated token validates and carries the user's email as its subject`() {
        val token = service.generateToken(user())

        assertTrue(service.validateToken(token) == "pilot@skydex.com")
    }

    private companion object {
        const val TWO_HOURS_IN_SECONDS = 2L * 60 * 60
    }
}
