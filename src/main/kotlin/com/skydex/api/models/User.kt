package com.skydex.api.models

import jakarta.persistence.*
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
    var password: String? = "",

    @Column
    var dataEntrada: LocalDateTime = LocalDateTime.now()
)