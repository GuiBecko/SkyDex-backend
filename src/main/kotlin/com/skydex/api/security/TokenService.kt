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
            .withIssuer("skydex-api") // Quem emitiu o token
            .withSubject(user.email) // A informação principal que queremos guardar (o email)
            .withExpiresAt(generateExpirationDate()) // Tempo de validade da pulseira
            .sign(algorithm)
    }

    fun validateToken(token: String): String {
        val algorithm = Algorithm.HMAC256(secret)
        return JWT.require(algorithm)
            .withIssuer("skydex-api")
            .build()
            .verify(token)
            .subject // Devolve o email se o token for válido e não estiver expirado
    }

    private fun generateExpirationDate(): Instant {
        // O token expira em 2 horas. Depois disso, o app precisa logar de novo.
        return LocalDateTime.now().plusHours(2).toInstant(ZoneOffset.of("-03:00"))
    }
}