package com.skydex.api.models


import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "eventos_catalogados")
class EventoMetereologico(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false)
    var titulo: String? = "",

    @Column(columnDefinition = "TEXT")
    var descricao: String? = "",

    @Column(nullable = false)
    var urlFoto: String? = "",

    @Column(nullable = false)
    var dataHoraRegistro: LocalDateTime = LocalDateTime.now()
)