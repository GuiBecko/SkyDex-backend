package com.skydex.api.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.skydex.api.models.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class TokenService (
    @Value("\${TOKEN_JWT_SECRET}")
    private val secret: String
) {

    fun generateToken(user: User): String {
        val algorithm = Algorithm.HMAC256(secret)
        return JWT.create()
            .withIssuer("skydex-api")
            .withSubject(user.email)
            .withExpiresAt(generateExpirationDate())
            .sign(algorithm)
    }

    fun validateToken(token: String): String {
        val algorithm = Algorithm.HMAC256(secret)
        return JWT.require(algorithm)
            .withIssuer("skydex-api")
            .build()
            .verify(token)
            .subject
    }

    /**
     * Two hours from now, measured on the only clock that has no timezone to get wrong.
     *
     * This used to read `LocalDateTime.now().plusHours(2).toInstant(ZoneOffset.of("-03:00"))`,
     * which takes the wall-clock time in the **host's default zone** and then reinterprets it as
     * if it had been -03:00 all along. The token's lifetime was therefore two hours plus whatever
     * the host's offset from -03:00 happened to be: correct on a `America/Sao_Paulo` laptop, five
     * hours on a UTC container image, fourteen in Tokyo. `TokenServiceTest` pins all three.
     */
    private fun generateExpirationDate(): Instant =
        Instant.now().plus(TOKEN_LIFETIME)

    private companion object {
        val TOKEN_LIFETIME: Duration = Duration.ofHours(2)
    }
}