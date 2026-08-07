package com.skydex.api.controllers

import com.skydex.api.dto.ErrorResponse
import com.skydex.api.dto.LoginRequest
import com.skydex.api.dto.LoginResponse
import com.skydex.api.dto.RegisterRequest
import com.skydex.api.dto.UserResponse
import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import com.skydex.api.security.TokenService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService
) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<Any> {
        if (userRepository.findByEmail(request.email) != null) {
            return ResponseEntity.badRequest().body(ErrorResponse("Email already registered"))
        }

        val user = userRepository.save(
            User(
                id = null,
                name = request.name,
                email = request.email,
                passwordHash = passwordEncoder.encode(request.password),
                joinedAt = Instant.now()
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<Any> {
        val user = userRepository.findByEmail(request.email)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse("Invalid email or password"))

        if (!passwordEncoder.matches(request.password, user.password)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse("Invalid email or password"))
        }

        return ResponseEntity.ok(
            LoginResponse(
                token = tokenService.generateToken(user),
                userId = user.id!!,
                name = user.name
            )
        )
    }
}
