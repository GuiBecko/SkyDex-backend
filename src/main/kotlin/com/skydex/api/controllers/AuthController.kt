package com.skydex.api.controllers

import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import com.skydex.api.security.TokenService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.util.UUID

data class LoginRequest(
    @field:NotBlank(message = "Email nao deve ser nulo")
    @field:Email(message = "O formato do email é inválido")
    val email: String,

    @field:NotBlank(message = "Senha nao deve ser nula")
    val password: String
)

data class RegisterRequest(
    @field:NotBlank(message = "Nome nao deve ser nulo")
    val nome: String,

    @field:NotBlank(message = "Email nao deve ser nulo")
    @field:Email(message = "O formato do email é inválido")
    val email: String,

    @field:NotBlank(message = "Senha nao deve ser nula")
    val password: String
)

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: TokenService
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<Map<String, String>> {
        val emailRecebido = request.email
        val senhaRecebida = request.password

        var userBusca = userRepository.findByEmail(emailRecebido)
        if (userBusca == null) {
            // 400 - Usuário nao existe
            return ResponseEntity.badRequest().body(mapOf("error" to "Usuário não encontrado"))
        }else {
            var senhaCorreta = passwordEncoder.matches(senhaRecebida, userBusca.password)

            if(!senhaCorreta) {
                // 401 - Senha Incorreta
                return ResponseEntity.status(401).body(mapOf("error" to "email ou senha inválidos"))
            }else{
                //200 - Login sucesso
                val tokenGerado = tokenService.generateToken(userBusca)
                return ResponseEntity.ok().body(mapOf(
                    "mensagem" to "Login realizado com sucesso!",
                    "tokenGerado" to tokenGerado,
                    "userId" to userBusca.id.toString()))
            }
        }
    }

    @PostMapping("/register")
    fun registro(@Valid @RequestBody request: RegisterRequest): ResponseEntity<Map<String, String>> {
        val nomeRecebido = request.nome
        val emailRecebido = request.email
        val senhaRecebida = request.password

        val userBusca = userRepository.findByEmail(request.email)

        if (userBusca != null) {
            //400 - usuário ja existente
            return ResponseEntity.badRequest().body(mapOf("error" to "Usuário com esse email já cadastrado"))
        }else{
            val userNovo = User(
                id = UUID.randomUUID(),
                nome = nomeRecebido,
                email = emailRecebido,
                password = passwordEncoder.encode(senhaRecebida),
                dataEntrada = LocalDateTime.now()
            )
            userRepository.save(userNovo)
            //200 - usuário criado
            return ResponseEntity.ok().body(mapOf("mensagem" to "Usuário Registrado com sucesso"))
        }
    }
}