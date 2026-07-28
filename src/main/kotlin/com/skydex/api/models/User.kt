package com.skydex.api.models

import jakarta.persistence.*
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false)
    var nome: String? = "",

    @Column(nullable = false)
    var email: String? = "",

    @Column(nullable = false)
    private var password: String? = "",

    @Column
    var dataEntrada: LocalDateTime = LocalDateTime.now()
): UserDetails {
    override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
        return mutableListOf(SimpleGrantedAuthority("ROLE_USER"))
    }

    override fun getPassword(): String? = this.password

    // O Spring Security usa o campo 'username' para autenticar. No seu app, é o email!
    override fun getUsername(): String? = this.email

    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = true
    fun definirSenha(novaSenha: String?){this.password = novaSenha}
}