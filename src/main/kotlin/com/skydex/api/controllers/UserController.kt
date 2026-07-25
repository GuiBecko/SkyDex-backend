package com.skydex.api.controllers

import com.skydex.api.models.User
import com.skydex.api.repositories.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/users")
class UserController(
    private val repo: UserRepository,
    private val passowdEncoder: PasswordEncoder
) {
    @PostMapping
    fun CriarUsuario(@RequestBody user: User): ResponseEntity<User> {
        user.password = passowdEncoder.encode(user.password)
        val userSalvo = repo.save(user)
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
    fun updateUser(@PathVariable id: UUID, @RequestBody user: User): ResponseEntity<User> {
        var userBusca = repo.findById(id)

        if (userBusca.isPresent) {
            val userOriginal = userBusca.get()

            userOriginal.nome = user.nome
            userOriginal.email = user.email
            userOriginal.password = passowdEncoder.encode(user.password)
            userOriginal.dataEntrada = user.dataEntrada



            val userSalvo = repo.save(userOriginal)
            return ResponseEntity.ok(userSalvo)
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
}