package com.skydex.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.skydex.api.dto.ErrorResponse
import com.skydex.api.security.SecurityFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig(
    private val securityFilter: SecurityFilter,
    private val objectMapper: ObjectMapper
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** Returns a JSON 401 instead of Spring's default 403 for unauthenticated requests. */
    @Bean
    fun authenticationEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write(objectMapper.writeValueAsString(ErrorResponse("Authentication required")))
        }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/auth/**").permitAll()
                auth.requestMatchers("/error").permitAll()
                auth.requestMatchers(HttpMethod.GET, "/api/photos/**").permitAll()
                auth.anyRequest().authenticated()
            }
            // Second layer behind PhotoStorageService's magic-byte check. Uploaded photos are
            // attacker-supplied bytes served from the API's own origin, so anything the byte check
            // ever misses must still be treated as the type its extension claims, never sniffed
            // and rendered. Spring Security sends this by default; stating it keeps the guarantee
            // from disappearing silently if the header defaults are ever customised.
            .headers { headers -> headers.contentTypeOptions { } }
            .exceptionHandling { it.authenticationEntryPoint(authenticationEntryPoint()) }
            .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
