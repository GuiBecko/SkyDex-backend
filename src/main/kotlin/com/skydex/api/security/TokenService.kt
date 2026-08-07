package com.skydex.api.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.skydex.api.models.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

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

    private fun generateExpirationDate(): Instant =
        LocalDateTime.now().plusHours(2).toInstant(ZoneOffset.of("-03:00"))
}