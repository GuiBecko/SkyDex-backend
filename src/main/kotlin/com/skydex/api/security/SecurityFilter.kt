package com.skydex.api.security

import com.skydex.api.repositories.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SecurityFilter(
    private val tokenService: TokenService,
    private val userRepository: UserRepository
) : OncePerRequestFilter() {

    // Esta função corre UMA VEZ a cada pedido que chega à tua API
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // 1. Tenta encontrar o token no cabeçalho do pedido
        val token = recoverToken(request)

        if (token != null) {
            // 2. Se encontrou, pede ao TokenService para validar e devolver o email
            val email = tokenService.validateToken(token)

            // 3. Procura o utilizador dono desse email na base de dados
            val user = userRepository.findByEmail(email)

            if (user != null) {
                // 4. Avisa o Spring Security: "Está tudo certo, podes considerar este utilizador autenticado!"
                val authentication = UsernamePasswordAuthenticationToken(user, null, emptyList())
                SecurityContextHolder.getContext().authentication = authentication
            }
        }

        // 5. Continua o fluxo normal do pedido (se não estiver autenticado, o próprio Spring bloqueia depois)
        filterChain.doFilter(request, response)
    }

    // Função auxiliar para extrair apenas o código do token, retirando a palavra "Bearer "
    private fun recoverToken(request: HttpServletRequest): String? {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null
        }
        return authHeader.replace("Bearer ", "")
    }
}