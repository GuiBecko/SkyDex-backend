package com.skydex.api.controllers

import com.skydex.api.models.EventoMetereologico
import com.skydex.api.models.User
import com.skydex.api.repositories.EventoRepository
import com.skydex.api.repositories.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class UserRequest(
    @field:NotBlank(message = "Nome de usuário nao deve ser nulo")
    val username: String,

    @field:NotBlank(message = "Email nao deve ser nulo")
    @field:Email(message = "E-mail deve ter formato valido")
    val email: String,

    @field:NotBlank(message = "Senha nao deve ser nulo")
    val password: String,
)

@RestController
@RequestMapping("/api/users")
class UserController(
    private val repo: UserRepository,
    private val eventRepo: EventoRepository,
    private val passwordEncoder: PasswordEncoder
) {
    @PostMapping
    fun CriarUsuario(@Valid @RequestBody user: UserRequest): ResponseEntity<User> {
        val userNovo = User(
            nome = user.username,
            email = user.email,
            password = passwordEncoder.encode(user.password)
        )

        val userSalvo = repo.save(userNovo)
        return ResponseEntity.ok(userSalvo)
    }

    @GetMapping
    fun MostrarUsuarios(): ResponseEntity< List<User> > {
        val users = repo.findAll()
        return ResponseEntity.ok(users)
    }

    @GetMapping("/{id}")
    fun buscarUsuarioPorId(@PathVariable id: UUID): ResponseEntity<User> {
        val user = repo.findById(id)

        if (user.isPresent) {
            return ResponseEntity.ok(user.get())
        }else {
            return ResponseEntity.notFound().build()
        }
    }

    @PutMapping("/{id}")
    fun updateUser(@PathVariable id: UUID, @Valid @RequestBody user: UserRequest): ResponseEntity<User> {
        val userBusca = repo.findById(id)

        if (userBusca.isPresent) {
            val userOriginal = userBusca.get()
            userOriginal.nome = user.username
            userOriginal.email = user.email
            userOriginal.definirSenha(passwordEncoder.encode(user.password))
            return ResponseEntity.ok(repo.save(userOriginal))
        } else {
            return ResponseEntity.notFound().build()
        }
    }

    @DeleteMapping
    fun deleteUsers(): ResponseEntity<String> {
        val users = repo.deleteAll()
        return ResponseEntity.ok("Deletado com sucesso!")
    }

    @DeleteMapping("/{id}")
    fun deleteUser(@PathVariable id: UUID): ResponseEntity<String> {
        val user = repo.findById(id)

        return if (user.isPresent) {
            repo.delete(user.get())
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/{id}/eventos")
    fun listUserEvents(@PathVariable id: UUID): ResponseEntity<Any>{
        val userBusca = repo.findById(id)
        if (userBusca.isEmpty) {
            return ResponseEntity.status(404).body(mapOf("error" to "Usuário não encontrado"))
        }

        var userEvents: List<EventoMetereologico>  = eventRepo.findByUserId(id)

        if (userEvents.isEmpty()) {
            return ResponseEntity.status(404).body(mapOf("error" to "Usuário nao tem Eventos"))
        }

        return ResponseEntity.status(200).body(userEvents)

    }
}