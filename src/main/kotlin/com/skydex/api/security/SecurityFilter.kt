package com.skydex.api.security

import com.auth0.jwt.exceptions.JWTVerificationException
import com.skydex.api.repositories.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the bearer token from each request and, if it is valid, marks the caller as
 * authenticated. An invalid or expired token leaves the context anonymous; the entry point
 * configured in SecurityConfig turns that into a 401.
 */
@Component
class SecurityFilter(
    private val tokenService: TokenService,
    private val userRepository: UserRepository
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = recoverToken(request)

        if (token != null) {
            val email = try {
                tokenService.validateToken(token)
            } catch (e: JWTVerificationException) {
                null
            }

            if (email != null) {
                val user = userRepository.findByEmail(email)
                if (user != null) {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(user, null, user.authorities)
                }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun recoverToken(request: HttpServletRequest): String? {
        val authHeader = request.getHeader("Authorization") ?: return null
        if (!authHeader.startsWith("Bearer ")) return null
        return authHeader.removePrefix("Bearer ").trim().ifBlank { null }
    }
}
